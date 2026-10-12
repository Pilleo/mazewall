package demo.vulnapp.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.mazewall.profiler.api.BillOfBehavior
import io.mazewall.profiler.api.ProfilingResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MazewallProfileManagerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `diagnostic publication stays incomplete through held raw work and settles after retry`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = Executors.newSingleThreadExecutor()
        val owner = ServiceExecutorOwner(Duration.ofMillis(20))
        owner.ownProfiling(delegate).execute {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Deliberately retain live work through a cancellation request.
                }
            }
        }
        var stopped = false
        val manager = MazewallProfileManager.forTesting(owner, listOf(ProfilingResult(Unit, BillOfBehavior(), emptyMap(), collectorCoverage()))) { stopped = true }
        val target = directory.resolve("retained.json")
        try {
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertIs<ExecutorDrain.Incomplete>(manager.publish(target))
            assertFalse(stopped, "global cleanup must not run before held raw work retires")
            awaitPointer(target)
            assertFailsWith<IncompleteProfileEvidenceException> { ProfileArtifactPublisher.resolveEvidencePath(target) }
            release.countDown()
            assertTrue(delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS))
            settleProfileOwners()
            assertTrue(stopped)
            assertEquals(ProfileArtifactPublisher.readCurrent(target).evidence, ProfileArtifactPublisher.resolveEvidencePath(target))
        } finally {
            release.countDown()
            delegate.shutdownNow()
            delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
            settleProfileOwners()
        }
    }

    @Test
    fun `orderly executor shutdown without collector coverage cannot authorize enforcement`() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val manager = MazewallProfileManager.forTesting(owner) {}
        val target = directory.resolve("sbob.json")
        manager.publish(target)
        assertFailsWith<IncompleteProfileEvidenceException> { ProfileArtifactPublisher.resolveEvidencePath(target) }
    }

    @Test
    fun `orderly shutdown retains lossy collector beside a complete collector and rejects enforcement`() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val profiles = listOf(collectorCoverage(), collectorCoverage(droppedEvents = 3)).map { coverage ->
            ProfilingResult(Unit, BillOfBehavior(), emptyMap(), coverage)
        }
        val manager = MazewallProfileManager.forTesting(owner, profiles) {}
        val target = directory.resolve("sbob.json")
        assertIs<ExecutorDrain.Incomplete.CollectorCoverage>(manager.publish(target))
        val integrity = ObjectMapper().readTree(Files.readString(ProfileArtifactPublisher.readCurrent(target).integrity))
        assertFalse(integrity.path("complete").booleanValue())
        assertTrue(integrity.path("reason").asText().contains("dropped 3 events"))
        assertEquals(2, integrity.path("collectors").size())
        assertEquals(3, integrity.path("collectors")[1].path("droppedEvents").intValue())
        assertFailsWith<IncompleteProfileEvidenceException> { ProfileArtifactPublisher.resolveEvidencePath(target) }
    }

    @Test
    fun `orderly shutdown with complete collectors permits enforcement`() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val manager = MazewallProfileManager.forTesting(owner, listOf(ProfilingResult(Unit, BillOfBehavior(), emptyMap(), collectorCoverage()))) {}
        val target = directory.resolve("sbob.json")
        assertIs<ExecutorDrain.Complete>(manager.publish(target))
        assertEquals(ProfileArtifactPublisher.readCurrent(target).evidence, ProfileArtifactPublisher.resolveEvidencePath(target))
    }

    @Test
    fun `timeout publishes incomplete integrity without tearing down live collectors`() {
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofMillis(10))
        owner.ownProfiling(Executors.newSingleThreadExecutor()).execute { awaitRelease(release) }
        var shutdown = false
        val manager = MazewallProfileManager.forTesting(owner) { shutdown = true }

        try {
            val outcome = manager.publish(directory.resolve("sbob.json"))

            assertIs<ExecutorDrain.Incomplete>(outcome)
            awaitPointer(directory.resolve("sbob.json"))
            val integrity = Files.readString(ProfileArtifactPublisher.readCurrent(directory.resolve("sbob.json")).integrity)
            assertTrue(integrity.contains("timeout"))
            assertFalse(shutdown)
        } finally {
            release.countDown()
            owner.stopAdmissionAndDrain()
            settleProfileOwners()
        }
    }

    @Test
    fun `interrupted drain retains collectors and preserves interruption`() {
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        owner.ownProfiling(Executors.newSingleThreadExecutor()).execute { awaitRelease(release) }
        var shutdown = false
        val manager = MazewallProfileManager.forTesting(owner) { shutdown = true }
        Thread.currentThread().interrupt()

        try {
            val outcome = manager.publish(directory.resolve("sbob.json"))

            assertIs<ExecutorDrain.Incomplete.InProgress>(outcome)
            assertTrue(Thread.currentThread().isInterrupted)
            // Publication cleanup runs on its retained platform owner, not the interrupted caller.
        } finally {
            Thread.interrupted()
            release.countDown()
            owner.stopAdmissionAndDrain()
            settleProfileOwners()
        }
    }

    @Test
    fun `physical drain followed by collector cleanup failure cannot authorize enforcement`() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        var attempts = 0
        val manager = MazewallProfileManager.forTesting(owner, listOf(ProfilingResult(Unit, BillOfBehavior(), emptyMap(), collectorCoverage()))) {
            if (attempts++ == 0) error("cleanup boom")
        }
        val target = directory.resolve("failed-cleanup.json")
        assertIs<ExecutorDrain.Incomplete.ProfilerCleanup>(manager.publish(target))
        assertTrue(Files.readString(ProfileArtifactPublisher.readCurrent(target).integrity).contains("cleanup boom"))
        assertFailsWith<IncompleteProfileEvidenceException> { ProfileArtifactPublisher.resolveEvidencePath(target) }
        assertIs<ExecutorDrain.Complete>(manager.publish(target))
    }

    private fun awaitRelease(release: CountDownLatch) {
        try {
            release.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun awaitPointer(path: Path) {
        val deadline = System.nanoTime() +
            java.util.concurrent.TimeUnit.SECONDS
            .toNanos(2)
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport
            .parkNanos(1_000_000)
        }
        assertTrue(Files.exists(path), "retained IO attempt must eventually publish its diagnostic pointer")
    }
}
