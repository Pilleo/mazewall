package demo.vulnapp.config

import io.mazewall.profiler.BillOfBehavior
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileArtifactPublisherTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `publishes one coherent immutable generation behind an atomic pointer`() {
        val target = directory.resolve("sbob.json")
        val published = ProfileArtifactPublisher.publish(target, BillOfBehavior(), ExecutorDrain.Complete)
        val current = ProfileArtifactPublisher.readCurrent(target)

        assertEquals(published, current)
        assertEquals(BillOfBehavior(), BillOfBehavior.fromFile(current.evidence))
        assertTrue(Files.readString(current.integrity).contains("\"complete\":true"))
        assertTrue(Files.exists(current.stackDictionary))
        Files.list(directory).use { children ->
            assertFalse(children.anyMatch { it.fileName.toString().matches(Regex("\\.sbob\\.json\\..+\\.tmp")) })
        }
    }

    @Test
    fun `incomplete drain is explicit in companion evidence`() {
        val target = directory.resolve("sbob.json")
        ProfileArtifactPublisher.publish(target, BillOfBehavior(), ExecutorDrain.Incomplete.Timeout(Duration.ofMillis(7)))

        val integrity = Files.readString(ProfileArtifactPublisher.readCurrent(target).integrity)
        assertTrue(integrity.contains("\"complete\":false"))
        assertTrue(integrity.contains("timeout"))
    }

    @Test
    fun `failure between generation components leaves prior generation authoritative`() {
        val target = directory.resolve("sbob.json")
        val firstEvidence = BillOfBehavior(opens = setOf("/first"))
        val first = ProfileArtifactPublisher.publish(target, firstEvidence, ExecutorDrain.Complete)

        listOf(ProfilePublicationComponent.EVIDENCE, ProfilePublicationComponent.STACK_DICTIONARY).forEach { failurePoint ->
            assertFailsWith<IllegalStateException> {
                ProfileArtifactPublisher.publish(
                    target,
                    BillOfBehavior(opens = setOf("/second")),
                    ExecutorDrain.Complete,
                ) { component ->
                    if (component == failurePoint) error("injected publication failure")
                }
            }

            val current = ProfileArtifactPublisher.readCurrent(target)
            assertEquals(first, current)
            assertEquals(firstEvidence, BillOfBehavior.fromFile(current.evidence))
            assertTrue(Files.readString(current.integrity).contains("\"complete\":true"))
        }
    }
}
