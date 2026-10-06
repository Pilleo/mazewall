package io.mazewall.enforcer.api

import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FailedInstallationQuarantineTest {
    private val reportedMode = AtomicLong(0L)
    private val nativeCalls = AtomicInteger()

    @BeforeEach
    fun prepareRuntime() {
        val engine = MockNativeEngine()
        engine.process.onPrctl = { command ->
            nativeCalls.incrementAndGet()
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) reportedMode.get() else 0L)
        }
        engine.onSyscall = { _, _, _, _, _, _, _ ->
            nativeCalls.incrementAndGet()
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(engine)
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(8)

            override fun getLandlockAbiVersion(): Int = 8

            override fun probeSeccompTsync(): Boolean = true

            override fun probeSeccompUserNotif(): Boolean = true

            override fun probeCetSupported(): Boolean = false
        })
    }

    @AfterEach
    fun restoreRuntime() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @ParameterizedTest
    @EnumSource(Platform.FallbackBehavior::class)
    fun `failed carrier cannot enter a later task through another wrapper`(fallback: Platform.FallbackBehavior) {
        System.setProperty("io.mazewall.fallback", fallback.name)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        val calls = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = ContainedExecutors.wrap(worker, policy)
            val initial = assertFailsWith<ExecutionException> {
                first.submit(Callable { calls.incrementAndGet() }).get(5, TimeUnit.SECONDS)
            }
            val failure = assertIs<PartialInstallationException>(initial.cause)
            assertTrue(failure.progress.seccompApplied)
            val retainedDepth = worker.submit(Callable { ContainmentStateRegistry.threadState.filterDepth }).get(5, TimeUnit.SECONDS)
            assertTrue(retainedDepth > 0)

            reportedMode.set(2L)
            System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
            val beforeRetry = nativeCalls.get()
            val another = ContainedExecutors.wrap(worker, policy)
            val rejected = assertFailsWith<ExecutionException> {
                another.submit(Callable { calls.incrementAndGet() }).get(5, TimeUnit.SECONDS)
            }
            assertSame(failure, rejected.cause)
            assertEquals(beforeRetry, nativeCalls.get(), "Quarantine must reject before any new native operation")
            assertEquals(0, calls.get())
        } finally {
            worker.shutdown()
            check(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `ordinary task failure does not quarantine a successfully installed worker`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        reportedMode.set(2L)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        Executors.newSingleThreadExecutor().use { worker ->
            val contained = ContainedExecutors.wrap(worker, policy)
            assertFailsWith<ExecutionException> {
                contained.submit(Callable<Int> { throw IllegalArgumentException("business failure") }).get(5, TimeUnit.SECONDS)
            }
            assertEquals(42, contained.submit(Callable { 42 }).get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `thread quarantine does not block a fresh worker from the unaffected manager context`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        Executors.newSingleThreadExecutor().use { failedWorker ->
            val contained = ContainedExecutors.wrap(failedWorker, policy)
            assertFailsWith<ExecutionException> { contained.submit(Callable { 0 }).get(5, TimeUnit.SECONDS) }
        }
        reportedMode.set(2L)
        Executors.newSingleThreadExecutor().use { freshWorker ->
            assertEquals(42, ContainedExecutors.wrap(freshWorker, policy).submit(Callable { 42 }).get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failed process installation rejects fresh workers despite later successful probes`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyFailedProcessQuarantine")
    }

    fun verifyFailedProcessQuarantine() {
        prepareRuntime()
        try {
        System.setProperty("io.mazewall.fallback", "FAIL")
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        val failure = assertFailsWith<PartialInstallationException> { ContainedExecutors.installOnProcess(policy) }
        assertTrue(failure.progress.processWide)
        assertTrue(failure.progress.seccompApplied)
        reportedMode.set(2L)
        val beforeRetry = nativeCalls.get()
        Executors.newSingleThreadExecutor().use { worker ->
            val contained = ContainedExecutors.wrap(worker, policy)
            val rejected = assertFailsWith<ExecutionException> {
                contained.submit(Callable { 42 }).get(5, TimeUnit.SECONDS)
            }
            assertSame(failure, rejected.cause)
        }
        assertEquals(beforeRetry, nativeCalls.get())
        } finally {
            restoreRuntime()
        }
    }
}
