package demo.vulnapp.config

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.ManagedRuntime
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceOwnerNativeRetentionTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `prepare close race retains a late real runtime and its prepared workers`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "lateRuntimeBody")
    }

    @Suppress("unused")
    fun lateRuntimeBody() {
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val runtime = AtomicReference<ManagedRuntime>()
        val failure = AtomicReference<Throwable?>()
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        val owner = ServiceExecutorOwner(Duration.ofMillis(100)) {
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, Duration.ofSeconds(5), Duration.ofSeconds(5)).also { actual ->
                runtime.set(actual)
                val domain = actual.prepare(policy, DomainLimits(1, 1))
                val invocation = actual.submit(domain, Callable { Thread.currentThread() })
                worker.set(invocation.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                acquired.countDown()
                release.await()
            }
        }
        val preparer = Thread {
            try {
                owner.prepare(policy)
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        preparer.start()
        try {
            assertTrue(acquired.await(10, TimeUnit.SECONDS))
            val start = System.nanoTime()
            assertIs<ExecutorDrain.Incomplete>(owner.stopAdmissionAndDrain())
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(250))
            assertTrue(worker.get().isAlive)
            assertFailsWith<RejectedExecutionException> { owner.prepare(policy) }
            release.countDown()
            preparer.join(5_000)
            assertFalse(preparer.isAlive)
            assertIs<RejectedExecutionException>(failure.get())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (ServiceExecutorOwner.drainRetained().isNotEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertEquals(ExecutorDrain.Complete, owner.stopAdmissionAndDrain())
            assertFalse(worker.get().isAlive)
            assertEquals(DrainReport.Complete, runtime.get().drain(Duration.ofSeconds(1)))
        } finally {
            release.countDown()
            preparer.join(5_000)
            owner.stopAdmissionAndDrain()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `interrupted combined drain preserves actual native cleanup failure and live counts`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "nativeFailureBody")
    }

    @Suppress("unused")
    fun nativeFailureBody() {
        val root = Files.createTempDirectory("owned-native-cleanup-")
        val release = CountDownLatch(1)
        val entered = CountDownLatch(2)
        val raw = Executors.newSingleThreadExecutor()
        NativeCleanupFault().use { fault ->
            val owner = ServiceExecutorOwner(Duration.ofMillis(250))
            val policy = WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead(root.toString())
                .allowJvmClasspath()
                .build()
            try {
                val idle = owner.prepare(policy)
                assertEquals("ready", idle.call { "ready" })
                val active = owner.prepare(policy)
                val invocation = active.submit(
                    Callable {
                        entered.countDown()
                        awaitRelease(release)
                        "finished"
                    },
                )
                owner.ownProfiling(raw).execute {
                    entered.countDown()
                    awaitRelease(release)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fault.failClose = true
                assertIs<ExecutorDrain.Incomplete>(owner.stopAdmissionAndDrain())
                assertTrue(fault.heldDescriptorCount() > 0, "cleanup failure must come from actual acquired native descriptors")
                Thread.currentThread().interrupt()
                val delivered = assertFailsWith<ServiceDrainException> { owner.close() }
                assertTrue(Thread.currentThread().isInterrupted)
                val reports = runtimeReports(delivered.report)
                assertTrue(reports.isNotEmpty())
                assertTrue(reports.any { it.unfinishedInvocations > 0 && it.liveWorkers > 0 })
                assertTrue(reports.any { it.cleanupFailure?.let { cause -> reachable(cause, fault.sentinel) } == true })
                assertTrue(reachable(delivered, fault.sentinel), "native failure must remain reachable by identity through throwing close")
                assertFalse(invocation.executionEnd().toCompletableFuture().isDone)
                Thread.interrupted()
                fault.failClose = false
                release.countDown()
                assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertTrue(raw.awaitTermination(5, TimeUnit.SECONDS))
                val retry = assertIs<ExecutorDrain.Incomplete>(owner.stopAdmissionAndDrain())
                assertTrue(
                    runtimeReports(retry).any {
                        it.cleanupFailure?.let { cause ->
                            reachable(cause, fault.sentinel)
                        } == true
                    },
                    "core's unresolved cleanup latch must not be cleared by an application retry",
                )
            } finally {
                Thread.interrupted()
                fault.failClose = false
                release.countDown()
                raw.shutdownNow()
                raw.awaitTermination(5, TimeUnit.SECONDS)
                owner.stopAdmissionAndDrain()
            }
        }
        root.toFile().deleteRecursively()
    }

    private fun awaitRelease(latch: CountDownLatch) {
        while (latch.count > 0) {
            try {
                latch.await()
            } catch (_: InterruptedException) {
                // Preserve the live native owner until explicitly released.
            }
        }
    }

    private fun runtimeReports(report: ExecutorDrain.Incomplete): List<DrainReport.Incomplete> =
        when (report) {
        is ExecutorDrain.Incomplete.Runtime -> listOf(report.report)
        is ExecutorDrain.Incomplete.Multiple -> report.failures.flatMap(::runtimeReports)
        is ExecutorDrain.Incomplete.Timeout, ExecutorDrain.Incomplete.Interrupted, is ExecutorDrain.Incomplete.ProfilerCleanup,
        is ExecutorDrain.Incomplete.CollectorCoverage, is ExecutorDrain.Incomplete.InProgress,
        -> emptyList()
    }

    private fun reachable(
        root: Throwable,
        expected: Throwable,
    ): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())

        fun visit(current: Throwable): Boolean = seen.add(current) && (current === expected || current.cause?.let(::visit) == true || current.suppressed.any(::visit))
        return visit(root)
    }
}
