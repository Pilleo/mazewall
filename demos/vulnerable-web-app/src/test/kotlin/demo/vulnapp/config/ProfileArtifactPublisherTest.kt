package demo.vulnapp.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.mazewall.profiler.api.BillOfBehavior
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
    fun `old generation with drain-only complete marker cannot bypass collector validation`() {
        val target = directory.resolve("sbob.json")
        val generation = ProfileArtifactPublisher.publish(target, BillOfBehavior(), ExecutorDrain.Complete, listOf(collectorCoverage()))
        val json = ObjectMapper().readTree(Files.readString(generation.integrity)) as com.fasterxml.jackson.databind.node.ObjectNode
        json.remove("collectors")
        Files.writeString(generation.integrity, json.toString())
        assertFailsWith<IncompleteProfileEvidenceException> { ProfileArtifactPublisher.resolveEvidencePath(target) }
    }

    @Test
    fun `publishes one coherent immutable generation behind an atomic pointer`() {
        val target = directory.resolve("sbob.json")
        val published = ProfileArtifactPublisher.publish(target, BillOfBehavior(), ExecutorDrain.Complete, listOf(collectorCoverage()))
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
    fun `incomplete profile is diagnostic only unless unsafe enforcement is explicit`() {
        val target = directory.resolve("sbob.json")
        ProfileArtifactPublisher.publish(target, BillOfBehavior(), ExecutorDrain.Incomplete.Timeout(Duration.ofMillis(7)))

        val failure = assertFailsWith<IllegalStateException> {
            ProfileArtifactPublisher.resolveEvidencePath(target)
        }

        assertTrue(failure.message.orEmpty().contains("timeout after 7ms"))
        System.setProperty("mazewall.allow-incomplete-profile-enforcement", "true")
        try {
            assertEquals(
                ProfileArtifactPublisher.readCurrent(target).evidence,
                ProfileArtifactPublisher.resolveEvidencePath(target),
            )
        } finally {
            System.clearProperty("mazewall.allow-incomplete-profile-enforcement")
        }
    }

    @Test
    fun `integrity diagnostics are valid JSON for every control character`() {
        val reason = "quote\" slash\\ newline\n tab\t return\r backspace\b formfeed\u000C nul\u0000"
        val target = directory.resolve("sbob.json")
        ProfileArtifactPublisher.publish(
            target,
            BillOfBehavior(),
            ExecutorDrain.Incomplete.ProfilerCleanup(reason),
            listOf(collectorCoverage()),
        )

        val integrity = Files.readString(ProfileArtifactPublisher.readCurrent(target).integrity)
        val parsed = ObjectMapper().readTree(integrity)

        assertFalse(parsed.path("complete").booleanValue())
        assertEquals("profiler cleanup failed: $reason", parsed.path("reason").textValue())
    }

    @Test
    fun `failure between generation components leaves prior generation authoritative`() {
        val target = directory.resolve("sbob.json")
        val firstEvidence = BillOfBehavior(opens = setOf("/first"))
        val first = ProfileArtifactPublisher.publish(target, firstEvidence, ExecutorDrain.Complete, listOf(collectorCoverage()))

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
