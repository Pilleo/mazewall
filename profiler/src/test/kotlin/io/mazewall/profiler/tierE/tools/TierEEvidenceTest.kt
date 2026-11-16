package io.mazewall.profiler.tierE.tools

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierEEvidenceTest {
    @TempDir
    lateinit var root: Path

    @BeforeEach
    fun input() {
        Files.writeString(root.resolve("input"), "before ARM\n")
    }

    private fun manifest(domain: TierEEvidence.Domain = TierEEvidence.Domain.SNAPSHOT): JsonObject = TierEEvidence.capture(root, listOf("input"), domain)

    private fun withEntry(
        manifest: JsonObject,
        entry: JsonObject,
    ): JsonObject = JsonObject(manifest + ("entries" to JsonArray(listOf(entry))))

    private fun entry(manifest: JsonObject): JsonObject =
        manifest
        .getValue("entries")
        .jsonArray
        .single()
        .jsonObject

    @Test
    fun snapshotRelocationUsesExplicitRootAndIgnoresHistoricalCanonicalPath() {
        val manifest = manifest().let { withEntry(it, JsonObject(entry(it) + ("canonical" to JsonPrimitive("/historical/missing/input")))) }
        val moved = Files.createDirectory(root.resolve("relocated"))
        Files.writeString(moved.resolve("input"), "before ARM\n")
        assertEquals(TierEEvidence.Outcome.VERIFIED, TierEEvidence.verify(manifest, moved).outcome)
        Files.writeString(moved.resolve("input"), "different!\n")
        assertEquals(TierEEvidence.Outcome.MISMATCH, TierEEvidence.verify(manifest, moved).outcome)
        assertFailsWith<IllegalArgumentException> { TierEEvidence.capture(Path.of("."), listOf("input"), TierEEvidence.Domain.SNAPSHOT) }
    }

    @Test
    fun liveRejectsSameBytesAtAnotherPathOrInAReplacementInode() {
        val manifest = manifest(TierEEvidence.Domain.LIVE)
        val moved = Files.createDirectory(root.resolve("relocated"))
        Files.writeString(moved.resolve("input"), "before ARM\n")
        assertEquals(TierEEvidence.Outcome.MISMATCH, TierEEvidence.verify(manifest, moved).outcome)
        Files.move(root.resolve("input"), root.resolve("old"))
        Files.writeString(root.resolve("input"), "before ARM\n")
        assertEquals(TierEEvidence.Outcome.MISMATCH, TierEEvidence.verify(manifest, root).outcome)
    }

    @Test
    fun prefixChecksCapturedBytesRatherThanFinalLogLength() {
        val prefix = manifest(TierEEvidence.Domain.PREFIX)
        val final = manifest()
        Files.writeString(root.resolve("input"), "before ARM\nafter ARM\n")
        assertEquals(TierEEvidence.Outcome.VERIFIED, TierEEvidence.verify(prefix, root).outcome)
        assertEquals(TierEEvidence.Outcome.MISMATCH, TierEEvidence.verify(final, root).outcome)
        for (changed in listOf("short", "BEFORE ARM\nafter ARM\n")) {
            Files.writeString(root.resolve("input"), changed)
            assertEquals(TierEEvidence.Outcome.MISMATCH, TierEEvidence.verify(prefix, root).outcome)
        }
    }

    @Test
    fun requiredFieldsAndNumericTypesAreValidatedBeforeComparison() {
        for (domain in TierEEvidence.Domain.entries) {
            val valid = manifest(domain)
            for (key in entry(valid).keys) {
                val missing = withEntry(valid, JsonObject(entry(valid) - key))
                assertEquals(TierEEvidence.Outcome.INVALID_MANIFEST, TierEEvidence.verify(missing, root).outcome, "$domain missing $key")
            }
        }
        for ((key, value) in listOf(
            "schema_version" to JsonPrimitive(2),
                "schema_version" to JsonPrimitive(true),
            "domain" to JsonPrimitive("guess"),
                "entries" to JsonArray(emptyList()),
        )) {
            assertEquals(TierEEvidence.Outcome.INVALID_MANIFEST, TierEEvidence.verify(JsonObject(manifest() + (key to value)), root).outcome)
        }
        for ((key, value) in listOf(
            "bytes" to JsonPrimitive(true),
                "bytes" to JsonPrimitive(-1),
                "bytes" to JsonPrimitive("11"),
            "sha256" to JsonPrimitive("0"),
                "path" to JsonPrimitive("../input"),
        )) {
            val valid = manifest()
            assertEquals(TierEEvidence.Outcome.INVALID_MANIFEST, TierEEvidence.verify(withEntry(valid, JsonObject(entry(valid) + (key to value))), root).outcome)
        }
    }

    @Test
    fun informationalMetadataDoesNotParticipateInWholeObjectComparison() {
        for (domain in TierEEvidence.Domain.entries) {
            val valid = manifest(domain)
            val annotated = withEntry(JsonObject(valid + ("note" to JsonPrimitive("new field"))), JsonObject(entry(valid) + ("operator_note" to JsonPrimitive(false))))
            assertEquals(TierEEvidence.Outcome.VERIFIED, TierEEvidence.verify(annotated, root).outcome)
        }
    }

    @Test
    fun duplicateMembersAndSymlinkTraversalAreRejected() {
        val valid = manifest()
        val duplicate = JsonObject(valid + ("entries" to JsonArray(listOf(entry(valid), entry(valid)))))
        assertEquals(TierEEvidence.Outcome.INVALID_MANIFEST, TierEEvidence.verify(duplicate, root).outcome)
        Files.createSymbolicLink(root.resolve("link"), root.resolve("input"))
        Files.createSymbolicLink(root.resolve("directory-link"), root)
        for (name in listOf("link", "directory-link/input", "../input", "/input", "./input", "input/../input", "input//child")) {
            assertFails(name) { TierEEvidence.capture(root, listOf(name), TierEEvidence.Domain.SNAPSHOT) }
        }
    }

    @Test
    fun duplicateDecodedJsonKeysAreRejectedIncludingIgnoredMetadata() {
        for (json in listOf(
            """{"schema_version":1,"schema_version":1}""",
            """{"path":"input","p\u0061th":"other"}""",
            """{"note":{"key":1,"key":2}}""",
        )) {
            assertFailsWith<IllegalArgumentException> { TierEEvidence.parseManifest(json) }
        }
    }

    @Test
    fun independentlySpecifiedVersionOneManifestIsAccepted() {
        Files.writeString(root.resolve("input"), "")
        val manifest = TierEEvidence.parseManifest(
            """{"schema_version":1,"domain":"snapshot","entries":[{"path":"input","bytes":0,"sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}]}""",
        )
        assertEquals(TierEEvidence.Outcome.VERIFIED, TierEEvidence.verify(manifest, root).outcome)
    }

    @Test
    fun nativeUnsignedOwnershipMatchesVersionOneLiveManifestNumbers() {
        val file = root.resolve("input")
        val native = Files.readAttributes(file, "unix:dev,ino,mode,uid,gid,size,lastModifiedTime,ctime")
        for ((signed, expected) in listOf(1000 to 1000L, Int.MIN_VALUE to 2147483648L, -2 to 4294967294L)) {
            val metadata = TierEEvidence.metadata(file, native + mapOf("uid" to signed, "gid" to signed))
            assertEquals(JsonPrimitive(expected), metadata.getValue("uid"))
            assertEquals(JsonPrimitive(expected), metadata.getValue("gid"))
            assertEquals(JsonPrimitive(11), metadata.getValue("bytes"))
        }
    }

    @Test
    fun reportsBoundFailureSamplesAndNeverReplaceExistingEvidence() {
        val valid = manifest()
        val missing = JsonObject(valid + ("entries" to JsonArray((0 until 100).map { JsonObject(entry(valid) + ("path" to JsonPrimitive("missing-$it"))) })))
        val result = TierEEvidence.verify(missing, root)
        assertEquals(TierEEvidence.Outcome.MISMATCH, result.outcome)
        assertEquals(
            "100",
            result.report
            .getValue("failure_count")
            .jsonPrimitive.content,
        )
        assertTrue(
            result.report
            .getValue("failures")
            .jsonArray.size <= 20,
        )
        assertEquals(
            "true",
            result.report
            .getValue("truncated")
            .jsonPrimitive.content,
        )
        val output = root.resolve("report.json")
        TierEEvidence.writeNew(output, result.report, TierEEvidence.REPORT_LIMIT)
        val original = Files.readString(output)
        assertFailsWith<FileAlreadyExistsException> { TierEEvidence.writeNew(output, JsonObject(emptyMap()), TierEEvidence.REPORT_LIMIT) }
        assertEquals(original, Files.readString(output))
        val oversized = root.resolve("oversized.json")
        assertFailsWith<IllegalArgumentException> { TierEEvidence.writeNew(oversized, buildJsonObject { put("data", "x".repeat(1000)) }, 100) }
        assertFalse(Files.exists(oversized))
    }

    @Test
    fun cliCaptureAndVerifyReturnStructuredMismatchWithoutPython() {
        val manifest = root.resolve("manifest.json")
        assertEquals(0, TierEEvidence.run(arrayOf("capture", "snapshot", root.toString(), manifest.toString(), "input")))
        Files.writeString(root.resolve("input"), "changed")
        val report = root.resolve("report.json")
        assertEquals(1, TierEEvidence.run(arrayOf("verify", root.toString(), manifest.toString(), report.toString())))
        val result = TierEEvidence.parseManifest(Files.readString(report))
        assertEquals("snapshot", result.getValue("domain").jsonPrimitive.content)
        assertEquals("MISMATCH", result.getValue("outcome").jsonPrimitive.content)
        assertEquals("false", result.getValue("qualification").jsonPrimitive.content)
        assertTrue(Files.size(report) < TierEEvidence.REPORT_LIMIT)
        assertEquals(2, TierEEvidence.run(arrayOf("verify", root.toString(), manifest.toString(), report.toString())))
    }
}
