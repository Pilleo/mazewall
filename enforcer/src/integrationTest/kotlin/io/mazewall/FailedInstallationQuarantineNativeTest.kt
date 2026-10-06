package io.mazewall.enforcer.api

import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeProcess
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FailedInstallationQuarantineNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `failed verification of an actual kernel filter quarantines its worker`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyActualFilterQuarantine")
    }

    fun verifyActualFilterQuarantine() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        val actual = LinuxNative.snapshot()
        val failingVerification = object : NativeProcess by actual.process {
            override fun prctl(command: PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                if (command is PrctlCommand.GetSeccomp) LinuxNative.SyscallResult.Success(0L) else actual.process.prctl(command)
        }
        LinuxNative.setEngine(object : NativeEngine by actual {
            override val process: NativeProcess = failingVerification
        })
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.EXECVE).build()
        val calls = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val initial = assertFailsWith<ExecutionException> {
                ContainedExecutors.wrap(worker, policy).submit(Callable { calls.incrementAndGet() }).get(10, TimeUnit.SECONDS)
            }
            val failure = assertIs<PartialInstallationException>(initial.cause)
            assertTrue(failure.progress.seccompApplied)
            val mode = worker.submit(Callable { actual.process.prctl(PrctlCommand.GetSeccomp) }).get(10, TimeUnit.SECONDS)
            assertEquals(2L, assertIs<LinuxNative.SyscallResult.Success<Long, *>>(mode).value)
            assertTrue(worker.submit(Callable { ContainmentStateRegistry.threadState.filterDepth }).get(10, TimeUnit.SECONDS) > 0)

            LinuxNative.setEngine(actual)
            val rejected = assertFailsWith<ExecutionException> {
                ContainedExecutors.wrap(worker, policy).submit(Callable { calls.incrementAndGet() }).get(10, TimeUnit.SECONDS)
            }
            assertSame(failure, rejected.cause)
            assertEquals(0, calls.get())
        } finally {
            worker.shutdown()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
            LinuxNative.resetToDefault()
        }
    }
}
