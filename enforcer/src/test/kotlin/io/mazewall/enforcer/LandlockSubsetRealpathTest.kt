package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.PlatformProvider
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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

            override fun getLandlockCapability(): io.mazewall.enforcer.api.KernelCapability<Int> =
                io.mazewall.enforcer.api.KernelCapability
                .Available(5)

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockPlatform)
        val mockProcess = object : io.mazewall.enforcer.api.MockNativeProcess() {
            override fun prctl(command: io.mazewall.platform.syscall.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (command is io.mazewall.platform.syscall.PrctlCommand.GetSeccomp) {
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
            Policy.threadLocalBuilder().allowFsRead(realDir.toString()).build(),
        )

        // Same dentry spelled through the symlink: must NOT be treated as permission expansion.
        ContainedExecutors.installOnCurrentThread(
            Policy.threadLocalBuilder().allowFsRead(link.toString()).build(),
        )
    }

    @Test
    fun `nested install expanding read scope is rejected`() {
        installMockEnvironment()
        val allowed = Files.createDirectories(tempDir.resolve("allowed"))

        ContainedExecutors.installOnCurrentThread(
            Policy.threadLocalBuilder().allowFsRead(allowed.toString()).build(),
        )

        val expansion = assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(
                Policy.threadLocalBuilder().allowFsRead(tempDir.toString()).build(),
            )
        }
        assert(expansion.message!!.contains("Cannot expand"))
    }
}
