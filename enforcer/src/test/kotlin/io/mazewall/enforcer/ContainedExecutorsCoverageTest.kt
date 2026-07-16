package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContainedExecutorsCoverageTest {

    @AfterEach
    fun tearDown() {
        Platform.resetToDefault()
        LinuxNative.resetToDefault()
        ThreadStateRegistry.state = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun testIsPathSubsetLogic() {
        val p1 = Policy.builder().allowFsRead("/tmp").build()
        val p2 = Policy.builder().allowFsRead("/").build()

        ThreadStateRegistry.state = ThreadStateRegistry.state.withLandlockPolicy(p1.definition)
        assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(p2)
        }
    }

    @Test
    fun testHandleUnsupportedPlatformBehaviors() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun hasKernelSeccompSupport(): Boolean = false
            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        System.setProperty("io.mazewall.fallback", "FAIL")
        assertFailsWith<UnsupportedOperationException> {
            ContainedExecutors.installOnCurrentThread(Policy.builder().build())
        }

        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())

        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())
    }
}
