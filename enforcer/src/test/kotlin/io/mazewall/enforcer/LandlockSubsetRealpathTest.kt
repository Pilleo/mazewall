package io.mazewall.enforcer

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Policy
import io.mazewall.Platform
import io.mazewall.PlatformProvider
import io.mazewall.RealPlatformProvider
import io.mazewall.core.SandboxedPath
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.enforcer.state.ContainerState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import kotlin.test.assertFailsWith

/**
 * Regression tests for issue-20260823-135558: the Landlock nested-install subset check must
 * compare realpath-resolved sets (Landlock binds rules to dentries), and must fail closed when
 * permissions would expand.
 */
class LandlockSubsetRealpathTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path


    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
    }

    private fun installMockEnvironment() {
        val mockPlatform = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockPlatform)
        val mockProcess = object : io.mazewall.MockNativeProcess() {
            override fun prctl(command: io.mazewall.core.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (command is io.mazewall.core.PrctlCommand.GetSeccomp) {
                    return LinuxNative.SyscallResult.Success(2L)
                }
                return super.prctl(command)
            }
        }
        LinuxNative.setEngine(MockNativeEngine(process = mockProcess))
    }

    @Test
    fun `nested install via symlinked spelling of same directory is accepted`() {
        installMockEnvironment()
        val realDir = Files.createDirectories(tempDir.resolve("real"))
        val link = tempDir.resolve("link")
        Files.createSymbolicLink(link, realDir)

        ContainedExecutors.installOnCurrentThread(
            Policy.threadLocalBuilder().allowFsRead(realDir.toString()).build()
        )

        // Same dentry spelled through the symlink: must NOT be treated as permission expansion.
        ContainedExecutors.installOnCurrentThread(
            Policy.threadLocalBuilder().allowFsRead(link.toString()).build()
        )
    }

    @Test
    fun `nested install expanding read scope is rejected`() {
        installMockEnvironment()
        val allowed = Files.createDirectories(tempDir.resolve("allowed"))

        ContainedExecutors.installOnCurrentThread(
            Policy.threadLocalBuilder().allowFsRead(allowed.toString()).build()
        )

        val expansion = assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(
                Policy.threadLocalBuilder().allowFsRead(tempDir.toString()).build()
            )
        }
        assert(expansion.message!!.contains("Cannot expand"))
    }
}
