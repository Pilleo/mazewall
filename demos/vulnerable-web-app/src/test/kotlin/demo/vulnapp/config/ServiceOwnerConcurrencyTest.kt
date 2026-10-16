package demo.vulnapp.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceOwnerConcurrencyTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `recoverable and fatal raw shutdown preserve identity attempt later owners and retain exact retry`() {
        for (sentinel in listOf(IllegalStateException("shutdown ordinary"), LinkageError("shutdown fatal"))) {
            val first = Executors.newSingleThreadExecutor()
            val held = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attempts = AtomicInteger()
            val laterAttempts = AtomicInteger()
            val secondary = IllegalArgumentException("later shutdown failure")
            val firstBoundary = object : ExecutorService by first {
                override fun shutdown() {
                    if (attempts.getAndIncrement() == 0) throw sentinel else first.shutdown()
                }
            }
            val laterBoundary = object : ExecutorService by held {
                override fun shutdown() {
                    if (laterAttempts.getAndIncrement() == 0) throw secondary else held.shutdown()
                }
            }
            val owner = ServiceExecutorOwner(Duration.ofMillis(100))
            owner.ownProfiling(firstBoundary)
            val operation = owner.ownProfiling(laterBoundary)
            operation.execute {
                entered.countDown()
                awaitRelease(release)
            }
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val delivered = assertFailsWith<Throwable> { owner.close() }
                if (sentinel is Error) assertSame(sentinel, delivered) else assertSame(sentinel, delivered.cause)
                assertTrue(reachable(delivered, secondary))
                assertTrue(laterAttempts.get() > 0, "later acquired pools must receive shutdown despite the first failure")
                assertFailsWith<java.util.concurrent.RejectedExecutionException> { operation.call { "late" } }
                assertTrue(ServiceExecutorOwner.drainRetained().isNotEmpty(), "destruction must retain the actual held owner")
                release.countDown()
                assertTrue(held.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(ServiceExecutorOwner.drainRetained().isEmpty())
                assertEquals(ExecutorDrain.Complete, owner.stopAdmissionAndDrain())
                assertTrue(first.isTerminated)
            } finally {
                release.countDown()
                first.shutdownNow()
                held.shutdownNow()
                held.awaitTermination(1, TimeUnit.SECONDS)
                owner.stopAdmissionAndDrain()
            }
        }
    }

    @Test
    fun `collector close cannot hold admission state or prevent another collector attempt`() {
        val owner = ServiceExecutorOwner(Duration.ofMillis(100))
        val first = Executors.newSingleThreadExecutor()
        val other = Executors.newSingleThreadExecutor()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val otherClosed = CountDownLatch(1)
        val sentinel = IllegalStateException("actual owned collector close failure")
        val attempts = AtomicInteger()
        val stream = Files.newInputStream(Files.writeString(directory.resolve("owned"), "resource"))
        owner.ownProfiling(first) { acquired ->
            acquired.attach(first) {
                closing.countDown()
                awaitRelease(release)
                if (attempts.getAndIncrement() == 0) throw sentinel
                stream.close()
            }
        }
        owner.ownProfiling(other) { acquired -> acquired.attach(other) { otherClosed.countDown() } }
        try {
            val start = System.nanoTime()
            assertIs<ExecutorDrain.Incomplete>(owner.stopAdmissionAndDrain())
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(250))
            assertTrue(closing.await(1, TimeUnit.SECONDS))
            assertTrue(otherClosed.await(1, TimeUnit.SECONDS), "another collector must be attempted while the first is blocked")
            val rejected = Executors.newSingleThreadExecutor()
            try {
                val before = System.nanoTime()
                assertFailsWith<IllegalStateException> { owner.ownProfiling(rejected) }
                assertTrue(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(80))
            } finally {
                rejected.shutdownNow()
            }
            release.countDown()
            var report = owner.stopAdmissionAndDrain()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (report is ExecutorDrain.Incomplete && ExecutorDrain.failures(report).none { it === sentinel } && System.nanoTime() < deadline) report = owner.stopAdmissionAndDrain()
            assertTrue(ExecutorDrain.failures(assertIs<ExecutorDrain.Incomplete>(report)).any { it === sentinel })
            assertEquals(ExecutorDrain.Complete, owner.stopAdmissionAndDrain())
            assertFailsWith<java.io.IOException> { stream.read() }
        } finally {
            release.countDown()
            first.shutdownNow()
            other.shutdownNow()
            owner.stopAdmissionAndDrain()
            stream.close()
        }
    }

    @Test
    fun `owned collector interruption is delivered with original failure identity`() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val raw = Executors.newSingleThreadExecutor()
        val sentinel = IllegalStateException("collector interrupted cleanup")
        val attempts = AtomicInteger()
        owner.ownProfiling(raw) { acquired ->
            acquired.attach(raw) {
                if (attempts.getAndIncrement() == 0) {
                    Thread.currentThread().interrupt()
                    throw sentinel
                }
            }
        }
        try {
            val delivered = assertFailsWith<ServiceDrainException> { owner.close() }
            assertSame(sentinel, delivered.cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            assertEquals(ExecutorDrain.Complete, owner.stopAdmissionAndDrain())
        }
    }

    @Test
    fun `publication preserves interrupted collector failure while writing diagnostic evidence`() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val raw = Executors.newSingleThreadExecutor()
        val sentinel = IllegalStateException("publication collector interruption")
        val attempts = AtomicInteger()
        owner.ownProfiling(raw) { acquired ->
            acquired.attach(raw) {
                if (attempts.getAndIncrement() == 0) {
                    Thread.currentThread().interrupt()
                    throw sentinel
                }
            }
        }
        val manager = MazewallProfileManager.forTesting(owner) {}
        val target = directory.resolve("interrupted-publication.json")
        try {
            val report = assertIs<ExecutorDrain.Incomplete>(manager.publish(target))
            assertTrue(ExecutorDrain.failures(report).any { it === sentinel })
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertTrue(Files.exists(target))
            assertFailsWith<IncompleteProfileEvidenceException> { ProfileArtifactPublisher.resolveEvidencePath(target) }
        } finally {
            Thread.interrupted()
            settleProfileOwners()
        }
    }

    @Test
    fun `publication IO owner budgets concurrent callers without holding manager state`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofMillis(100))
        val manager = MazewallProfileManager.forTesting(owner) {
            entered.countDown()
            awaitRelease(release)
        }
        val target = directory.resolve("pending.json")
        try {
            assertIs<ExecutorDrain.Incomplete.InProgress>(manager.publish(target))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val start = System.nanoTime()
            assertIs<ExecutorDrain.Incomplete.InProgress>(manager.publish(target))
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(250))
            assertFalse(Files.exists(target), "pending IO must not be described as published evidence")
            val before = System.nanoTime()
            assertFailsWith<IllegalStateException> { manager.publish(directory.resolve("cannot-switch.json")) }
            assertTrue(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(80), "IO must not hold the manager's state monitor")
            release.countDown()
            settleProfileOwners()
            assertTrue(Files.exists(target))
            assertFailsWith<IncompleteProfileEvidenceException> { ProfileArtifactPublisher.resolveEvidencePath(target) }
        } finally {
            release.countDown()
            settleProfileOwners()
        }
    }

    private fun awaitRelease(latch: CountDownLatch) {
        while (latch.count > 0) {
            try {
                latch.await()
            } catch (_: InterruptedException) {
                // The fixture retains real work until the release handshake.
            }
        }
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

/** Bounded real-owner retry, used only after fixtures release their held resources. */
internal fun settleProfileOwners() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (System.nanoTime() < deadline) {
        if (MazewallProfileManager.retryRetained().isEmpty()) return
        java.util.concurrent.locks.LockSupport
            .parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
    }
    kotlin.test.fail("retained profile owners did not finish actual cleanup")
}
