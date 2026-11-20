package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotAccumulatorCounts
import io.mazewall.profiler.internal.hotspot.HotSpotCollectionAccounting
import io.mazewall.profiler.internal.hotspot.HotSpotCollectionOutcome
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceDocument
import io.mazewall.profiler.internal.hotspot.HotSpotProtocol
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotSourceAdmission
import io.mazewall.profiler.internal.hotspot.HotSpotSourceAdmissionContext
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import io.mazewall.profiler.internal.hotspot.HotSpotWireReceipt
import io.mazewall.profiler.internal.hotspot.HotSpotWireRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HotSpotStreamingEvidenceTest {
    @Test
    fun `partial snapshots distinguish pending records from retention loss and remain immutable`() {
        val rows = rows()
        val accumulator = HotSpotEvidenceAssembler().begin()
        accumulator.accept(rows[0])
        val pending = accumulator.snapshot()
        assertFalse(pending.finalized)
        assertFalse(pending.terminalAccountingComplete)
        assertEquals(1uL, pending.pendingOccurrences)
        assertEquals(0uL, pending.discardedOccurrences)
        accumulator.accept(rows[1])
        val paired = accumulator.snapshot()
        assertEquals(1, paired.records.size)
        assertEquals(0uL, paired.pendingOccurrences)
        assertEquals(0, pending.records.size)
        accumulator.accept(rows[2])
        assertTrue(accumulator.finish().finalized)
        assertFalse(paired.finalized)
    }

    @Test
    fun `retention cap continues terminal accounting while final evidence reports loss of retained records`() {
        val accumulator = HotSpotEvidenceAssembler(HotSpotReplayLimits(maxRetainedOccurrences = 0)).begin()
        rows().forEach(accumulator::accept)
        val final = accumulator.finish()
        assertEquals(0, final.records.size)
        assertEquals(1uL, final.discardedOccurrences)
        assertTrue(final.terminalAccountingComplete)
        assertFalse(final.retentionComplete)
    }

    @Test
    fun `EOF cannot finalize pending or summaryless evidence`() {
        val accumulator = HotSpotEvidenceAssembler().begin()
        accumulator.accept(rows()[0])
        assertFailsWith<IllegalArgumentException> { accumulator.finish() }
    }

    @Test
    fun `final summary uses the assembler summary object rather than a wire envelope`() {
        val accumulator = HotSpotEvidenceAssembler().begin()
        rows().forEach(accumulator::accept)
        assertFalse(HotSpotProtocol(123, 456uL).summaryEvent(accumulator.finish()).healthy)
    }

    @Test
    fun `valid prefix receipt computes exact prefix records, bytes and sha256`() {
        val rawBytes = checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { it.readBytes() }
        val lines = rawBytes.toString(Charsets.UTF_8).split("\n").filter { it.isNotBlank() }
        val prefixBytes = (lines[0] + "\n" + lines[1] + "\n").toByteArray(Charsets.UTF_8)
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(prefixBytes).joinToString("") { "%02x".format(it) }
        val receipt = HotSpotWireReader(HotSpotReplayLimits()).read(ByteArrayInputStream(rawBytes)) { }
        assertEquals(2uL, receipt.records)
        assertEquals(2139uL, receipt.bytes)
        assertEquals(prefixBytes.size.toULong(), receipt.bytes)
        assertEquals("ae6cc748e937db293cbce043a6bed51bf2834702bc519faaf72c94afdc93046c", receipt.sha256)
        assertEquals(expectedSha, receipt.sha256)
        assertTrue(receipt.validEof)

        val snapshot = HotSpotEvidenceAssembler().read(ByteArrayInputStream(rawBytes))
        assertFalse(snapshot.collectionLossComplete)
        val encoded = HotSpotEvidenceDocument.encode(snapshot)
        val decoded = HotSpotEvidenceDocument.decode(encoded)
        assertFalse(decoded.collectionLossComplete)
    }

    @Test
    fun `altered raw byte in stream changes delivered wire prefix hash`() {
        val rawBytes = checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { it.readBytes() }
        val receipt = HotSpotWireReader(HotSpotReplayLimits()).read(ByteArrayInputStream(rawBytes)) { }

        val alteredBytes = rawBytes.copyOf()
        val targetPattern = "/fixture/".toByteArray(Charsets.UTF_8)
        val idx = (0 until alteredBytes.size - targetPattern.size).first { i ->
            targetPattern.indices.all { j -> alteredBytes[i + j] == targetPattern[j] }
        } +
            targetPattern.size
        alteredBytes[idx] = (alteredBytes[idx].toInt() xor 1).toByte()

        val alteredReceipt = HotSpotWireReader(HotSpotReplayLimits()).read(ByteArrayInputStream(alteredBytes)) { }
        assertNotEquals(receipt.sha256, alteredReceipt.sha256)
        assertEquals(receipt.bytes, alteredReceipt.bytes)
        assertEquals(receipt.records, alteredReceipt.records)
    }

    @Test
    fun `altered structural byte in stream triggers parser oracle rejection`() {
        val rawBytes = checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { it.readBytes() }
        val alteredBytes = rawBytes.copyOf()
        alteredBytes[0] = 'X'.code.toByte()
        assertFailsWith<HotSpotProtocolException> {
            HotSpotWireReader(HotSpotReplayLimits()).read(ByteArrayInputStream(alteredBytes)) { }
        }
    }

    @Test
    fun `appended record after final summary is rejected`() {
        val rawBytes = checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { it.readBytes() }
        val lines = rawBytes.toString(Charsets.UTF_8).split("\n").filter { it.isNotBlank() }
        val appended = rawBytes + (lines[0] + "\n").toByteArray(Charsets.UTF_8)
        assertFailsWith<HotSpotProtocolException> {
            HotSpotWireReader(HotSpotReplayLimits()).read(ByteArrayInputStream(appended)) { }
        }
    }

    @Test
    fun `missing summary fails validEof and prevents finalization`() {
        val rawBytes = checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { it.readBytes() }
        val lines = rawBytes.toString(Charsets.UTF_8).split("\n").filter { it.isNotBlank() }
        val prefixOnly = (lines[0] + "\n" + lines[1] + "\n").toByteArray(Charsets.UTF_8)
        val records = mutableListOf<HotSpotWireRecord>()
        val receiptWithoutSummary = HotSpotWireReader(HotSpotReplayLimits()).read(ByteArrayInputStream(prefixOnly), records::add)
        assertFalse(receiptWithoutSummary.validEof)

        val accumulator = HotSpotEvidenceAssembler().begin()
        records.forEach(accumulator::accept)
        assertFailsWith<HotSpotProtocolException> {
            accumulator.finish(receiptWithoutSummary)
        }
    }

    @Test
    fun `retention0 with positive delivery admits pairs and classifies discards`() {
        val limits = HotSpotReplayLimits(maxRetainedOccurrences = 0)
        val snapshot = HotSpotEvidenceAssembler(limits).read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        assertTrue(snapshot.records.isEmpty())
        assertEquals(1uL, snapshot.discardedOccurrences)
        assertTrue(snapshot.terminalAccountingComplete)
        assertFalse(snapshot.retentionComplete)
        assertTrue(snapshot.finalized)
    }

    @Test
    fun `forged true report without proof envelope is rejected`() {
        val snapshot = HotSpotEvidenceAssembler().read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        val encoded = HotSpotEvidenceDocument.encode(snapshot)
        val parsed = Json.parseToJsonElement(encoded) as JsonObject
        val forged = JsonObject(
            parsed.toMutableMap().apply {
            put("collectionLossComplete", JsonPrimitive(true))
        },
        ).toString()
        assertNotEquals(encoded, forged)
        assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceDocument.decode(forged)
        }
    }

    @Test
    fun `positive pure-model admission baseline reconciles without loss with separately fixed expected facts`() {
        val base = validModelBaseline()
        val outcome = HotSpotCollectionAccounting.evaluate(
            summaryObject = base.summary,
            wireReceipt = base.receipt,
            counts = base.counts,
            expectedSessionId = "testsession",
            expectedPid = 12345,
            expectedStartTicks = 67890uL,
            physicalDrainComplete = true,
            terminalAccountingComplete = true,
            admissionContext = base.admissionContext,
        )
        assertTrue(outcome is HotSpotCollectionOutcome.ReconciledWithoutLoss)
        assertTrue(outcome.complete)
        assertTrue(outcome.lossFree)
        assertEquals("true", outcome.identities["model_only"])
        assertEquals("7", outcome.identities["selected_events_map_id"])
        assertEquals("11", outcome.identities["definitions_map_id"])

        // Also assert checked known retention/source loss gives ReconciledWithLoss
        // 1. Source loss: reserve_failed = 1, attempted = 3 (conservation: attempted = submitted + reserve_failed = 2 + 1 = 3)
        val sourceLossFixture = validModelBaseline(
            selectedAttempted = 3uL,
            selectedReserveFailed = 1uL,
        )
        val sourceLossOutcome = HotSpotCollectionAccounting.evaluate(
            summaryObject = sourceLossFixture.summary,
            wireReceipt = sourceLossFixture.receipt,
            counts = sourceLossFixture.counts,
            expectedSessionId = "testsession",
            expectedPid = 12345,
            expectedStartTicks = 67890uL,
            physicalDrainComplete = true,
            terminalAccountingComplete = true,
            admissionContext = sourceLossFixture.admissionContext,
        )
        assertTrue(sourceLossOutcome is HotSpotCollectionOutcome.ReconciledWithLoss)
        assertTrue(sourceLossOutcome.complete)
        assertFalse(sourceLossOutcome.lossFree)
        assertEquals(1uL, sourceLossOutcome.totalLost)
        assertEquals(1uL, sourceLossOutcome.sourceLosses["selected_events"])

        // 2. Retention loss: discardedOccurrences = 1, admittedOccurrences = 3
        val retLossFixture = validModelBaseline(
            selectedAttempted = 3uL,
            selectedSubmitted = 3uL,
            selectedReceived = 3uL,
            admittedOccurrences = 3uL,
            discardedOccurrences = 1uL,
            enrichReceived = 3uL,
            enrichResolved = 3uL,
        )
        val retLossOutcome = HotSpotCollectionAccounting.evaluate(
            summaryObject = retLossFixture.summary,
            wireReceipt = retLossFixture.receipt,
            counts = retLossFixture.counts,
            expectedSessionId = "testsession",
            expectedPid = 12345,
            expectedStartTicks = 67890uL,
            physicalDrainComplete = true,
            terminalAccountingComplete = true,
            admissionContext = retLossFixture.admissionContext,
        )
        assertTrue(retLossOutcome is HotSpotCollectionOutcome.ReconciledWithLoss)
        assertTrue(retLossOutcome.complete)
        assertFalse(retLossOutcome.lossFree)
        assertEquals(1uL, retLossOutcome.totalLost)
        assertEquals(1uL, retLossOutcome.sourceLosses["retention"])
    }

    @Test
    fun `single-fact mutations from passing baseline require specific unknown reasons and unproven sources`() {
        val base = validModelBaseline()

        fun eval(
            summary: JsonObject = base.summary,
            receipt: HotSpotWireReceipt? = base.receipt,
            counts: HotSpotAccumulatorCounts = base.counts,
            sessionId: String = "testsession",
            pid: Int? = 12345,
            startTicks: ULong? = 67890uL,
            drainComplete: Boolean = true,
            terminalComplete: Boolean = true,
            adm: HotSpotSourceAdmissionContext? = base.admissionContext,
        ): HotSpotCollectionOutcome =
            HotSpotCollectionAccounting.evaluate(
            summaryObject = summary,
            wireReceipt = receipt,
            counts = counts,
            expectedSessionId = sessionId,
            expectedPid = pid,
            expectedStartTicks = startTicks,
            physicalDrainComplete = drainComplete,
            terminalAccountingComplete = terminalComplete,
            admissionContext = adm,
        )

        // 1. null selected admission
        val nullSel = eval(adm = base.admissionContext.copy(selectedEvents = null))
        assertTrue(nullSel is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events admission absent", nullSel.reason)
        assertEquals(listOf("selected_events"), nullSel.unprovenSources)

        // 2. null definitions admission
        val nullDef = eval(adm = base.admissionContext.copy(definitions = null))
        assertTrue(nullDef is HotSpotCollectionOutcome.Unknown)
        assertEquals("definitions admission absent", nullDef.reason)
        assertEquals(listOf("definitions"), nullDef.unprovenSources)

        // 3. null expected PID
        val nullPid = eval(pid = null)
        assertTrue(nullPid is HotSpotCollectionOutcome.Unknown)
        assertEquals("expectedPid missing or out of valid range (1..4194304)", nullPid.reason)
        assertEquals(listOf("target"), nullPid.unprovenSources)

        // 4. null expected startTicks
        val nullTicks = eval(startTicks = null)
        assertTrue(nullTicks is HotSpotCollectionOutcome.Unknown)
        assertEquals("expectedStartTicks missing", nullTicks.reason)
        assertEquals(listOf("target"), nullTicks.unprovenSources)

        // 5. Selected generation mismatch
        val selGenMismatch = eval(
            adm = base.admissionContext.copy(
            selectedEvents = base.admissionContext.selectedEvents?.copy(generation = 99uL),
        ),
        )
        assertTrue(selGenMismatch is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events generation mismatch: expected 99, got 1", selGenMismatch.reason)
        assertEquals(listOf("selected_events"), selGenMismatch.unprovenSources)

        // 6. Selected generation absent in summary
        val selNoGenSummary = replaceSummary(
            base.summary,
            "\"selected_events\":{\"complete\":true,\"attempted\":2,\"submitted\":2,\"received\":2,\"reserve_failed\":0,\"map_id\":7,\"possible_cpus\":2,\"generation\":1",
            "\"selected_events\":{\"complete\":true,\"attempted\":2,\"submitted\":2,\"received\":2,\"reserve_failed\":0,\"map_id\":7,\"possible_cpus\":2",
        )
        val selNoGen = eval(summary = selNoGenSummary)
        assertTrue(selNoGen is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events generation missing or malformed", selNoGen.reason)
        assertEquals(listOf("selected_events"), selNoGen.unprovenSources)

        // 7. Definitions generation mismatch
        val defGenMismatch = eval(
            adm = base.admissionContext.copy(
            definitions = base.admissionContext.definitions?.copy(generation = 88uL),
        ),
        )
        assertTrue(defGenMismatch is HotSpotCollectionOutcome.Unknown)
        assertEquals("definitions generation mismatch: expected 88, got 1", defGenMismatch.reason)
        assertEquals(listOf("definitions"), defGenMismatch.unprovenSources)

        // 8. Definitions generation absent in summary
        val defNoGenSummary = replaceSummary(base.summary, "\"lost\":0,\"generation\":1", "\"lost\":0")
        val defNoGen = eval(summary = defNoGenSummary)
        assertTrue(defNoGen is HotSpotCollectionOutcome.Unknown)
        assertEquals("definitions generation missing or malformed", defNoGen.reason)
        assertEquals(listOf("definitions"), defNoGen.unprovenSources)

        // 9. Selected required finality booleans false
        for (prop in listOf("final", "quiesced", "joined", "retired")) {
            val sel = base.admissionContext.selectedEvents!!
            val mutatedSel = when (prop) {
                "final" -> sel.copy(final = false)
                "quiesced" -> sel.copy(quiesced = false)
                "joined" -> sel.copy(joined = false)
                "retired" -> sel.copy(retired = false)
                else -> sel
            }
            val res = eval(adm = base.admissionContext.copy(selectedEvents = mutatedSel))
            assertTrue(res is HotSpotCollectionOutcome.Unknown)
            assertEquals("selected_events admission not final/quiesced/joined/retired", res.reason)
            assertEquals(listOf("selected_events"), res.unprovenSources)
        }

        // 10. Selected coverage false
        val selNoCov = eval(
            adm = base.admissionContext.copy(
            selectedEvents = base.admissionContext.selectedEvents?.copy(coverage = false),
        ),
        )
        assertTrue(selNoCov is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events admission coverage not established", selNoCov.reason)
        assertEquals(listOf("selected_events"), selNoCov.unprovenSources)

        // 11. Definitions required finality booleans false
        for (prop in listOf("final", "quiesced", "joined", "retired")) {
            val d = base.admissionContext.definitions!!
            val mutatedDef = when (prop) {
                "final" -> d.copy(final = false)
                "quiesced" -> d.copy(quiesced = false)
                "joined" -> d.copy(joined = false)
                "retired" -> d.copy(retired = false)
                else -> d
            }
            val res = eval(adm = base.admissionContext.copy(definitions = mutatedDef))
            assertTrue(res is HotSpotCollectionOutcome.Unknown)
            assertEquals("definitions admission not final/quiesced/joined/retired", res.reason)
            assertEquals(listOf("definitions"), res.unprovenSources)
        }

        // 12. Definitions coverage false
        val defNoCov = eval(
            adm = base.admissionContext.copy(
            definitions = base.admissionContext.definitions?.copy(coverage = false),
        ),
        )
        assertTrue(defNoCov is HotSpotCollectionOutcome.Unknown)
        assertEquals("definitions admission coverage not established", defNoCov.reason)
        assertEquals(listOf("definitions"), defNoCov.unprovenSources)

        // 13. Selected overflow true
        val selOverflow = eval(
            adm = base.admissionContext.copy(
            selectedEvents = base.admissionContext.selectedEvents?.copy(overflow = true),
        ),
        )
        assertTrue(selOverflow is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events admission indicates overflow", selOverflow.reason)
        assertEquals(listOf("selected_events"), selOverflow.unprovenSources)

        // 14. Selected error non-null
        val selErr = eval(
            adm = base.admissionContext.copy(
            selectedEvents = base.admissionContext.selectedEvents?.copy(error = "bpf_perf_event_output failed"),
        ),
        )
        assertTrue(selErr is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events admission error: bpf_perf_event_output failed", selErr.reason)
        assertEquals(listOf("selected_events"), selErr.unprovenSources)

        // 15. Definitions overflow true
        val defOverflow = eval(
            adm = base.admissionContext.copy(
            definitions = base.admissionContext.definitions?.copy(overflow = true),
        ),
        )
        assertTrue(defOverflow is HotSpotCollectionOutcome.Unknown)
        assertEquals("definitions admission indicates overflow", defOverflow.reason)
        assertEquals(listOf("definitions"), defOverflow.unprovenSources)

        // 16. Definitions error non-null
        val defErr = eval(
            adm = base.admissionContext.copy(
            definitions = base.admissionContext.definitions?.copy(error = "worker process killed"),
        ),
        )
        assertTrue(defErr is HotSpotCollectionOutcome.Unknown)
        assertEquals("definitions admission error: worker process killed", defErr.reason)
        assertEquals(listOf("definitions"), defErr.unprovenSources)

        // 17. Absent each mandatory counter in selected_events
        for (counter in listOf("attempted", "submitted", "received", "reserve_failed")) {
            val json = replaceSummary(base.summary, "\"$counter\":", "\"stripped\":")
            val res = eval(summary = json)
            assertTrue(res is HotSpotCollectionOutcome.Unknown)
            assertEquals(listOf("selected_events"), res.unprovenSources)
        }

        // 18. Absent each mandatory counter in enrichment
        val noEnrichRec = replaceSummary(base.summary, "\"enrichment\":{\"received\":2", "\"enrichment\":{\"stripped\":2")
        val noEnrichRecRes = eval(summary = noEnrichRec)
        assertTrue(noEnrichRecRes is HotSpotCollectionOutcome.Unknown)
        assertEquals(listOf("enrichment"), noEnrichRecRes.unprovenSources)

        for (counter in listOf("resolved", "unavailable", "skipped")) {
            val json = replaceSummary(base.summary, "\"$counter\":", "\"stripped\":")
            val res = eval(summary = json)
            assertTrue(res is HotSpotCollectionOutcome.Unknown)
            assertEquals(listOf("enrichment"), res.unprovenSources)
        }

        // 19. Absent mandatory counter in definitions
        val defNoLost = replaceSummary(base.summary, "\"lost\":0", "\"stripped\":0")
        val defNoLostRes = eval(summary = defNoLost)
        assertTrue(defNoLostRes is HotSpotCollectionOutcome.Unknown)
        assertEquals("definitions lost missing or malformed", defNoLostRes.reason)
        assertEquals(listOf("definitions"), defNoLostRes.unprovenSources)

        // 20. Pending pair (allPaired = false)
        val unpaired = eval(counts = base.counts.copy(allPaired = false))
        assertTrue(unpaired is HotSpotCollectionOutcome.Unknown)
        assertEquals("unpaired event/terminal records remain", unpaired.reason)

        // 21. Invalid wire EOF
        val badEof = eval(receipt = base.receipt.copy(validEof = false))
        assertTrue(badEof is HotSpotCollectionOutcome.Unknown)
        assertEquals("wire receipt absent or EOF invalid", badEof.reason)
        assertEquals(listOf("wire"), badEof.unprovenSources)

        // 22. Wire receipt null
        val nullWire = eval(receipt = null)
        assertTrue(nullWire is HotSpotCollectionOutcome.Unknown)
        assertEquals("wire receipt absent or EOF invalid", nullWire.reason)
        assertEquals(listOf("wire"), nullWire.unprovenSources)

        // 23. Wire prefix hash mismatch
        val badWireHash = eval(receipt = base.receipt.copy(sha256 = "f".repeat(64)))
        assertTrue(badWireHash is HotSpotCollectionOutcome.Unknown)
        assertEquals(listOf("wire"), badWireHash.unprovenSources)

        // 24. Receipt-side controls: mutate/delete each receipt fact for selected_events and definitions
        fun mutateSource(
            sourceName: String,
            transform: (MutableMap<String, JsonElement>) -> Unit,
        ): JsonObject {
            val root = base.summary.toMutableMap()
            val prod = (root["production_accounting"] as JsonObject).toMutableMap()
            val sources = (prod["sources"] as JsonObject).toMutableMap()
            val src = (sources[sourceName] as JsonObject).toMutableMap()
            transform(src)
            sources[sourceName] = JsonObject(src)
            prod["sources"] = JsonObject(sources)
            root["production_accounting"] = JsonObject(prod)
            return JsonObject(root)
        }

        for (lane in listOf("selected_events", "definitions")) {
            // Finality flags
            for (flag in listOf("final", "quiesced", "joined", "retired")) {
                val falseRes = eval(summary = mutateSource(lane) { it[flag] = JsonPrimitive(false) })
                assertTrue(falseRes is HotSpotCollectionOutcome.Unknown)
                if (flag == "final") {
                    assertEquals("$lane receipt finality contradiction: expected true, got false", falseRes.reason)
                } else {
                    assertEquals("$lane receipt $flag contradiction: expected true, got false", falseRes.reason)
                }
                assertEquals(listOf(lane), falseRes.unprovenSources)

                val strRes = eval(summary = mutateSource(lane) { it[flag] = JsonPrimitive("true") })
                assertTrue(strRes is HotSpotCollectionOutcome.Unknown)
                assertEquals("$lane receipt $flag missing or not boolean", strRes.reason)
                assertEquals(listOf(lane), strRes.unprovenSources)

                val missingRes = eval(summary = mutateSource(lane) { it.remove(flag) })
                assertTrue(missingRes is HotSpotCollectionOutcome.Unknown)
                assertEquals("$lane receipt $flag missing or not boolean", missingRes.reason)
                assertEquals(listOf(lane), missingRes.unprovenSources)
            }

            // Coverage
            val covFalse = eval(summary = mutateSource(lane) { it["coverage"] = JsonPrimitive(false) })
            assertTrue(covFalse is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt coverage contradiction: expected true, got false", covFalse.reason)
            assertEquals(listOf(lane), covFalse.unprovenSources)

            val covStr = eval(summary = mutateSource(lane) { it["coverage"] = JsonPrimitive("true") })
            assertTrue(covStr is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt coverage missing or not boolean", covStr.reason)
            assertEquals(listOf(lane), covStr.unprovenSources)

            val covMissing = eval(summary = mutateSource(lane) { it.remove("coverage") })
            assertTrue(covMissing is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt coverage missing or not boolean", covMissing.reason)
            assertEquals(listOf(lane), covMissing.unprovenSources)

            // Overflow
            val ovfTrue = eval(summary = mutateSource(lane) { it["overflow"] = JsonPrimitive(true) })
            assertTrue(ovfTrue is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt overflow contradiction: expected false, got true", ovfTrue.reason)
            assertEquals(listOf(lane), ovfTrue.unprovenSources)

            val ovfStr = eval(summary = mutateSource(lane) { it["overflow"] = JsonPrimitive("false") })
            assertTrue(ovfStr is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt overflow missing or not boolean", ovfStr.reason)
            assertEquals(listOf(lane), ovfStr.unprovenSources)

            val ovfMissing = eval(summary = mutateSource(lane) { it.remove("overflow") })
            assertTrue(ovfMissing is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt overflow missing or not boolean", ovfMissing.reason)
            assertEquals(listOf(lane), ovfMissing.unprovenSources)

            // Error
            val errNonEmpty = eval(summary = mutateSource(lane) { it["error"] = JsonPrimitive("simulated failure") })
            assertTrue(errNonEmpty is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt error: simulated failure", errNonEmpty.reason)
            assertEquals(listOf(lane), errNonEmpty.unprovenSources)

            val errWrongType = eval(summary = mutateSource(lane) { it["error"] = JsonPrimitive(123) })
            assertTrue(errWrongType is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt error malformed or wrong type", errWrongType.reason)
            assertEquals(listOf(lane), errWrongType.unprovenSources)

            val errMissing = eval(summary = mutateSource(lane) { it.remove("error") })
            assertTrue(errMissing is HotSpotCollectionOutcome.Unknown)
            assertEquals("$lane receipt error missing", errMissing.reason)
            assertEquals(listOf(lane), errMissing.unprovenSources)
        }
    }

    @Test
    fun `numeric table strictly rejects malformed representations and checks bounds and aliases`() {
        val base = validModelBaseline()

        fun evalSummary(summary: JsonObject): HotSpotCollectionOutcome =
            HotSpotCollectionAccounting.evaluate(
            summaryObject = summary,
            wireReceipt = base.receipt,
            counts = base.counts,
            expectedSessionId = "testsession",
            expectedPid = 12345,
            expectedStartTicks = 67890uL,
            physicalDrainComplete = true,
            terminalAccountingComplete = true,
            admissionContext = base.admissionContext,
        )

        // Malformed numeric representations for reserve_failed
        val malformedCases = listOf(
            "\"1\"", // quoted 1
            "\"0\"", // quoted 0
            "-1", // negative
            "-0", // negative zero
            "1.5", // fraction
            "1e2", // exponent
            "null", // null
            "true", // boolean true
            "false", // boolean false
            "18446744073709551616", // ULong.MAX_VALUE + 1 overflow
        )
        for (bad in malformedCases) {
            val json = replaceSummary(base.summary, "\"reserve_failed\":0", "\"reserve_failed\":$bad")
            val res = evalSummary(json)
            assertTrue(res is HotSpotCollectionOutcome.Unknown, "Expected rejection for bad numeric: $bad")
            assertEquals("selected_events reserve_failed missing, malformed or negative", res.reason)
            assertEquals(listOf("selected_events"), res.unprovenSources)
        }

        // Upper-half unsigned counter (ULong.MAX_VALUE): parses and reaches consistent decision
        val ulongMaxStr = "18446744073709551615"
        val upperHalfDefJson = replaceSummary(base.summary, "\"lost\":0", "\"lost\":$ulongMaxStr")
        val upperHalfRes = evalSummary(upperHalfDefJson)
        assertTrue(upperHalfRes is HotSpotCollectionOutcome.ReconciledWithLoss)
        assertEquals(ULong.MAX_VALUE, upperHalfRes.totalLost)
        assertEquals(ULong.MAX_VALUE, upperHalfRes.sourceLosses["definitions"])

        // Checked MAX+1 overflow in loss summing
        val overflowLossJson = replaceSummary(
            replaceSummary(
                replaceSummary(base.summary, "\"lost\":0", "\"lost\":$ulongMaxStr"),
                "\"attempted\":2",
                "\"attempted\":3",
            ),
            "\"reserve_failed\":0",
            "\"reserve_failed\":1",
        )
        val overflowRes = evalSummary(overflowLossJson)
        assertTrue(overflowRes is HotSpotCollectionOutcome.Unknown)
        assertEquals("total loss overflow", overflowRes.reason)
        assertEquals(listOf("definitions"), overflowRes.unprovenSources)

        // Reject map_id 4294967303 (2^32 + 7, overflows uint32) against expected 7 BEFORE narrowing
        val badMapIdJson = replaceSummary(base.summary, "\"map_id\":7", "\"map_id\":4294967303")
        val badMapIdRes = evalSummary(badMapIdJson)
        assertTrue(badMapIdRes is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events map_id out of uint32 range: 4294967303", badMapIdRes.reason)
        assertEquals(listOf("selected_events"), badMapIdRes.unprovenSources)

        // Reject valid uint32 map_id mismatch (8 vs expected 7)
        val mismatchMapIdJson = replaceSummary(base.summary, "\"map_id\":7", "\"map_id\":8")
        val mismatchMapIdRes = evalSummary(mismatchMapIdJson)
        assertTrue(mismatchMapIdRes is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events map_id mismatch: expected 7, got 8", mismatchMapIdRes.reason)
        assertEquals(listOf("selected_events"), mismatchMapIdRes.unprovenSources)

        // Reject possible_cpus 4294967298 (> Int.MAX_VALUE) against expected 2 BEFORE narrowing
        val badCpusJson = replaceSummary(base.summary, "\"possible_cpus\":2", "\"possible_cpus\":4294967298")
        val badCpusRes = evalSummary(badCpusJson)
        assertTrue(badCpusRes is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events possible_cpus out of int32 range: 4294967298", badCpusRes.reason)
        assertEquals(listOf("selected_events"), badCpusRes.unprovenSources)

        // Aliases:
        // 1. Alias only: "mapId": 7 instead of "map_id": 7
        val aliasOnlyJson = replaceSummary(base.summary, "\"map_id\":7", "\"mapId\":7")
        val aliasOnlyRes = evalSummary(aliasOnlyJson)
        assertTrue(aliasOnlyRes is HotSpotCollectionOutcome.ReconciledWithoutLoss)

        // 2. Both canonical and alias present and agreeing
        val bothAgreeJson = replaceSummary(base.summary, "\"map_id\":7", "\"map_id\":7,\"mapId\":7")
        val bothAgreeRes = evalSummary(bothAgreeJson)
        assertTrue(bothAgreeRes is HotSpotCollectionOutcome.ReconciledWithoutLoss)

        // 3. Both present and conflicting
        val bothConflictJson = replaceSummary(base.summary, "\"map_id\":7", "\"map_id\":7,\"mapId\":8")
        val bothConflictRes = evalSummary(bothConflictJson)
        assertTrue(bothConflictRes is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events map_id missing or malformed", bothConflictRes.reason)

        // 4. Malformed canonical + valid alias: must NOT rescue
        val malformedCanonicalJson = replaceSummary(base.summary, "\"map_id\":7", "\"map_id\":\"bad\",\"mapId\":7")
        val malformedRes = evalSummary(malformedCanonicalJson)
        assertTrue(malformedRes is HotSpotCollectionOutcome.Unknown)
        assertEquals("selected_events map_id missing or malformed", malformedRes.reason)
    }

    @Test
    fun `retained bytes require exact ledger match independent charge and accumulator bounds`() {
        val base = validModelBaseline()

        fun evalCounts(counts: HotSpotAccumulatorCounts): HotSpotCollectionOutcome =
            HotSpotCollectionAccounting.evaluate(
            summaryObject = base.summary,
            wireReceipt = base.receipt,
            counts = counts,
            expectedSessionId = "testsession",
            expectedPid = 12345,
            expectedStartTicks = 67890uL,
            physicalDrainComplete = true,
            terminalAccountingComplete = true,
            admissionContext = base.admissionContext,
        )

        // These are MODEL facts; no source authority is implied by a passing equation.
        assertTrue(evalCounts(base.counts) is HotSpotCollectionOutcome.ReconciledWithoutLoss)
        for (counts in listOf(
            base.counts.copy(admittedPayloadBytes = -1),
            base.counts.copy(discardedPayloadBytes = -1),
            base.counts.copy(admittedPayloadBytes = base.counts.admittedPayloadBytes + 1),
            base.counts.copy(discardedPayloadBytes = 1),
            base.counts.copy(discardedPayloadBytes = Long.MAX_VALUE),
        )) {
            val result = evalCounts(counts)
            assertTrue(result is HotSpotCollectionOutcome.Unknown)
            assertEquals(listOf("retention"), result.unprovenSources)
        }

        val unfinished = HotSpotCollectionAccounting.evaluate(
            summaryObject = base.summary,
            wireReceipt = base.receipt.copy(validEof = false),
            counts = base.counts.copy(admittedPayloadBytes = -1),
            expectedSessionId = "testsession",
            expectedPid = 12345,
            expectedStartTicks = 67890uL,
            physicalDrainComplete = true,
            terminalAccountingComplete = true,
            admissionContext = base.admissionContext,
        )
        assertTrue(unfinished is HotSpotCollectionOutcome.Unknown)
        assertEquals(listOf("wire"), unfinished.unprovenSources)

        // 1. retainedBytes + 1 with unchanged charge
        val resPlus = evalCounts(base.counts.copy(retainedBytes = 1025, chargedBytes = 1024))
        assertTrue(resPlus is HotSpotCollectionOutcome.Unknown)
        assertEquals("retainedBytes (1025) does not match independent charge (1024)", resPlus.reason)
        assertEquals(listOf("retention"), resPlus.unprovenSources)

        // 2. retainedBytes - 1 with unchanged charge
        val resMinus = evalCounts(base.counts.copy(retainedBytes = 1023, chargedBytes = 1024))
        assertTrue(resMinus is HotSpotCollectionOutcome.Unknown)
        assertEquals("retainedBytes (1023) does not match independent charge (1024)", resMinus.reason)
        assertEquals(listOf("retention"), resMinus.unprovenSources)

        // 3. independent charge changed only
        val resChargeOnly = evalCounts(base.counts.copy(retainedBytes = 1024, chargedBytes = 1025))
        assertTrue(resChargeOnly is HotSpotCollectionOutcome.Unknown)
        assertEquals("retainedBytes (1024) does not match independent charge (1025)", resChargeOnly.reason)
        assertEquals(listOf("retention"), resChargeOnly.unprovenSources)

        // 4. exceed budget (33554433)
        val resBudget = evalCounts(base.counts.copy(retainedBytes = 33554433, chargedBytes = 33554433))
        assertTrue(resBudget is HotSpotCollectionOutcome.Unknown)
        assertEquals("retained byte charge out of valid range: 33554433", resBudget.reason)
        assertEquals(listOf("retention"), resBudget.unprovenSources)

        // 5. overflow checked sum in retention occurrences
        val resOverflow = evalCounts(base.counts.copy(retainedOccurrences = ULong.MAX_VALUE, discardedOccurrences = 1uL))
        assertTrue(resOverflow is HotSpotCollectionOutcome.Unknown)
        assertEquals("retention occurrences count overflow", resOverflow.reason)
        assertEquals(listOf("retention"), resOverflow.unprovenSources)

        // Actual accumulator tests:
        val rows = rows()
        val eventRow = rows[0]
        val termRow = rows[1]
        val summaryRow = rows[2]

        // Normal accumulation with genuine rows
        val accNormal = HotSpotEvidenceAssembler().begin()
        accNormal.accept(eventRow)
        accNormal.accept(termRow)
        accNormal.accept(summaryRow)
        val snapNormal = accNormal.finish()
        assertEquals(1, snapNormal.records.size)
        assertEquals(0uL, snapNormal.discardedOccurrences)
        assertTrue(snapNormal.retainedBytes > 0)

        // Retention0 with positive delivery
        val limitsZero = HotSpotReplayLimits(maxRetainedBytes = 0)
        val accZero = HotSpotEvidenceAssembler(limitsZero).begin()
        accZero.accept(eventRow)
        accZero.accept(termRow)
        accZero.accept(summaryRow)
        val snapZero = accZero.finish()
        assertEquals(0, snapZero.records.size)
        assertEquals(1uL, snapZero.discardedOccurrences)
        assertEquals(0, snapZero.retainedBytes)

        // Eviction when budget exceeded
        val smallLimits = HotSpotReplayLimits(maxRetainedBytes = 50)
        val accSmall = HotSpotEvidenceAssembler(smallLimits).begin()
        accSmall.accept(eventRow)
        accSmall.accept(termRow)
        accSmall.accept(summaryRow)
        val snapSmall = accSmall.finish()
        assertEquals(0, snapSmall.records.size)
        assertEquals(1uL, snapSmall.discardedOccurrences)
    }

    @Test
    fun `forged true report with proof-looking envelope and synthetic definitions is rejected`() {
        val snapshot = HotSpotEvidenceAssembler().read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        val encoded = HotSpotEvidenceDocument.encode(snapshot)
        val validSha = "c".repeat(64)

        val parsedDoc = Json.parseToJsonElement(encoded) as JsonObject
        val docMap = parsedDoc.toMutableMap()
        docMap["collectionLossComplete"] = JsonPrimitive(true)

        val origSummary = (docMap["summary"] as JsonObject).toMutableMap()
        val forgedRawAccounting = JsonObject(
            mapOf(
            "kind" to JsonPrimitive("session_summary"),
            "session_id" to JsonPrimitive(snapshot.sessionId),
            "production_accounting" to JsonObject(
                mapOf(
                "schema" to JsonPrimitive(1),
                "session_id" to JsonPrimitive(snapshot.sessionId),
                "host_pid" to JsonPrimitive(123),
                "process_start_ticks" to JsonPrimitive(456),
                "wire_prefix" to JsonObject(
                    mapOf(
                    "records" to JsonPrimitive(2),
                    "bytes" to JsonPrimitive(100),
                    "sha256" to JsonPrimitive(validSha),
                ),
                ),
                "sources" to JsonObject(
                    mapOf(
                    "selected_events" to JsonObject(
                        mapOf(
                        "complete" to JsonPrimitive(true),
                        "received" to JsonPrimitive(1),
                        "reserve_failed" to JsonPrimitive(0),
                        "map_id" to JsonPrimitive(7),
                        "possible_cpus" to JsonPrimitive(1),
                        "generation" to JsonPrimitive(1),
                    ),
                    ),
                    "enrichment" to JsonObject(
                        mapOf(
                        "received" to JsonPrimitive(1),
                        "resolved" to JsonPrimitive(1),
                        "unavailable" to JsonPrimitive(0),
                        "skipped" to JsonPrimitive(0),
                    ),
                    ),
                    "definitions" to JsonObject(
                        mapOf(
                        "complete" to JsonPrimitive(true),
                        "map_id" to JsonPrimitive(11),
                        "possible_cpus" to JsonPrimitive(1),
                        "lost" to JsonPrimitive(0),
                        "generation" to JsonPrimitive(1),
                    ),
                    ),
                ),
                ),
            ),
            ),
        ),
        )
        origSummary["raw"] = forgedRawAccounting
        docMap["summary"] = JsonObject(origSummary)

        val clientReceiptObj = JsonObject(
            mapOf(
            "schema" to JsonPrimitive(1),
            "records" to JsonPrimitive(2),
            "bytes" to JsonPrimitive(100),
            "validEof" to JsonPrimitive(true),
            "sha256" to JsonPrimitive(validSha),
        ),
        )
        docMap["client_receipt"] = clientReceiptObj

        val forgedObj = JsonObject(docMap)
        val forgedText = forgedObj.toString()

        // Assert nested envelope equality, receipt presence and changed serialized text BEFORE decode
        assertNotEquals(encoded, forgedText)
        val reParsed = Json.parseToJsonElement(forgedText) as JsonObject
        assertEquals(JsonPrimitive(true), reParsed["collectionLossComplete"])
        val reSummary = reParsed["summary"] as JsonObject
        assertTrue(reSummary["raw"] is JsonObject, "summary.raw must be an object")
        assertEquals(forgedRawAccounting, reSummary["raw"])
        assertTrue(reParsed["client_receipt"] is JsonObject, "root client_receipt must be an object")
        assertEquals(clientReceiptObj, reParsed["client_receipt"])

        val ex = assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceDocument.decode(forgedText)
        }
        assertEquals("serialized collectionLossComplete true cannot be established from document", ex.message)
    }

    @Test
    fun `forged report with string-valued raw is rejected as malformed raw`() {
        val snapshot = HotSpotEvidenceAssembler().read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        val encoded = HotSpotEvidenceDocument.encode(snapshot)
        val parsedDoc = Json.parseToJsonElement(encoded) as JsonObject
        val docMap = parsedDoc.toMutableMap()
        val origSummary = (docMap["summary"] as JsonObject).toMutableMap()
        origSummary["raw"] = JsonPrimitive("malformed_string_raw")
        docMap["summary"] = JsonObject(origSummary)
        val corruptedText = JsonObject(docMap).toString()

        val ex = assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceDocument.decode(corruptedText)
        }
        assertEquals("missing object raw", ex.message)
    }

    @Test
    fun `document local count or byte mismatch is rejected`() {
        val snapshot = HotSpotEvidenceAssembler().read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        assertFalse(snapshot.collectionLossComplete)
        val encoded = HotSpotEvidenceDocument.encode(snapshot)

        // Mutate received count via JsonObject
        val parsed1 = Json.parseToJsonElement(encoded) as JsonObject
        val docMap1 = parsed1.toMutableMap()
        val sumMap1 = (docMap1["summary"] as JsonObject).toMutableMap()
        sumMap1["received"] = JsonPrimitive(99)
        docMap1["summary"] = JsonObject(sumMap1)
        val corruptedReceived = JsonObject(docMap1).toString()
        assertNotEquals(encoded, corruptedReceived)
        assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceDocument.decode(corruptedReceived)
        }

        // Mutate terminal count via JsonObject
        val parsed2 = Json.parseToJsonElement(encoded) as JsonObject
        val docMap2 = parsed2.toMutableMap()
        val sumMap2 = (docMap2["summary"] as JsonObject).toMutableMap()
        sumMap2["resolved"] = JsonPrimitive(0)
        docMap2["summary"] = JsonObject(sumMap2)
        val corruptedTerminal = JsonObject(docMap2).toString()
        assertNotEquals(encoded, corruptedTerminal)
        assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceDocument.decode(corruptedTerminal)
        }
    }

    @Test
    fun `legacy-false document with arithmetic overflow in terminal counts is rejected`() {
        val snapshot = HotSpotEvidenceAssembler().read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        assertFalse(snapshot.collectionLossComplete)
        val encoded = HotSpotEvidenceDocument.encode(snapshot)

        // Mutate numeric summary: resolved = ULong.MAX_VALUE, unavailable = 1, skipped = 0
        // while preserving valid shape, collectionLossComplete=false, and received == retained + discarded
        val parsed = Json.parseToJsonElement(encoded) as JsonObject
        val docMap = parsed.toMutableMap()
        assertEquals(JsonPrimitive(false), docMap["collectionLossComplete"])

        // Retained records in fixture is 1, discardedOccurrences is 0 -> expectedOccurrences is 1
        val sumMap = (docMap["summary"] as JsonObject).toMutableMap()
        sumMap["received"] = JsonPrimitive(1)
        sumMap["resolved"] = JsonPrimitive(ULong.MAX_VALUE.toString().toBigInteger())
        sumMap["unavailable"] = JsonPrimitive(1)
        sumMap["skipped"] = JsonPrimitive(0)
        docMap["summary"] = JsonObject(sumMap)
        docMap["discardedOccurrences"] = JsonPrimitive(0)
        val mutatedDoc = JsonObject(docMap).toString()

        // Assert intended object/numeric mutations before decode
        val reParsed = Json.parseToJsonElement(mutatedDoc) as JsonObject
        val reParsedSummary = reParsed["summary"] as JsonObject
        assertEquals(JsonPrimitive(false), reParsed["collectionLossComplete"])
        assertEquals(JsonPrimitive(ULong.MAX_VALUE.toString().toBigInteger()), reParsedSummary["resolved"])
        assertEquals(JsonPrimitive(1), reParsedSummary["unavailable"])
        assertEquals(JsonPrimitive(0), reParsedSummary["skipped"])
        assertEquals(JsonPrimitive(1), reParsedSummary["received"])

        val ex = assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceDocument.decode(mutatedDoc)
        }
        assertEquals("arithmetic overflow in terminal counts", ex.message)
    }

    @Test
    fun `legacy-false document with arithmetic overflow in occurrence counts is rejected`() {
        val snapshot = HotSpotEvidenceAssembler().read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        assertFalse(snapshot.collectionLossComplete)
        val encoded = HotSpotEvidenceDocument.encode(snapshot)

        // Mutate discardedOccurrences to ULong.MAX_VALUE (retained records is 1)
        // 1 + ULong.MAX_VALUE overflows in checkedAddULong
        val parsed = Json.parseToJsonElement(encoded) as JsonObject
        val docMap = parsed.toMutableMap()
        docMap["discardedOccurrences"] = JsonPrimitive(ULong.MAX_VALUE.toString().toBigInteger())
        val mutatedDoc = JsonObject(docMap).toString()

        // Assert intended object/numeric mutations before decode
        val reParsed = Json.parseToJsonElement(mutatedDoc) as JsonObject
        assertEquals(JsonPrimitive(false), reParsed["collectionLossComplete"])
        assertEquals(JsonPrimitive(ULong.MAX_VALUE.toString().toBigInteger()), reParsed["discardedOccurrences"])

        val ex = assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceDocument.decode(mutatedDoc)
        }
        assertEquals("arithmetic overflow in occurrence counts", ex.message)
    }

    @Test
    fun `genuine projection fixture honestly reports definitions lane unproven and collectionLossComplete false`() {
        val snapshot = HotSpotEvidenceAssembler().read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        assertFalse(snapshot.collectionLossComplete)
        val accounting = snapshot.collectionAccounting
        assertTrue(accounting is HotSpotCollectionOutcome.Unknown)
        assertFalse(snapshot.summary.physicalDrainComplete)

        // Even with physical drain and terminal accounting complete and target facts, absent definition admission keeps definitions unproven
        val rawSummaryObj = Json.parseToJsonElement(snapshot.summary.rawJson) as JsonObject
        val drainCompleteAccounting = HotSpotCollectionAccounting.evaluate(
            summaryObject = rawSummaryObj,
            wireReceipt = HotSpotWireReceipt(records = 1uL, bytes = 100uL, sha256 = "c".repeat(64), validEof = true),
            counts = HotSpotAccumulatorCounts(
                admittedOccurrences = 1uL,
                retainedOccurrences = 1uL,
                discardedOccurrences = 0uL,
                admittedRecoveries = 0uL,
                retainedRecoveries = 0uL,
                discardedRecoveries = 0uL,
                retainedBytes = 100,
                chargedBytes = 100,
                admittedPayloadBytes = 100,
                discardedPayloadBytes = 0,
                resolved = 1uL,
                unavailable = 0uL,
                skipped = 0uL,
                allPaired = true,
            ),
            expectedSessionId = snapshot.sessionId,
            expectedPid = 12345,
            expectedStartTicks = 67890uL,
            physicalDrainComplete = true,
            terminalAccountingComplete = true,
        )
        assertTrue(drainCompleteAccounting is HotSpotCollectionOutcome.Unknown)
        assertEquals("source admission context absent", drainCompleteAccounting.reason)
        assertEquals(listOf("sources"), drainCompleteAccounting.unprovenSources)
    }

    @Test
    fun `pending occurrence without terminal prevents finalization`() {
        val rows = rows()
        val accumulator = HotSpotEvidenceAssembler().begin()
        accumulator.accept(rows[0])
        val summaryRecord = rows[2]
        accumulator.accept(summaryRecord)
        assertFailsWith<HotSpotProtocolException> {
            accumulator.finish()
        }
    }

    @Test
    fun `receipt document rejects malformed fields and cannot establish true`() {
        val evidence = HotSpotEvidenceAssembler().read(
            checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")),
        )
        val root = Json.parseToJsonElement(HotSpotEvidenceDocument.encode(evidence)) as JsonObject
        val bundle = root.getValue("collectionReceipt") as JsonObject
        val counts = bundle.getValue("counts") as JsonObject
        val wire = bundle.getValue("wireReceipt") as JsonObject

        fun withBundle(value: JsonObject): String =
            JsonObject(
            root.toMutableMap().apply {
            put("collectionReceipt", value)
        },
        ).toString()

        fun changeCount(
            key: String,
            value: JsonElement,
        ): String =
            withBundle(
            JsonObject(
                bundle.toMutableMap().apply {
            put("counts", JsonObject(counts.toMutableMap().apply { put(key, value) }))
        },
            ),
        )

        fun changeWire(
            key: String,
            value: JsonElement,
        ): String =
            withBundle(
            JsonObject(
                bundle.toMutableMap().apply {
            put("wireReceipt", JsonObject(wire.toMutableMap().apply { put(key, value) }))
        },
            ),
        )
        val invalid = listOf(
            changeCount("admittedOccurrences", JsonPrimitive(-1)),
            changeCount("admittedOccurrences", JsonPrimitive("1")),
            changeCount("admittedOccurrences", Json.parseToJsonElement("1.5")),
            changeCount("admittedOccurrences", JsonPrimitive("18446744073709551616".toBigInteger())),
            changeCount("retainedBytes", JsonPrimitive("2147483648".toBigInteger())),
            changeCount("chargedBytes", JsonPrimitive("2147483648".toBigInteger())),
            changeCount("admittedPayloadBytes", JsonPrimitive("9223372036854775808".toBigInteger())),
            changeCount("discardedPayloadBytes", JsonPrimitive(-1)),
            changeCount("admittedPayloadBytes", JsonPrimitive("1")),
            changeCount("admittedPayloadBytes", Json.parseToJsonElement("1.5")),
            changeCount("admittedPayloadBytes", JsonPrimitive(0)),
            changeCount("allPaired", JsonPrimitive("true")),
            changeCount("retainedOccurrences", JsonPrimitive(0)),
            changeWire("records", JsonPrimitive(-1)),
            changeWire("sha256", JsonPrimitive("A".repeat(64))),
            changeWire("sha256", JsonPrimitive("a".repeat(63))),
            changeWire("validEof", JsonPrimitive("false")),
            withBundle(JsonObject(bundle.toMutableMap().apply { put("schema", JsonPrimitive(2)) })),
            withBundle(JsonObject(bundle.toMutableMap().apply { put("schema", JsonPrimitive("1")) })),
        )
        invalid.forEach { text -> assertFailsWith<HotSpotProtocolException> { HotSpotEvidenceDocument.decode(text) } }
        val withoutCharge = HotSpotEvidenceDocument.decode(changeCount("chargedBytes", JsonNull))
        assertEquals(null, checkNotNull(withoutCharge.collectionReceipt).counts.chargedBytes)
        assertFalse(withoutCharge.collectionLossComplete)
        val legacy = JsonObject(root.toMutableMap().apply { remove("collectionReceipt") })
        assertEquals(null, HotSpotEvidenceDocument.decode(legacy.toString()).collectionReceipt)
        assertFalse(HotSpotEvidenceDocument.decode(legacy.toString()).collectionLossComplete)
        val claimedOwner = JsonObject(
            root.toMutableMap().apply {
            put("model_only", JsonPrimitive(true))
            put("source_owner", JsonPrimitive("claimed"))
        },
        )
        assertFalse(HotSpotEvidenceDocument.decode(claimedOwner.toString()).collectionLossComplete)
        for (candidate in listOf(root, legacy, claimedOwner)) {
            val forged = JsonObject(candidate.toMutableMap().apply { put("collectionLossComplete", JsonPrimitive(true)) })
            assertFailsWith<HotSpotProtocolException> { HotSpotEvidenceDocument.decode(forged.toString()) }
        }
    }

    @Test
    fun `pending receipt preserves maximum scalar domains without asserting final conservation`() {
        val acc = HotSpotEvidenceAssembler().begin()
        acc.accept(rows().first())
        val root = Json.parseToJsonElement(HotSpotEvidenceDocument.encode(acc.snapshot())) as JsonObject
        val bundle = root.getValue("collectionReceipt") as JsonObject
        val counts = bundle.getValue("counts") as JsonObject
        val maxCounts = JsonObject(
            counts.toMutableMap().apply {
            put("admittedOccurrences", JsonPrimitive("18446744073709551615".toBigInteger()))
            put("admittedPayloadBytes", JsonPrimitive(Long.MAX_VALUE))
            put("chargedBytes", JsonPrimitive(Int.MAX_VALUE))
        },
        )
        val wire = JsonObject(
            mapOf(
            "records" to JsonPrimitive("18446744073709551615".toBigInteger()),
            "bytes" to JsonPrimitive("18446744073709551615".toBigInteger()),
            "sha256" to JsonPrimitive("a".repeat(64)),
            "validEof" to JsonPrimitive(false),
        ),
        )
        val changedBundle = JsonObject(
            bundle.toMutableMap().apply {
            put("counts", maxCounts)
            put("wireReceipt", wire)
        },
        )
        val changed = JsonObject(root.toMutableMap().apply { put("collectionReceipt", changedBundle) })
        val decoded = HotSpotEvidenceDocument.decode(changed.toString())
        val result = checkNotNull(decoded.collectionReceipt)
        assertEquals(ULong.MAX_VALUE, result.counts.admittedOccurrences)
        assertEquals(Long.MAX_VALUE, result.counts.admittedPayloadBytes)
        assertEquals(Int.MAX_VALUE, result.counts.chargedBytes)
        assertEquals(ULong.MAX_VALUE, checkNotNull(result.wireReceipt).bytes)
        assertFalse(decoded.finalized)
        assertFalse(decoded.collectionLossComplete)
        assertEquals(result, HotSpotEvidenceDocument.decode(HotSpotEvidenceDocument.encode(decoded)).collectionReceipt)
    }

    private fun rows(): List<HotSpotWireRecord> {
        val rows = mutableListOf<HotSpotWireRecord>()
        requireNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { input ->
            HotSpotWireReader(HotSpotReplayLimits()).read(input, rows::add)
        }
        return rows
    }

    private data class BaselineFixture(
        val summary: JsonObject,
        val receipt: HotSpotWireReceipt,
        val counts: HotSpotAccumulatorCounts,
        val admissionContext: HotSpotSourceAdmissionContext,
    )

    private fun replaceSummary(
        base: JsonObject,
        old: String,
        new: String,
    ): JsonObject {
        val s = base.toString()
        val target = if (s.contains(old)) old else old.replace(": ", ":")
        check(s.contains(target)) { "Pattern '$target' (from '$old') not found in summary: $s" }
        val replaced = s.replace(target, new)
        return Json.parseToJsonElement(replaced) as JsonObject
    }

    private fun validModelBaseline(
        sessionId: String = "testsession",
        pid: Int = 12345,
        startTicks: ULong = 67890uL,
        selectedGen: ULong = 1uL,
        defGen: ULong = 1uL,
        selectedComplete: Boolean = true,
        selectedAttempted: ULong = 2uL,
        selectedSubmitted: ULong = 2uL,
        selectedReceived: ULong = 2uL,
        selectedReserveFailed: ULong = 0uL,
        selectedMapId: UInt = 7u,
        selectedPossibleCpus: Int = 2,
        enrichReceived: ULong = 2uL,
        enrichResolved: ULong = 2uL,
        enrichUnavailable: ULong = 0uL,
        enrichSkipped: ULong = 0uL,
        defComplete: Boolean = true,
        defLost: ULong = 0uL,
        defMapId: UInt = 11u,
        defPossibleCpus: Int = 2,
        retainedBytes: Int = 1024,
        chargedBytes: Int = 1024,
        admittedOccurrences: ULong = 2uL,
        retainedOccurrences: ULong = 2uL,
        discardedOccurrences: ULong = 0uL,
        admittedRecoveries: ULong = 1uL,
        retainedRecoveries: ULong = 1uL,
        discardedRecoveries: ULong = 0uL,
        allPaired: Boolean = true,
        wireRecords: ULong = 3uL,
        wireBytes: ULong = 100uL,
        wireValidEof: Boolean = true,
        wireSha: String = "a".repeat(64),
        selectedAdmissionOverride: HotSpotSourceAdmission? = HotSpotSourceAdmission(
            mapId = 7u,
            possibleCpus = 2,
            generation = 1uL,
            final = true,
            quiesced = true,
            joined = true,
            retired = true,
            overflow = false,
            coverage = true,
        ),
        defAdmissionOverride: HotSpotSourceAdmission? = HotSpotSourceAdmission(
            mapId = 11u,
            possibleCpus = 2,
            generation = 1uL,
            final = true,
            quiesced = true,
            joined = true,
            retired = true,
            overflow = false,
            coverage = true,
        ),
        isModelOnly: Boolean = true,
        summaryJsonOverride: String? = null,
    ): BaselineFixture {
        val counts = HotSpotAccumulatorCounts(
            admittedOccurrences = admittedOccurrences,
            retainedOccurrences = retainedOccurrences,
            discardedOccurrences = discardedOccurrences,
            admittedRecoveries = admittedRecoveries,
            retainedRecoveries = retainedRecoveries,
            discardedRecoveries = discardedRecoveries,
            retainedBytes = retainedBytes,
            chargedBytes = chargedBytes,
            admittedPayloadBytes = retainedBytes.toLong() + if (discardedOccurrences > 0uL || discardedRecoveries > 0uL) 1L else 0L,
            discardedPayloadBytes = if (discardedOccurrences > 0uL || discardedRecoveries > 0uL) 1L else 0L,
            resolved = enrichResolved,
            unavailable = enrichUnavailable,
            skipped = enrichSkipped,
            allPaired = allPaired,
        )
        val receipt = HotSpotWireReceipt(
            records = wireRecords,
            bytes = wireBytes,
            sha256 = wireSha,
            validEof = wireValidEof,
        )
        val admission = HotSpotSourceAdmissionContext(
            selectedEvents = selectedAdmissionOverride,
            definitions = defAdmissionOverride,
            isModelOnly = isModelOnly,
        )
        val jsonStr = summaryJsonOverride ?: """
        {
            "schema_version": 1,
            "kind": "session_summary",
            "session_id": "$sessionId",
            "production_accounting": {
                "schema": 1,
                "session_id": "$sessionId",
                "host_pid": $pid,
                "process_start_ticks": $startTicks,
                "wire_prefix": {
                    "records": $wireRecords,
                    "bytes": $wireBytes,
                    "sha256": "$wireSha"
                },
                "sources": {
                    "selected_events": {
                        "complete": $selectedComplete,
                        "attempted": $selectedAttempted,
                        "submitted": $selectedSubmitted,
                        "received": $selectedReceived,
                        "reserve_failed": $selectedReserveFailed,
                        "map_id": $selectedMapId,
                        "possible_cpus": $selectedPossibleCpus,
                        "generation": $selectedGen,
                        "final": true,
                        "quiesced": true,
                        "joined": true,
                        "retired": true,
                        "coverage": true,
                        "overflow": false,
                        "error": null
                    },
                    "enrichment": {
                        "received": $enrichReceived,
                        "resolved": $enrichResolved,
                        "unavailable": $enrichUnavailable,
                        "skipped": $enrichSkipped
                    },
                    "definitions": {
                        "complete": $defComplete,
                        "map_id": $defMapId,
                        "possible_cpus": $defPossibleCpus,
                        "lost": $defLost,
                        "generation": $defGen,
                        "final": true,
                        "quiesced": true,
                        "joined": true,
                        "retired": true,
                        "coverage": true,
                        "overflow": false,
                        "error": null
                    }
                }
            }
        }
        """.trimIndent()
        val summary = Json.parseToJsonElement(jsonStr) as JsonObject
        return BaselineFixture(summary, receipt, counts, admission)
    }
}
