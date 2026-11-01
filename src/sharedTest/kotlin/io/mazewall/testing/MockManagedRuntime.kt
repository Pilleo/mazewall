package io.mazewall.testing

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockPlatformProvider
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.PrctlCommand
import java.time.Duration

/** Real owned workers with native installation mocked; never evidence of kernel enforcement. */
internal fun <T> withMockManagedRuntime(block: (ManagedRuntime) -> T): T {
    val engine = MockNativeEngine().apply {
        process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
    }
    LinuxNative.setEngine(engine)
    Platform.setProvider(MockPlatformProvider())
    return try {
        val timeout = Duration.ofSeconds(5)
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use(block)
    } finally {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }
}
