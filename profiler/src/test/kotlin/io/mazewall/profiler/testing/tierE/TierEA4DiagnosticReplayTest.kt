package io.mazewall.profiler.testing.tierE

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.profiler.tierE.engine.BoundaryStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierEA4DiagnosticReplayTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun missingOrMalformedEvidenceIsAnExplicitRefusalAndNeverOverwritesAReport() {
        for (malformed in listOf(false, true)) {
            val root = Files.createDirectory(directory.resolve("evidence-$malformed"))
            if (malformed) {
                for (name in TierEA4DiagnosticReplay.inputLimits.keys) Files.writeString(root.resolve(name), "bad")
            }
            val output = directory.resolve("report-$malformed.json")
            assertEquals(2, TierEA4DiagnosticReplay.run(root, 1000, 77, output))
            val original = Files.readString(output)
            val report = Json.parseToJsonElement(original).jsonObject
            assertEquals("INVALID_EVIDENCE", report.getValue("outcome").jsonPrimitive.content)
            assertEquals("OFFLINE_REPLAY", report.getValue("execution").jsonPrimitive.content)
            assertEquals("false", report.getValue("qualification").jsonPrimitive.content)
            assertFailsWith<FileAlreadyExistsException> { TierEA4DiagnosticReplay.run(root, 1000, 77, output) }
            assertEquals(original, Files.readString(output))
        }
    }

    @Test
    fun reportDistinguishesInconclusiveIncompleteAndLocalizedWithoutQualification() {
        val cases = listOf(
            DiagnosticOutcome.InconclusiveBaseline to "INCONCLUSIVE_BASELINE",
            DiagnosticOutcome.Incomplete(listOf("FD generation unproved")) to "INCOMPLETE",
            DiagnosticOutcome.LocalizedBoundary(7, BoundaryStage.MAIN_NO_SESSION, listOf(2)) to "LOCALIZED_BOUNDARY",
        )
        for ((outcome, expected) in cases) {
            val join = DiagnosticJoin(emptyList(), listOf("FD generation unproved"), outcome, setOf(7), setOf(8))
            val report = TierEA4DiagnosticReplay.summarize(join)
            assertEquals(expected, report.getValue("outcome").jsonPrimitive.content)
            assertEquals("false", report.getValue("qualification").jsonPrimitive.content)
            assertEquals(
                "7",
                report
                .getValue("missing_ids")
                .jsonArray
                .single()
                .jsonPrimitive.content,
            )
            if (outcome is DiagnosticOutcome.LocalizedBoundary) assertEquals("7", report.getValue("invocation_id").jsonPrimitive.content)
        }
    }

    @Test
    fun oversizedDiagnosticReasonsAreSampledBeforeWriting() {
        val reasons = List(100) { "x".repeat(10000) }
        val join = DiagnosticJoin(emptyList(), reasons, DiagnosticOutcome.Incomplete(reasons), emptySet(), emptySet())
        val report = TierEA4DiagnosticReplay.summarize(join)
        assertEquals("100", report.getValue("reason_count").jsonPrimitive.content)
        assertEquals("true", report.getValue("truncated").jsonPrimitive.content)
        assertTrue(report.toString().toByteArray().size < 64 * 1024)
        assertTrue(report.getValue("reasons").jsonArray.size <= 20)
    }

    @Test
    fun inputLimitsAndSymlinksCannotBypassReadOnlyReplayBounds() {
        val file = Files.writeString(directory.resolve("text"), "12345")
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticReplay.readBounded(file, 4) }
        val link = Files.createSymbolicLink(directory.resolve("link"), file)
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticReplay.readBounded(link, 8) }
        assertEquals("12345", Files.readString(file))
        assertFalse(Files.exists(directory.resolve("report")))
    }
}
