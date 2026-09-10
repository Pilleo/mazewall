package demo.vulnapp.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MazewallProfileManagerTest {
    @TempDir
    lateinit var directory: Path

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
