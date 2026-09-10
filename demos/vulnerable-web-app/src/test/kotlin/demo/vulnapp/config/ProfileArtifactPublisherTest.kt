package demo.vulnapp.config

import io.mazewall.profiler.BillOfBehavior
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileArtifactPublisherTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `publishes sbob and integrity atomically after drain`() {
        val target = directory.resolve("sbob.json")
        ProfileArtifactPublisher.publish(target, BillOfBehavior(), ExecutorDrain.Complete)

        assertEquals(BillOfBehavior(), BillOfBehavior.fromFile(target))
        assertTrue(Files.readString(ProfileArtifactPublisher.integrityPath(target)).contains("\"complete\":true"))
        assertFalse(Files.exists(directory.resolve(".sbob.json.tmp")))
    }

    @Test
    fun `incomplete drain is explicit in companion evidence`() {
        val target = directory.resolve("sbob.json")
        ProfileArtifactPublisher.publish(target, BillOfBehavior(), ExecutorDrain.Incomplete.Timeout(Duration.ofMillis(7)))

        val integrity = Files.readString(ProfileArtifactPublisher.integrityPath(target))
        assertTrue(integrity.contains("\"complete\":false"))
        assertTrue(integrity.contains("timeout"))
    }
}
