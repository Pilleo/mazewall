package io.mazewall.enforcer.api

import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeProcess
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CapturedSeccompBackendNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `captured real backend completes installation despite global replacement during setup`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCapturedRealBackend")
    }

    fun verifyCapturedRealBackend() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        System.setProperty("io.mazewall.selfVerify", "true")
        val real = LinuxNative.snapshot()
        val foreignCalls = AtomicInteger()
        val foreign = MockNativeEngine().apply {
            process.onPrctl = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
            onSyscall = { _, _, _, _, _, _, _ ->
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
        }
        val selectedProcess = object : NativeProcess by real.process {
            override fun prctl(command: PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (command is PrctlCommand.SetNoNewPrivs) LinuxNative.setEngine(foreign)
                return real.process.prctl(command)
            }
        }
        LinuxNative.setEngine(object : NativeEngine by real {
            override val process: NativeProcess = selectedProcess
        })
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.EXECVE).build()
        try {
            Executors.newSingleThreadExecutor().use { worker ->
                assertEquals(42, ContainedExecutors.wrap(worker, policy).submit(Callable { 42 }).get(10, TimeUnit.SECONDS))
                val mode = worker.submit(Callable { real.process.prctl(PrctlCommand.GetSeccomp) }).get(10, TimeUnit.SECONDS)
                assertEquals(2L, assertIs<LinuxNative.SyscallResult.Success<Long, *>>(mode).value)
            }
            assertEquals(0, foreignCalls.get())
        } finally {
            System.clearProperty("io.mazewall.selfVerify")
            LinuxNative.resetToDefault()
        }
    }
}
