package demo.vulnapp.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mazewall.profiler.BillOfBehavior
import io.mazewall.profiler.ProfileEvidence
import io.mazewall.profiler.ProfilingCoverage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal const val ALLOW_INCOMPLETE_ENFORCEMENT_PROPERTY = "mazewall.allow-incomplete-profile-enforcement"

internal enum class ProfilePublicationComponent {
    EVIDENCE,
    STACK_DICTIONARY,
    INTEGRITY,
}

internal data class PublishedProfileGeneration(
    val id: String,
    val evidence: Path,
    val stackDictionary: Path,
    val integrity: Path,
)

internal class IncompleteProfileEvidenceException(
    reason: String,
) : IllegalStateException(
        "Refusing to enforce incomplete profile evidence: $reason. " +
            "Set -D$ALLOW_INCOMPLETE_ENFORCEMENT_PROPERTY=true only after accepting the unsafe fallback.",
    )

/** Publishes immutable component sets, then atomically points readers at the complete generation. */
internal object ProfileArtifactPublisher {
    fun publish(
        target: Path,
        evidence: BillOfBehavior,
        drain: ExecutorDrain,
        coverages: List<ProfilingCoverage> = emptyList(),
        checkpoint: (ProfilePublicationComponent) -> Unit = {},
    ): PublishedProfileGeneration {
        target.parent?.let(Files::createDirectories)
        val generationId = UUID.randomUUID().toString()
        val root = generationRoot(target)
        Files.createDirectories(root)
        val generationDirectory = root.resolve(generationId)
        Files.createDirectory(generationDirectory)
        val published = PublishedProfileGeneration(
            generationId,
            generationDirectory.resolve(EVIDENCE_FILE),
            generationDirectory.resolve(STACK_DICTIONARY_FILE),
            generationDirectory.resolve(INTEGRITY_FILE),
        )
        val evidenceText = evidence.toJson()
        val stackText = evidence.toStackTracesJson()

        writeImmutable(published.evidence, evidenceText)
        checkpoint(ProfilePublicationComponent.EVIDENCE)
        writeImmutable(published.stackDictionary, stackText)
        checkpoint(ProfilePublicationComponent.STACK_DICTIONARY)
        writeImmutable(published.integrity, integrityJson(evidenceText, stackText, drain, coverages.toList()))
        checkpoint(ProfilePublicationComponent.INTEGRITY)

        publishPointer(target, generationId)
        return published
    }

    /** Resolves and validates the generation named by the one atomic publication pointer. */
    fun readCurrent(target: Path): PublishedProfileGeneration {
        val pointer = Files.readString(target, StandardCharsets.UTF_8).trim()
        val generationId = POINTER_PATTERN.matchEntire(pointer)?.groupValues?.get(1)
            ?: error("Invalid profile generation pointer: $target")
        val directory = generationRoot(target).resolve(generationId)
        val generation = PublishedProfileGeneration(
            generationId,
            directory.resolve(EVIDENCE_FILE),
            directory.resolve(STACK_DICTIONARY_FILE),
            directory.resolve(INTEGRITY_FILE),
        )
        val integrity = Files.readString(generation.integrity, StandardCharsets.UTF_8)
        val integrityRecord = parseIntegrity(integrity)
        validateDigest(integrityRecord, "evidenceSha256", generation.evidence)
        validateDigest(integrityRecord, "stackDictionarySha256", generation.stackDictionary)
        return generation
    }

    /** Supports legacy direct SBoB files while new publications use the coherent contract. */
    fun resolveEvidencePath(target: Path): Path {
        if (!Files.exists(target)) return target
        val content = Files.readString(target, StandardCharsets.UTF_8).trim()
        if (!POINTER_PATTERN.matches(content)) return target

        val generation = readCurrent(target)
        val integrity = parseIntegrity(Files.readString(generation.integrity, StandardCharsets.UTF_8))
        val collectors = integrity.path("collectors")
        val collectorEvidenceComplete = collectors.isArray &&
            !collectors.isEmpty &&
            collectors.all { collector ->
            collector.path("complete").isBoolean &&
                collector.path("complete").booleanValue() &&
                collector.path("drainComplete").isBoolean &&
                collector.path("drainComplete").booleanValue() &&
                collector.path("droppedEvents").isInt &&
                collector.path("droppedEvents").intValue() == 0
        }
        val complete = integrity.path("complete").isBoolean && integrity.path("complete").booleanValue() && collectorEvidenceComplete
        if (!complete && !allowIncompleteEnforcement()) {
            val reason = integrity.path("reason").asText("unspecified failure")
            throw IncompleteProfileEvidenceException(
                if (collectorEvidenceComplete) reason else "$reason; collector coverage is absent or incomplete; regenerate the profile",
            )
        }
        return generation.evidence
    }

    private fun publishPointer(
        target: Path,
        generationId: String,
    ) {
        val pointer = """{"format":"$FORMAT","generation":"$generationId"}"""
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, pointer, StandardCharsets.UTF_8)
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeImmutable(
        target: Path,
        content: String,
    ) {
        Files.writeString(target, content, StandardCharsets.UTF_8, CREATE_NEW, WRITE)
    }

    private fun integrityJson(
        evidence: String,
        stackDictionary: String,
        drain: ExecutorDrain,
        coverages: List<ProfilingCoverage>,
    ): String {
        val drainFailure = when (drain) {
            ExecutorDrain.Complete -> null
            is ExecutorDrain.Incomplete -> drain.reason
        }
        val failures = listOfNotNull(drainFailure) + incompleteCollectorReasons(coverages)
        val complete = failures.isEmpty()
        val reason = if (complete) "complete" else failures.joinToString("; ")
        val collectors = JSON.createArrayNode()
        coverages.forEach { coverage ->
            collectors.add(
                JSON
                    .createObjectNode()
                    .put("strategy", coverage.strategy.name)
                    .put("strategyReason", coverage.strategyReason)
                    .put("processWide", coverage.processWide)
                    .put("ioUring", coverage.ioUring.name)
                    .put("pathResolution", coverage.pathResolution.name)
                    .put("stacks", coverage.stacks.name)
                    .put("droppedEvents", coverage.droppedEvents)
                    .put("drainComplete", coverage.drainComplete)
                    .put("complete", coverage.complete)
                    .apply {
                        set<JsonNode>("warnings", JSON.valueToTree(coverage.warnings))
                        set<JsonNode>(
                            "environment",
                            JSON.valueToTree(
                                mapOf(
                            "kernelRelease" to coverage.environment.kernelRelease,
                            "ebpfLoad" to coverage.environment.ebpfLoad.toString(),
                            "landlockAbi" to coverage.environment.landlockAbi,
                            "ioUringDisabled" to coverage.environment.ioUringDisabled,
                        ),
                            ),
                        )
                        set<JsonNode>(
                            "pathFailures",
                            JSON.valueToTree(
                                coverage.pathFailures.mapTo(ArrayList<Map<String, Any?>>()) { failure ->
                            mapOf("operandIndex" to failure.operandIndex, "reason" to failure.reason.toString(), "detail" to failure.detail, "cause" to failure.cause)
                        },
                            ),
                        )
                    },
            )
        }
        return JSON.writeValueAsString(
            JSON
                .createObjectNode()
                .put("complete", complete)
                .put("reason", reason)
                .put("evidenceSha256", digest(evidence))
                .put("stackDictionarySha256", digest(stackDictionary))
                .set<JsonNode>("collectors", collectors),
        )
    }

    private fun parseIntegrity(integrity: String): JsonNode = JSON.readTree(integrity) ?: error("Profile integrity must contain a JSON object")

    private fun validateDigest(
        integrity: JsonNode,
        field: String,
        component: Path,
    ) {
        val expected = integrity
            .path(field)
            .textValue()
            ?.takeIf { it.matches(SHA_256_PATTERN) }
            ?: error("Profile integrity is missing $field")
        val actual = digest(Files.readString(component, StandardCharsets.UTF_8))
        check(actual == expected) { "Profile generation component failed $field validation" }
    }

    private fun allowIncompleteEnforcement(): Boolean {
        val configured = System.getProperty(ALLOW_INCOMPLETE_ENFORCEMENT_PROPERTY) ?: return false
        return configured.toBooleanStrictOrNull()
            ?: error("$ALLOW_INCOMPLETE_ENFORCEMENT_PROPERTY must be true or false")
    }

    private fun digest(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun generationRoot(target: Path): Path = target.resolveSibling(".${target.fileName}.generations")

    private const val FORMAT = "mazewall-profile-generation-v1"
    private const val EVIDENCE_FILE = "sbob.json"
    private const val STACK_DICTIONARY_FILE = "stacktraces.json"
    private const val INTEGRITY_FILE = "integrity.json"
    private val POINTER_PATTERN = Regex("""\{"format":"$FORMAT","generation":"([0-9a-f-]{36})"\}""")
    private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    private val JSON = ObjectMapper()
}

/** Preserve every collector's failure; an orderly executor alone supplies no observation evidence. */
internal fun incompleteCollectorReasons(coverages: List<ProfilingCoverage>): List<String> {
    if (coverages.isEmpty()) return listOf("collector coverage is absent")
    val failures = ArrayList<String>()
    coverages.forEachIndexed { index, coverage ->
        val reasons = LinkedHashSet<String>()
        when (val evidence = coverage.evidence()) {
            ProfileEvidence.Complete -> Unit
            is ProfileEvidence.Incomplete -> reasons.addAll(evidence.reasons)
        }
        if (!coverage.drainComplete) reasons.add("collector drain did not complete")
        if (coverage.droppedEvents != 0) reasons.add("dropped ${coverage.droppedEvents} events")
        reasons.forEach { failures.add("collector[$index]: $it") }
    }
    return failures
}
