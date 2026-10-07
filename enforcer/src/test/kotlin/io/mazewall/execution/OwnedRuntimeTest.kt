package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnedRuntimeTest {
    private val timeout = Duration.ofSeconds(5)
    private lateinit var engine: MockNativeEngine

    @BeforeEach
    fun prepareNative() {
        engine = MockNativeEngine().apply {
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
    }

    @AfterEach
    fun resetNative() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    private fun open(): ManagedRuntime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)

    private fun policy(): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.CONNECT).build()

    @Test
    fun `declared runtime cannot misreport incompatible process mapping floor`() {
        var opened: ManagedRuntime? = null
        try {
            assertFailsWith<IllegalArgumentException> {
                ManagedRuntime.open(RuntimeProfile.NATIVE_IMAGE, Policy.NO_EXEC_HOTSPOT, timeout, timeout).also { opened = it }
            }
        } finally {
            opened?.close()
        }
    }

    @Test
    fun `worker startup preserves original fatal identity and retires all owned threads`() {
        open().use { runtime ->
            val fatal = AssertionError("native startup fatal")
            engine.process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs) throw fatal
                LinuxNative.SyscallResult.Success(2L)
            }
            val failure = assertFailsWith<AssertionError> { runtime.prepare(policy(), DomainLimits(2, 2)) }
            assertTrue(failure === fatal)
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("mazewall-domain-") })
        }
    }

    @Test
    fun `foreign domains and unsupported nested dispatch cannot enter application work`() {
        open().use { first ->
            open().use { second ->
                val domain = first.prepare(policy(), DomainLimits(1, 1))
                val calls = AtomicInteger()
                assertFailsWith<IllegalArgumentException> { second.submit(domain, Callable { calls.incrementAndGet() }) }
                assertEquals(
                    42,
                    first
                        .submit(
                        domain,
                        Callable {
                    assertFailsWith<RejectedExecutionException> { first.submit(domain, Callable { calls.incrementAndGet() }) }
                    assertFailsWith<IllegalStateException> { first.prepare(policy(), DomainLimits(1, 1)) }
                    42
                },
                    ).result()
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS),
                )
                assertEquals(0, calls.get())
            }
        }
    }

    @Test
    fun `ordinary business failure preserves a healthy installed worker`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val business = IllegalArgumentException("business failure")
            val failed = runtime.submit(domain, Callable<Int> { throw business })
            val ended = assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertTrue(ended.failure === business)
            assertEquals(
                42,
                runtime
                .submit(domain, Callable { 42 })
                .result()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun `preparation timeout cancels bootstrap without hiding its live workers`() {
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, Duration.ofSeconds(1), timeout).use { runtime ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            engine.process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs && Thread.currentThread().name.startsWith("mazewall-domain-")) {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            // native startup deliberately remains live
                        }
                    }
                }
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
            try {
                assertFailsWith<TimeoutException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                check(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<IllegalStateException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ZERO))
                release.countDown()
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `failed live generation cannot be hidden by preparing replacement capacity`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(2, 2))
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val survivor = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        }
                    }
                    1
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val failed = runtime.submit(domain, Callable<Int> { throw AssertionError("generation failure") })
                assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                check(interrupted.await(5, TimeUnit.SECONDS))
                assertFalse(survivor.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<IllegalStateException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                release.countDown()
                survivor.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `caller future mutation cannot retire physical work or cancel internal delivery`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                handle.result().toCompletableFuture().cancel(true)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
                release.countDown()
                assertEquals(42, handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `owned workers retain prepared native authority and refuse caller execution on overload`() {
        open().use { runtime ->
            val foreignCalls = AtomicInteger()
            LinuxNative.setEngine(
                MockNativeEngine().apply {
                process.onPrctl = {
                    foreignCalls.incrementAndGet()
                    LinuxNative.SyscallResult.Error(1, -1L)
                }
            },
            )
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = Thread.currentThread()
            try {
                val first = runtime.submit(
                    domain,
                    Callable {
                    assertFalse(Thread.currentThread().isVirtual)
                    assertTrue(Thread.currentThread() !== caller)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val second = runtime.submit(domain, Callable { 43 })
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("must not run on caller") }) }
                release.countDown()
                assertEquals(42, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(43, second.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertIs<ExecutionEnd.Finished>(first.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(0, foreignCalls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `running cancellation retains admission until physical completion`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        }
                    }
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                assertEquals(CancellationDisposition.REQUESTED, handle.requestCancellation())
                check(interrupted.await(5, TimeUnit.SECONDS))
                assertTrue(handle.result().toCompletableFuture().isCompletedExceptionally)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 1 }) }
                release.countDown()
                assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(CancellationDisposition.ALREADY_ENDED, handle.requestCancellation())
                assertFalse(
                    runtime
                    .submit(domain, Callable { Thread.currentThread().isInterrupted })
                    .result()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS),
                )
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `queued cancellation never enters the body and frees bounded admission`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            try {
                val first = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    1
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val queued = runtime.submit(domain, Callable { calls.incrementAndGet() })
                assertEquals(CancellationDisposition.REQUESTED, queued.requestCancellation())
                assertIs<ExecutionEnd.NotStarted>(queued.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                val next = runtime.submit(domain, Callable { 3 })
                release.countDown()
                assertEquals(1, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(3, next.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(0, calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `drain reports live execution and stops admission until actual retirement`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ZERO))
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 1 }) }
                release.countDown()
                assertEquals(42, handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `failed worker preparation retires its generation before any application entry`() {
        open().use { runtime ->
            engine.process.onPrctl = { LinuxNative.SyscallResult.Success(0L) }
            assertFailsWith<PartialInstallationException> { runtime.prepare(policy(), DomainLimits(2, 2)) }
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("mazewall-domain-") })
        }
    }
}
