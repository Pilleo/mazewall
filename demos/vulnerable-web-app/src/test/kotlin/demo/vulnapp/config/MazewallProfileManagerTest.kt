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
    fun `timeout and profiler cleanup failure are both retained in published integrity`() {
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofMillis(10))
        owner.own(Executors.newSingleThreadExecutor()).execute { awaitRelease(release) }
        val manager = MazewallProfileManager.forTesting(owner) { error("cleanup boom") }

        try {
            val outcome = manager.publish(directory.resolve("sbob.json"))

            assertIs<ExecutorDrain.Incomplete.Multiple>(outcome)
            val integrity = Files.readString(ProfileArtifactPublisher.readCurrent(directory.resolve("sbob.json")).integrity)
            assertTrue(integrity.contains("timeout"))
            assertTrue(integrity.contains("cleanup boom"))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `interruption and profiler cleanup failure remain visible and interruption is preserved`() {
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        owner.own(Executors.newSingleThreadExecutor()).execute { awaitRelease(release) }
        val manager = MazewallProfileManager.forTesting(owner) { error("cleanup interrupted") }
        Thread.currentThread().interrupt()

        try {
            val outcome = manager.publish(directory.resolve("sbob.json"))

            assertIs<ExecutorDrain.Incomplete.Multiple>(outcome)
            assertTrue(Thread.currentThread().isInterrupted)
            val integrity = Files.readString(ProfileArtifactPublisher.readCurrent(directory.resolve("sbob.json")).integrity)
            assertTrue(integrity.contains("interrupted"))
            assertTrue(integrity.contains("cleanup interrupted"))
        } finally {
            Thread.interrupted()
            release.countDown()
        }
    }

    private fun awaitRelease(release: CountDownLatch) {
        try {
            release.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
