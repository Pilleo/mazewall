package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContainedExecutorTest {
    @Test
    fun `owned invocation retains its worker until physical retirement without a delegate view`() {
        val engine = MockNativeEngine().apply {
            process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
        }
        LinuxNative.setEngine(engine)
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(8)

            override fun probeSeccompTsync(): Boolean = true

            override fun probeSeccompUserNotif(): Boolean = true

            override fun probeCetSupported(): Boolean = false
        })
        try {
            val timeout = Duration.ofSeconds(5)
            val caller = Thread.currentThread()
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
                val domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), DomainLimits(1, 1))
                val handle = runtime.submit(domain, Callable { Thread.currentThread() })
                val worker = handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertTrue(worker !== caller)
                assertFalse(worker.isVirtual)
                assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                assertFalse(worker.isAlive)
            }
        } finally {
            LinuxNative.resetToDefault()
            Platform.resetToDefault()
            ContainmentStateRegistry.threadState = ContainerState()
            ContainmentStateRegistry.processState = ContainerState()
        }
    }
}
