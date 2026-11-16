package io.mazewall.profiler.testing.tierE

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.ExecutionContext
import io.mazewall.profiler.internal.attribution.ExecutionId
import io.mazewall.profiler.internal.attribution.ExecutionKind
import io.mazewall.profiler.internal.attribution.InvocationDefinition
import io.mazewall.profiler.internal.attribution.InvocationId
import io.mazewall.profiler.internal.attribution.ManagedFrame
import io.mazewall.profiler.internal.attribution.ManagedFrameKind
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.SnapshotPublication
import io.mazewall.profiler.internal.attribution.StackCaptureQuality
import io.mazewall.profiler.internal.attribution.StackDefinition
import io.mazewall.profiler.internal.attribution.StackTraceId
import io.mazewall.profiler.tierE.engine.BoundaryDiagnosticControl
import io.mazewall.profiler.tierE.engine.BoundaryDiagnosticRecord
import io.mazewall.profiler.tierE.engine.BoundaryDiagnosticSnapshot
import io.mazewall.profiler.tierE.engine.BoundaryStage
import io.mazewall.profiler.tierE.stress.TierECaptureYield
import io.mazewall.profiler.tierE.tools.controller.TierEContainerRun
import io.mazewall.profiler.tierE.tools.controller.TierEFixtureLifecycle
import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierEA4DiagnosticSupportTest {
    @TempDir
    lateinit var directory: Path

    private val healthy = DiagnosticReadyFacts(true, true, true, true, true, true, true, true, true, true, true)

    @Test
    fun startupRegistrationDoesNotCollideWithMandatoryExport() {
        val registration = "version=1\nregistration_id=orders\n"
        val encoder = java.util.Base64.getEncoder()
        val encodedText = encoder.encodeToString(registration.toByteArray())
        val encoded = Files.writeString(directory.resolve("encoded"), encodedText)
        TierEA4DiagnosticSupport.writeStartupRegistration(directory, registration)
        TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, directory.resolve("target.reg"), 4096)
        for (name in listOf("startup-target.reg", "target.reg")) {
            val path = directory.resolve(name)
            assertEquals(registration, Files.readString(path))
            val permissions = Files.getPosixFilePermissions(path)
            val expected = PosixFilePermissions.fromString("rw-------")
            assertEquals(expected, permissions)
        }
    }

    @Test
    fun startupRegistrationNeverOverwritesEvidence() {
        val startup = directory.resolve("startup-target.reg")
        TierEA4DiagnosticSupport.writeStartupRegistration(directory, "original")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { TierEA4DiagnosticSupport.writeStartupRegistration(directory, "replacement") }
        assertEquals("original", Files.readString(startup))
        val other = Files.createDirectory(directory.resolve("other"))
        Files.createSymbolicLink(other.resolve("startup-target.reg"), startup)
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { TierEA4DiagnosticSupport.writeStartupRegistration(other, "replacement") }
        assertEquals("original", Files.readString(startup))
    }

    @Test
    fun earlyFailureRetainsRegistrationAndReportsMissingPreArmArtifact() {
        val registration = "version=1\n"
        val encoder = java.util.Base64.getEncoder()
        val encodedText = encoder.encodeToString(registration.toByteArray())
        val encoded = Files.writeString(directory.resolve("encoded"), encodedText)
        val original = java.io.IOException("early exercise failure")
        val thrown = assertFailsWith<java.io.IOException> {
            TierEA4DiagnosticSupport.finishDiagnosticAttempt(
                exercise = {
                    TierEA4DiagnosticSupport.writeStartupRegistration(directory, registration)
                    throw original
                },
                quiesce = {},
                retain = {
                    TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, directory.resolve("target.reg"), 4096)
                    TierEA4DiagnosticSupport.retainEncodedArtifact(0, directory.resolve("pre-arm-missing"), directory.resolve("boundary.bpfdiag"), 4096)
                },
                classify = { DiagnosticJoin(emptyList(), emptyList(), DiagnosticOutcome.Incomplete(emptyList()), emptySet(), emptySet()) },
                publish = { join, retained -> TierEA4DiagnosticSupport.publishDiagnosticOutcome(directory, join, retained) },
            )
        }
        kotlin.test.assertSame(original, thrown)
        assertEquals(registration, Files.readString(directory.resolve("startup-target.reg")))
        assertEquals(registration, Files.readString(directory.resolve("target.reg")))
        assertTrue(Files.readString(directory.resolve("export-status.txt")).startsWith("INCOMPLETE"))
        assertTrue(Files.readString(directory.resolve("diagnostic-outcome.txt")).contains("pre-arm-missing"))
        assertTrue(thrown.suppressed.any { it is java.nio.file.NoSuchFileException })
    }

    private fun readyFixture(): Triple<Path, Path, Map<String, String>> {
        val (repository, _) = directNativeFixture()
        for (name in TierEA4DiagnosticSupport.diagnosticSources) {
            Files.createDirectories(repository.resolve(name).parent)
            if (!Files.exists(repository.resolve(name))) Files.writeString(repository.resolve(name), name)
        }
        rehashFixture(repository)
        val destination = directory.resolve("ready-attempt")
        val identities = listOf(
            "target",
            "daemon",
            "tracer",
            "target_container",
            "collector_container",
            "image",
            "live_control",
            "attach",
            "tracer_argv",
            "time_namespace",
        ).associateWith { "checked-$it" }
        return Triple(repository, destination, identities)
    }

    @Test
    fun readyFreezeIsPrivateCompleteAndPrecedesArmAndGate() {
        val (repository, destination, identities) = readyFixture()
        val input = Files.writeString(directory.resolve("input"), "example")
        TierEA4DiagnosticSupport.withPreparedAttempt(destination) { attempt ->
            TierEA4DiagnosticSupport.writeStartupRegistration(destination, "registration")
            TierEA4DiagnosticSupport.copyNativeProvenance(repository, repository.resolve(fixtureNative), destination)
            for (name in listOf("native-build-settings.txt", "native-compiler-version.txt", "source-sha256.txt", "runtime-fingerprints.txt", "ready-control.txt")) {
                Files.writeString(destination.resolve(name), "checked-$name")
            }
            Files.writeString(
                destination.resolve("source-sha256.txt"),
                TierEA4DiagnosticSupport.diagnosticSources.joinToString("\n") { "${TierEA4DiagnosticSupport.sha256(repository.resolve(it))}  $it" } + "\n",
            )
            TierEA4DiagnosticSupport.publishReadyAndArm(attempt, healthy.copy(fingerprintsComplete = false), repository, repository.resolve(fixtureNative), destination, input, identities, { true }) {
                val ready = directory.resolve("a4-discriminator-ready.json")
                assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(ready)),
                )
                val text = Files.readString(ready)
                assertTrue(text.contains("TierEProfilerSession.kt"))
                assertTrue(text.contains(TierEA4DiagnosticSupport.sha256(input)))
                assertTrue(text.contains("\"byte_length\":7"))
                assertTrue(text.contains("checked-live_control"))
                assertTrue(text.contains("startup-target.reg"))
                assertEquals("ARM\n", Files.readString(destination.resolve("attempt.state")))
            }
        }
    }

    @Test
    fun missingFreezeFactsOrPublicationFailureConsumesAttemptWithoutGate() {
        val (repository, _, identities) = readyFixture()
        val input = Files.writeString(directory.resolve("input"), "example")
        for (defect in listOf("input", "identity", "publication")) {
            val destination = directory.resolve("attempt-$defect")
            if (defect == "publication") Files.writeString(directory.resolve("a4-discriminator-ready.json"), "existing")
            var gates = 0
            assertFailsWith<Exception> {
                TierEA4DiagnosticSupport.withPreparedAttempt(destination) { attempt ->
                    prepareReadyEvidence(repository, repository.resolve(fixtureNative), destination)
                    assertFailsWith<Exception> {
                        TierEA4DiagnosticSupport.publishReadyAndArm(
                            attempt,
                            healthy,
                            repository,
                            repository.resolve(fixtureNative),
                            destination,
                            if (defect ==
                            "input"
                            ) {
                                directory.resolve("absent")
                            } else {
                                input
                            },
                                if (defect == "identity") identities - "target" else identities,
                            { true },
                        ) { gates++ }
                    }.also { failure ->
                        assertFailsWith<IllegalStateException> { attempt.armOnce(healthy, { true }) { gates++ } }
                        throw failure
                    }
                }
            }
            assertEquals(0, gates)
            assertEquals("PREPARED\n", Files.readString(destination.resolve("attempt.state")))
            assertTrue(Files.readString(destination.resolve("diagnostic-outcome.txt")).startsWith("INCOMPLETE"))
            if (defect == "publication") assertEquals("existing", Files.readString(directory.resolve("a4-discriminator-ready.json")))
        }
    }

    @Test
    fun missingDiagnosticOnlySourceReachesItsPresenceGuardWithNativeFactsValid() {
        val (repository, _, identities) = readyFixture()
        val native = repository.resolve(fixtureNative)
        val input = Files.writeString(directory.resolve("input"), "example")
        val positive = Files.createDirectory(directory.resolve("source-positive")).resolve("attempt")
        var positiveGates = 0
        TierEA4DiagnosticSupport.withPreparedAttempt(positive) { attempt ->
            prepareReadyEvidence(repository, native, positive)
            TierEA4DiagnosticSupport.publishReadyAndArm(attempt, healthy, repository, native, positive, input, identities, { true }) {
                assertTrue(Files.isRegularFile(positive.parent.resolve("a4-discriminator-ready.json")))
                positiveGates++
            }
        }
        assertEquals(1, positiveGates)
        assertEquals("ARM\n", Files.readString(positive.resolve("attempt.state")))
        val name = "profiler/src/test/resources/tier-e-a4-tracer.py"
        val source = repository.resolve(name)
        val bytes = Files.readAllBytes(source)
        val modified = Files.getLastModifiedTime(source)
        val destination = Files.createDirectory(directory.resolve("source-missing")).resolve("attempt")
        var gates = 0
        try {
            TierEA4DiagnosticSupport.withPreparedAttempt(destination) { attempt ->
                prepareReadyEvidence(repository, native, destination)
                TierEA4DiagnosticSupport.checkNativeProvenance(repository, native, destination)
                Files.delete(source)
                TierEA4DiagnosticSupport.checkNativeProvenance(repository, native, destination)
                val failure = assertFailsWith<IllegalArgumentException> {
                    TierEA4DiagnosticSupport.publishReadyAndArm(attempt, healthy, repository, native, destination, input, identities, { true }) { gates++ }
                }
                assertEquals("missing diagnostic source: $name", failure.message)
                assertFailsWith<IllegalStateException> { attempt.armOnce(healthy, { true }) { gates++ } }
                assertEquals("PREPARED\n", Files.readString(destination.resolve("attempt.state")))
                assertFalse(Files.exists(destination.parent.resolve("a4-discriminator-ready.json")))
                assertEquals(0, gates)
            }
        } finally {
            Files.write(source, bytes)
            Files.setLastModifiedTime(source, modified)
        }
    }

    @Test
    fun rejectionRequiresEveryObservedPredicateAndConsistentBranchValues() {
        val k = kernel()
        val original = k.records.single { it.plane == 2 && it.invocation == 1uL }
        val cases = listOf(
            original.copy(stage = BoundaryStage.MAIN_NO_SESSION, validFields = 0u) to emptyList<Int>(),
            original.copy(stage = BoundaryStage.MAIN_WRONG_TARGET, validFields = 1u, words = original.words.toMutableList().apply { this[0] = 999uL }) to listOf(0),
            original.copy(stage = BoundaryStage.MAIN_NO_INFLIGHT, validFields = 17u, words = original.words.toMutableList().apply { this[4] = 0uL }) to listOf(0, 4),
            original.copy(stage = BoundaryStage.MAIN_CAPTURE_DISABLED, validFields = 19u, words = original.words.toMutableList().apply { this[1] = 0uL }) to listOf(0, 1, 4),
            original.copy(stage = BoundaryStage.MAIN_DETACHED, validFields = 23u, words = original.words.toMutableList().apply { this[2] = 0uL }) to listOf(0, 1, 2, 4),
            original.copy(stage = BoundaryStage.MAIN_NO_TASK, validFields = 31u) to listOf(0, 1, 2, 3, 4),
            original.copy(stage = BoundaryStage.MAIN_NO_STORAGE, validFields = 31u) to listOf(0, 1, 2, 3, 4),
            original.copy(stage = BoundaryStage.MAIN_INACTIVE, validFields = 95u, invocation = 0uL) to listOf(0, 1, 2, 3, 4, 6),
            original.copy(stage = BoundaryStage.MAIN_STALE, words = original.words.toMutableList().apply { this[3] = 2uL }) to listOf(0, 1, 2, 3, 4, 6, 7, 8, 9),
        )

        fun joined(row: BoundaryDiagnosticRecord): DiagnosticJoin = TierEA4DiagnosticSupport.joinObservedCalls(calls(), k.copy(records = k.records.map { if (it === original) row else it }), trace())
        for ((valid, bits) in cases) {
            assertEquals(valid.stage, assertIs<DiagnosticOutcome.LocalizedBoundary>(joined(valid).outcome).stage)
            for (bit in bits) {
                val tampered = valid.copy(validFields = valid.validFields and (1u shl bit).inv())
                assertIs<DiagnosticOutcome.Incomplete>(joined(tampered).outcome, "${valid.stage} missing bit=$bit")
            }
            val contradictory = bits.mapNotNull { bit ->
                when (bit) {
                    0 -> valid.copy(words = valid.words.toMutableList().apply { this[0] = if (valid.stage == BoundaryStage.MAIN_WRONG_TARGET) 1000uL else 999uL })
                    1, 2, 4 -> valid.copy(words = valid.words.toMutableList().apply { this[bit] = if (this[bit] == 0uL) 1uL else 0uL })
                    3 -> valid.copy(words = valid.words.toMutableList().apply { this[3] = if (valid.stage == BoundaryStage.MAIN_STALE) valid.generation.toULong() else 0uL })
                    6 -> valid.copy(invocation = if (valid.stage == BoundaryStage.MAIN_INACTIVE) 1uL else 0uL)
                    7 -> valid.copy(sequence = 0uL)
                    8 -> valid.copy(generation = valid.words[3].toUInt())
                    9 -> null // Flags are observed, not a rejecting branch predicate.
                    else -> error("unexpected bit")
                }
            }
            for (tampered in contradictory) {
                val result = joined(tampered)
                assertIs<DiagnosticOutcome.Incomplete>(result.outcome, "${valid.stage} contradictory=$tampered")
                assertEquals(40, result.matched.size)
            }
        }
    }

    private fun productionEvidence(localized: Boolean): Pair<List<String>, String> {
        val events = production(
            calls()
            .coldMethods.keys
            .sorted()
            .filter { !localized || it != 1L },
        ).mapIndexed { index, row ->
            row.dropLast(1) + """, "schema_version":1,"registration_id":"orders","session_epoch":"19802","ordinal":"${index + 1}","host_tgid":"1000"}"""
        } +
            """{"type":"event","schema_version":1,"registration_id":"orders","session_epoch":"19802","ordinal":"${if (localized) 40 else 41}","host_tgid":"1000","capture_flags":32,"resolution_status":"NO_ACTIVE_INVOCATION"}"""
        val header = """
            {"type":"header","schema_version":1,"registration_id":"orders","session_epoch":"19802",
            "target_pid":1000,"process_incarnation":"77","clock_domain":"CLOCK_MONOTONIC",
            "observation_scope":"SYS_ENTER_TRACEPOINT","marker_version":"v1",
            "stack_transport":"session-bound-mzse-v2","policy_eligibility":false}
        """.trimIndent().replace("\n", "")
        val footer = """
            {"type":"terminal","schema_version":1,"registration_id":"orders","session_epoch":"19802",
            "event_count":"${events.size}","attributed_count":"${events.size - 1}","unattributed_count":"1",
            "ambient_inactive_count":"1","capture_admitted_count":"${events.size}","capture_submitted_count":"${events.size}",
            "observed_loss_count":"0","marker_state_unavailable_count":"0","capture_complete":true,
            "transport_complete":true,"export_complete":true,"attribution_complete":false,
            "terminal_reason":"COMPLETE","policy_eligibility":false}
        """.trimIndent().replace("\n", "")
        val dictionary = Files.writeString(directory.resolve("dictionary"), "closed dictionary")
        val terminal = """{"schema_version":1,"registration_id":"orders","session_epoch":19802,"target_pid":1000,"process_incarnation":77,"artifact_byte_length":${Files.size(
            dictionary,
        )},"artifact_sha256":"${TierEA4DiagnosticSupport.sha256(
            dictionary,
        )}","resolved_syscalls":${events.size - 1},"capture_domain_validity":true,"transport_completeness":true,"dictionary_integrity":true,"lifecycle_validity":true,"attribution_coverage":false,"evidence_integrity":"INCOMPLETE","terminal_reason":"COMPLETE","policy_eligibility":false,"failed_dimensions":["attribution_coverage"]}"""
        return (listOf(header) + events + footer) to terminal
    }

    @Test
    fun retainedProductionDefectsInvalidateBothCandidatesAndPreserveOriginalError() {
        for (localized in listOf(false, true)) {
            val (rows, terminal) = productionEvidence(localized)
            val k = productionKernel(localized)

            fun classify(
                lines: List<String>,
                report: String,
            ): DiagnosticJoin = TierEA4DiagnosticSupport.classifyRetainedProduction(calls(), k, trace(), calls().coldMethods, lines, report, 1000, 77, directory.resolve("dictionary"))
            val valid = classify(rows, terminal)
            if (localized) assertIs<DiagnosticOutcome.LocalizedBoundary>(valid.outcome) else assertIs<DiagnosticOutcome.InconclusiveBaseline>(valid.outcome)
            val defects = listOf(
                rows.dropLast(1) + rows.last().replace("\"observed_loss_count\":\"0\"", "\"observed_loss_count\":\"1\"") to terminal,
                rows.dropLast(1) + rows.last().replace("\"transport_complete\":true", "\"transport_complete\":false") to terminal,
                rows.dropLast(1) + rows.last().replace("\"export_complete\":true", "\"export_complete\":false") to terminal,
                rows.dropLast(1) + rows.last().replace("\"event_count\":\"${rows.size - 2}\"", "\"event_count\":\"999\"") to terminal,
                rows.dropLast(1) + rows.last().replace("\"capture_admitted_count\":\"${rows.size - 2}\"", "\"capture_admitted_count\":null") to terminal,
                rows.dropLast(1) to terminal,
                rows.drop(1) to terminal,
                rows.dropLast(1) + "{broken-footer" to terminal,
                rows to terminal.replace("\"target_pid\":1000", "\"target_pid\":1001"),
                rows to terminal.replace("\"process_incarnation\":77", "\"process_incarnation\":78"),
                rows to terminal.replace("\"lifecycle_validity\":true", "\"lifecycle_validity\":false"),
                rows to terminal.replace("\"artifact_byte_length\":17", "\"artifact_byte_length\":0"),
                rows to terminal.replace(TierEA4DiagnosticSupport.sha256(directory.resolve("dictionary")), "0".repeat(64)),
            )
            for ((lines, report) in defects) {
                val original = AssertionError("original count/integrity assertion")
                var published: DiagnosticJoin? = null
                kotlin.test.assertSame(
                    original,
                    assertFailsWith<AssertionError> {
                    TierEA4DiagnosticSupport.finishDiagnosticAttempt(
                        exercise = { throw original },
                        quiesce = {},
                        retain = {},
                        classify = { classify(lines, report) },
                        publish = { result, _ -> published = result },
                    )
                },
                )
                val result = requireNotNull(published)
                assertIs<DiagnosticOutcome.Incomplete>(result.outcome, "defect=$lines $report")
                assertEquals(40, result.matched.size)
                assertTrue(original.suppressed.isNotEmpty())
            }
        }
    }

    private fun productionKernel(
        localized: Boolean,
        mixed: Boolean = false,
    ): BoundaryDiagnosticSnapshot {
        val k = kernel()
        val inactive = k.records.first { it.plane == 2 }.copy(rawId = 1uL, invocation = 0uL, stage = BoundaryStage.MAIN_INACTIVE, validFields = 95u)
        val rows = k.records.filterNot { localized && it.plane == 2 && it.invocation == 1uL } + inactive
        return withRows(k, rows + if (mixed) listOf(inactive.copy(stage = BoundaryStage.MAIN_NO_STORAGE, validFields = 31u)) else emptyList())
    }

    @Test
    fun mixedInactiveAndNoStorageProductionReconcilesOnlyActualAmbientReceipts() {
        for (localized in listOf(false, true)) {
            val (original, report) = productionEvidence(localized)
            val extra = original[original.lastIndex - 1]
                .replace("\"capture_flags\":32", "\"capture_flags\":4")
                .replace("\"ordinal\":\"${original.size - 2}\"", "\"ordinal\":\"${original.size - 1}\"")
            val footer = original
                .last()
                .replace("\"unattributed_count\":\"1\"", "\"unattributed_count\":\"2\"")
                .replace("\"event_count\":\"${original.size - 2}\"", "\"event_count\":\"${original.size - 1}\"")
                .replace("\"capture_admitted_count\":\"${original.size - 2}\"", "\"capture_admitted_count\":\"${original.size - 1}\"")
                .replace("\"capture_submitted_count\":\"${original.size - 2}\"", "\"capture_submitted_count\":\"${original.size - 1}\"")
            val rows = original.dropLast(1) + extra + footer
            val k = productionKernel(localized, mixed = true)

            fun classify(
                lines: List<String> = rows,
                evidence: BoundaryDiagnosticSnapshot = k,
            ): DiagnosticJoin = TierEA4DiagnosticSupport.classifyRetainedProduction(calls(), evidence, trace(), calls().coldMethods, lines, report, 1000, 77, directory.resolve("dictionary"))
            val valid = classify()
            assertEquals(40, valid.matched.size)
            if (localized) assertIs<DiagnosticOutcome.LocalizedBoundary>(valid.outcome) else assertIs<DiagnosticOutcome.InconclusiveBaseline>(valid.outcome)
            val badRows = listOf(
                rows.map { it.replace("\"ambient_inactive_count\":\"1\"", "\"ambient_inactive_count\":\"2\"") },
                rows.map { it.replace("\"capture_flags\":32", "\"capture_flags\":4") },
                rows.map { it.replace("\"capture_flags\":32,", "") },
                rows.map { it.replace("\"capture_flags\":32", "\"capture_flags\":null") },
                rows.map { it.replace("\"capture_flags\":32", "\"capture_flags\":\"bad\"") },
                rows.map { it.replace("\"capture_flags\":32", "\"capture_flags\":\"32\"") },
                rows.map { it.replace("\"capture_flags\":32", "\"capture_flags\":-1") },
                rows.map { it.replace("\"capture_flags\":32", "\"capture_flags\":36") },
                rows.map { it.replace("\"capture_flags\":0,", "") },
                rows.map { it.replace("\"capture_flags\":32", "\"capture_flags\":0") },
                rows.map { it.replace("\"capture_flags\":0", "\"capture_flags\":32") },
            )
            for (bad in badRows) {
                val result = classify(bad)
                assertIs<DiagnosticOutcome.Incomplete>(result.outcome)
                assertEquals(40, result.matched.size)
            }
            val inactive = k.records.single { it.stage == BoundaryStage.MAIN_INACTIVE }
            for (bad in listOf(
                withRows(k, k.records - inactive),
                withRows(k, k.records + inactive),
                withRows(k, k.records.map { if (it === inactive) it.copy(validFields = 31u) else it }),
                withRows(k, k.records.map { if (it === inactive) it.copy(invocation = 1uL) else it }),
                k.copy(producerClosed = false),
                k.copy(session = 19803),
                k.copy(targetTgid = 1001),
            )) {
                val result = classify(evidence = bad)
                assertIs<DiagnosticOutcome.Incomplete>(result.outcome)
                assertEquals(40, result.matched.size)
            }
        }
    }

    // Independent G2 literals: never derive fixture membership or semantics from the validator.
    private val fixtureWorkspace = ".superpowers/sdd/2026-11-03-tier-e-improvement-roadmap"
    private val fixtureNative = "$fixtureWorkspace/a4-yield-diagnostic-native-001"
    private val fixtureOwned = listOf(
        "CMakeLists.txt",
        "src/agent.cpp",
        "src/live_stack_context.cpp",
        "src/live_stack_jni.cpp",
        "include/binding_manifest.hpp",
        "include/capture_profile.hpp",
        "include/capture_timing_diagnostic.hpp",
        "include/capture_yield_recorder.hpp",
        "include/invocation_fd_recorder.hpp",
        "include/invocation_registry.hpp",
        "include/open_boundary_recorder.hpp",
        "include/lifecycle_barrier.hpp",
        "include/live_stack_context.hpp",
        "include/live_stack_context_abi.h",
        "include/live_stack_jni.hpp",
        "include/marker_version_proof.hpp",
        "include/symbolization_generation.hpp",
        "include/tier_e_usdt_marker.hpp",
        "tests/open_boundary_recorder_test.cpp",
        "tests/capture_yield_recorder_test.cpp",
        "tests/capture_yield_agent_test.cpp",
        "tests/capture_yield_file_test.cpp",
        "tests/marker_export_test.cpp",
        "tests/marker_version_proof_test.cpp",
        "tests/usdt_marker_note_test.cpp",
        "tests/AgentWorkload.java",
        "tests/OpenatProfileWorkload.java",
        "tests/AgentCoverageWorkload.java",
        "tests/NativeOutcomeWorkload.java",
        "tests/InvocationFdWorkload.java",
        "tests/TierEAllTargetWorkload.java",
        "tests/OpenBoundaryWorkload.java",
    ).map { "profiler-native/$it" }
    private val fixtureTargets = listOf(
        "mazewall_stack_agent",
        "open_boundary_recorder_test",
        "capture_yield_recorder_test",
        "capture_yield_agent_test",
        "capture_yield_file_test",
        "marker_export_test",
        "marker_version_proof_test",
        "usdt_marker_note_test",
    )
    private val fixtureGenerated = listOf(
        "CMakeCache.txt",
        "CMakeFiles/4.2.3/CMakeCXXCompiler.cmake",
        "CMakeFiles/mazewall_stack_agent.dir/flags.make",
        "CMakeFiles/mazewall_stack_agent.dir/build.make",
        "CMakeFiles/mazewall_stack_agent.dir/link.txt",
        "CMakeFiles/agent_workload_test_classes.dir/build.make",
        "CTestTestfile.cmake",
    ) +
        fixtureTargets.drop(1).flatMap { target -> listOf("flags.make", "build.make", "link.txt").map { "CMakeFiles/$target.dir/$it" } }
    private val fixtureReceiptNames = listOf("configure", "build", "ctest", "source-before", "dependencies", "hashes")
        .map { "$fixtureWorkspace/a4-yield-diagnostic-001-$it.log" }
    private val fixtureMembers get() = fixtureOwned +
        listOf("$fixtureNative/libmazewall_stack_agent.so", "$fixtureNative/agent-test-classes/TierEAllTargetWorkload.class") +
        fixtureGenerated.map { "$fixtureNative/$it" } +
        fixtureReceiptNames.dropLast(1)
    private val fixtureCopies get() = (fixtureReceiptNames + fixtureGenerated.map { "$fixtureNative/$it" }).mapIndexed { index, name ->
        name to
        "native-evidence-${index.toString().padStart(3, '0')}-${Path.of(name).fileName}"
    }
    private val fixtureCases =
        listOf(
            "overlap",
            "close-error",
            "clean",
            "unload",
            "hole",
            "stale-close",
            "stale-full",
            "overflow",
            "errno",
            "last-slot",
            "disabled",
            "zero",
            "open-error",
            "flush-error",
            "rename-error",
            "allocation-error",
            "full",
        )
    private val fixtureTests = listOf(
        "open_boundary_recorder_test",
        "open_boundary_export_test",
        "capture_yield_recorder_test",
        "capture_yield_file_test",
        "agent_capture_yield_sidecar",
        "agent_native_outcome_preservation",
        "agent_smoke_test",
        "agent_virtual_smoke_test",
        "agent_open_boundary_enabled",
        "agent_open_boundary_disabled",
        "marker_export_test",
        "marker_version_proof_test",
        "usdt_marker_note_test",
    ) +
        fixtureCases.map { "capture_yield_agent_$it" }

    private fun rehashFixture(repository: Path) {
        Files.writeString(repository.resolve(fixtureReceiptNames.last()), fixtureMembers.joinToString("\n") { "${TierEA4DiagnosticSupport.sha256(repository.resolve(it))}  $it" } + "\n")
    }

    private fun directNativeFixture(): Pair<Path, Path> {
        val repository = Files.createTempDirectory(directory, "repo-")
        val native = repository.resolve(fixtureNative)

        fun write(
            name: String,
            text: String,
        ) {
            Files.createDirectories(repository.resolve(name).parent)
            Files.writeString(repository.resolve(name), text)
        }
        for (name in fixtureOwned) write(name, name)
        val compiler = Files.writeString(repository.resolve("c++"), "compiler")
        check(compiler.toFile().setExecutable(true))
        write(
            "$fixtureNative/CMakeCache.txt",
            """
            CMAKE_HOME_DIRECTORY:INTERNAL=${repository.resolve("profiler-native")}
            CMAKE_CACHEFILE_DIR:INTERNAL=$native
            CMAKE_BUILD_TYPE:STRING=Release
            CMAKE_CXX_COMPILER:FILEPATH=$compiler
            CMAKE_CXX_FLAGS:STRING=
            CMAKE_CXX_FLAGS_RELEASE:STRING=-O3 -DNDEBUG
            Java_JAVAC_EXECUTABLE:FILEPATH=$compiler
            Java_JAVA_EXECUTABLE:FILEPATH=/frozen-jdk/bin/java
            MAZEWALL_ENABLE_TIER_E_USDT:BOOL=OFF
            CMAKE_CACHE_MAJOR_VERSION:INTERNAL=4
            CMAKE_CACHE_MINOR_VERSION:INTERNAL=2
            CMAKE_CACHE_PATCH_VERSION:INTERNAL=3
        """.trimIndent(),
        )
        write("$fixtureNative/CMakeFiles/4.2.3/CMakeCXXCompiler.cmake", "set(CMAKE_CXX_COMPILER \"$compiler\")\nset(CMAKE_CXX_COMPILER_ID \"GNU\")\nset(CMAKE_CXX_COMPILER_VERSION \"15.2.0\")\n")
        for (target in fixtureTargets) {
            val production = target == "mazewall_stack_agent"
            val shared = target in listOf("mazewall_stack_agent", "open_boundary_recorder_test", "capture_yield_agent_test")
            val prefix = "CMakeFiles/$target.dir"
            val sources = if (production) {
                listOf("src/agent.cpp", "src/live_stack_context.cpp", "src/live_stack_jni.cpp")
            } else {
                listOf("tests/$target.cpp") +
                if (shared) listOf("src/live_stack_context.cpp", "src/live_stack_jni.cpp") else emptyList()
            }
            val define = if (production) {
                "-Dmazewall_stack_agent_EXPORTS"
            } else if (target == "capture_yield_agent_test") {
                "-DMAZEWALL_CAPTURE_YIELD_TEST"
            } else {
                ""
            }
            val flags = if (production) "-O3 -DNDEBUG -std=c++20 -fPIC -Wall -Wextra -Werror" else "-O3 -DNDEBUG -std=c++20 -Wall -Wextra -Werror -UNDEBUG"
            val visibility = if (shared) {
                listOf("live_stack_context", "live_stack_jni").joinToString("\n") { "# Custom options: $prefix/src/$it.cpp.o_OPTIONS = -fvisibility=hidden;-fvisibility-inlines-hidden" } +
                "\n"
            } else {
                ""
            }
            write(
                "$fixtureNative/$prefix/flags.make",
                "# compile CXX with $compiler\nCXX_DEFINES = $define\nCXX_INCLUDES = -I${repository.resolve(
                    "profiler-native/include",
                )} -I/frozen-jdk/include -I/frozen-jdk/include/linux\nCXX_FLAGS = $flags\n$visibility",
            )
            val rules = sources.joinToString("\n") { source ->
                val extra = if (source.startsWith("src/live_stack_")) " -fvisibility=hidden -fvisibility-inlines-hidden" else ""
                "$prefix/$source.o: ${repository.resolve(
                    "profiler-native/$source",
                )}\n\t$compiler \$(CXX_DEFINES) \$(CXX_INCLUDES) \$(CXX_FLAGS)$extra -MD -MT $prefix/$source.o -MF $prefix/$source.o.d -o $prefix/$source.o -c ${repository.resolve(
                    "profiler-native/$source",
                )}"
            }
            write("$fixtureNative/$prefix/build.make", rules + "\n")
            val wraps = if (target ==
                "open_boundary_recorder_test"
            ) {
                    " -Wl,--wrap=__cxa_thread_atexit -Wl,--wrap=_Znwm -Wl,--wrap=_ZnwmRKSt9nothrow_t"
                } else if (target ==
                    "capture_yield_agent_test"
                ) {
                        " -Wl,--wrap=_Znwm"
                    } else {
                        ""
                    }
            val link = if (production) "$compiler -fPIC -O3 -DNDEBUG -shared -Wl,-soname,libmazewall_stack_agent.so -o libmazewall_stack_agent.so " else "$compiler -O3 -DNDEBUG$wraps -o $target "
            write("$fixtureNative/$prefix/link.txt", link + sources.joinToString(" ") { "$prefix/$it.o" } + if (target == "marker_export_test") " -ldl\n" else "\n")
        }
        val javaSources = listOf("AgentWorkload", "OpenatProfileWorkload", "AgentCoverageWorkload", "NativeOutcomeWorkload", "InvocationFdWorkload", "TierEAllTargetWorkload", "OpenBoundaryWorkload")
        write(
            "$fixtureNative/CMakeFiles/agent_workload_test_classes.dir/build.make",
            "agent-test-classes/TierEAllTargetWorkload.class: agent-test-classes/AgentWorkload.class\n\t$compiler -d $native/agent-test-classes " +
                javaSources.joinToString(" ") { "${repository.resolve("profiler-native/tests/$it.java")}" } +
                "\n",
        )
        val otherTests = listOf(
            "open_boundary_recorder_test" to listOf("$native/open_boundary_recorder_test"),
            "open_boundary_export_test" to listOf("$native/open_boundary_recorder_test", "--exports", "$native/libmazewall_stack_agent.so"),
            "capture_yield_recorder_test" to listOf("$native/capture_yield_recorder_test"),
            "capture_yield_file_test" to listOf("$native/capture_yield_file_test", "$native/tier-e-capture-yield.mzcy"),
            "marker_export_test" to listOf("$native/marker_export_test", "$native/libmazewall_stack_agent.so"),
            "marker_version_proof_test" to listOf("$native/marker_version_proof_test"),
            "usdt_marker_note_test" to listOf("$native/usdt_marker_note_test", "$native/libmazewall_stack_agent.so", "disabled"),
            "agent_open_boundary_enabled" to listOf("/frozen-jdk/bin/java", "-cp", "$native/agent-test-classes", "OpenBoundaryWorkload", "enabled", "$native/libmazewall_stack_agent.so"),
            "agent_open_boundary_disabled" to listOf("/frozen-jdk/bin/java", "-cp", "$native/agent-test-classes", "OpenBoundaryWorkload", "disabled", "$native/libmazewall_stack_agent.so"),
            "agent_native_outcome_preservation" to
                listOf(
                    "/frozen-jdk/bin/java",
                    "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                    "-agentpath:$native/libmazewall_stack_agent.so=benchmark-phases,capture-profile=jdk25-unix-openat0,force-capture-failure-once",
                    "-cp",
                    "$native/agent-test-classes",
                    "NativeOutcomeWorkload",
                ),
            "agent_smoke_test" to listOf("/frozen-jdk/bin/java", "-agentpath:$native/libmazewall_stack_agent.so", "-cp", "$native/agent-test-classes", "AgentWorkload"),
            "agent_virtual_smoke_test" to listOf("/frozen-jdk/bin/java", "-agentpath:$native/libmazewall_stack_agent.so", "-cp", "$native/agent-test-classes", "AgentWorkload", "0", "virtual", "20"),
            "agent_capture_yield_sidecar" to
                listOf(
                    "/frozen-jdk/bin/java",
                    "-agentpath:$native/libmazewall_stack_agent.so=benchmark-phases,capture-yield=$native/tier-e-capture-yield.mzcy",
                    "-cp",
                    "$native/agent-test-classes",
                    "AgentWorkload",
                    "0",
                    "platform",
                    "10",
                ),
        ).joinToString("\n") { (name, argv) -> "add_test([=[$name]=] " + argv.joinToString(" ") { "\"$it\"" } + ")" }
        val otherProperties = """
            set_tests_properties([=[agent_native_outcome_preservation]=] PROPERTIES  PASS_REGULAR_EXPRESSION "native-outcome-workload: return=preserved exception=preserved errno=2" _BACKTRACE_TRIPLES "fixture")
            set_tests_properties([=[agent_smoke_test]=] PROPERTIES  PASS_REGULAR_EXPRESSION "proxied-binds=[1-9][0-9]* stack-captures=[1-9][0-9]*" _BACKTRACE_TRIPLES "fixture")
            set_tests_properties([=[agent_virtual_smoke_test]=] PROPERTIES  PASS_REGULAR_EXPRESSION "proxied-binds=[1-9][0-9]* stack-captures=[1-9][0-9]*" _BACKTRACE_TRIPLES "fixture")
            set_tests_properties([=[agent_capture_yield_sidecar]=] PROPERTIES  PASS_REGULAR_EXPRESSION "capture-yield=written" _BACKTRACE_TRIPLES "fixture")
            set_tests_properties([=[capture_yield_file_test]=] PROPERTIES  DEPENDS "agent_capture_yield_sidecar" _BACKTRACE_TRIPLES "fixture")
        """.trimIndent()
        write(
            "$fixtureNative/CTestTestfile.cmake",
            fixtureCases.joinToString("\n") { case ->
            val name = "capture_yield_agent_$case"
            val extra = if (case == "full") " \"$native/capture_yield_file_test\"" else ""
            val properties = if (case == "unload") " FAIL_REGULAR_EXPRESSION \"capture-yield=written\" PASS_REGULAR_EXPRESSION \"capture-yield=failed\"" else ""
            "add_test([=[$name]=] \"$native/capture_yield_agent_test\" \"$case\"$extra)\nset_tests_properties([=[$name]=] PROPERTIES $properties TIMEOUT \"30\" _BACKTRACE_TRIPLES \"fixture\")"
        } +
            "\n$otherTests\n$otherProperties\n",
        )
        write(fixtureReceiptNames[0], "-- The CXX compiler identification is GNU 15.2.0\n-- Configuring done\n-- Generating done\n-- Build files have been written to: $native\n")
        write(fixtureReceiptNames[1], "[100%] Built target agent_workload_test_classes\n[100%] Built target mazewall_stack_agent\n")
        write(fixtureReceiptNames[2], ctestReceipt())
        val escaped = compiler.toString().replace("\\", "\\\\")
        write(fixtureReceiptNames[3], "{\"external\":{\"$escaped\":{\"canonical\":\"$escaped\",\"sha256\":\"${TierEA4DiagnosticSupport.sha256(compiler)}\"}}}")
        write(fixtureReceiptNames[4], "opaque complete offline closure; never reopen these paths\n")
        write("$fixtureNative/libmazewall_stack_agent.so", "ELF fixture")
        write("$fixtureNative/agent-test-classes/TierEAllTargetWorkload.class", "compiled workload")
        rehashFixture(repository)
        return repository to native
    }

    private fun ctestReceipt(focused: Boolean = false): String {
        val names = fixtureTests + if (focused) emptyList() else listOf("agent_workload_benchmark_output")
        return "Test project /fixture/native\n" +
            names.mapIndexed { index, name -> "${index + 1}/${names.size} Test #${index + 1}: $name .................   Passed    0.19 sec" }.joinToString("\n") +
            "\n100% tests passed, 0 tests failed out of ${names.size}\nTotal Test time (real) = 10.41 sec\nEXIT_STATUS=0 ELAPSED_SECONDS=10.42\n"
    }

    @Test
    fun ctestReceiptAcceptsFullAndFocusedScopesAndRejectsDefects() {
        val (repository, native) = directNativeFixture()
        val receipt = repository.resolve(fixtureReceiptNames[2])
        val valid = ctestReceipt()
        TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        val focused = ctestReceipt(focused = true)
        Files.writeString(receipt, focused)
        rehashFixture(repository)
        TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        val defects = listOf(
            valid.replace("open_boundary_recorder_test .................   Passed", "open_boundary_recorder_test .................***Failed"),
            valid.replace("agent_open_boundary_enabled .................   Passed", "agent_open_boundary_enabled .................***Skipped"),
            valid.replace("agent_open_boundary_disabled", "agent_open_boundary_missing"),
            valid.replace("EXIT_STATUS=0", "EXIT_STATUS=1"),
            valid.replace("EXIT_STATUS=0", "EXIT_STATUS=0garbage"),
            valid.replace("100% tests passed, 0 tests failed out of 31\n", ""),
            valid.replace("out of 31", "out of 31garbage"),
            valid.replace("out of 31", "out of "),
            valid.replace("0 tests failed", "1 tests failed"),
            valid.replace("100% tests passed, 0 tests failed out of 31", "100% tests passed, 0 tests failed out of 31\n100% tests passed, 0 tests failed out of 31"),
            valid.replace("100% tests passed, 0 tests failed out of 31", "100% tests passed, 0 tests failed out of 31\n99% tests passed, 1 tests failed out of 31"),
            valid + "not terminal\n",
            valid.replace("/31", "/30"),
        )
        for (defect in defects) {
            Files.writeString(receipt, valid)
            rehashFixture(repository)
            TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
            Files.writeString(receipt, defect)
            rehashFixture(repository)
            assertTrue(
                assertFailsWith<IllegalArgumentException>(
                    defect,
                ) { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }.message.orEmpty().contains("native verification receipt missing"),
            )
        }
    }

    @Test
    fun recorderHeaderDriftIsRejectedWithUnchangedDso() {
        val (repository, native) = directNativeFixture()
        val header = repository.resolve("profiler-native/include/capture_yield_recorder.hpp")
        if (!Files.exists(header)) Files.writeString(header, "accepted recorder")
        TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        val dso = TierEA4DiagnosticSupport.sha256(native.resolve("libmazewall_stack_agent.so"))
        Files.writeString(header, "changed recorder")
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
        assertEquals(dso, TierEA4DiagnosticSupport.sha256(native.resolve("libmazewall_stack_agent.so")))
    }

    @Test
    fun sourceSpecificVisibilityIsSemanticallyRequired() {
        val (repository, native) = directNativeFixture()
        val flags = native.resolve("CMakeFiles/mazewall_stack_agent.dir/flags.make")
        TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        Files.writeString(flags, Files.readString(flags).replace("-fvisibility=hidden", "-fvisibility=default"))
        rehashFixture(repository)
        assertTrue(assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }.message.orEmpty().contains("source-specific visibility"))
    }

    @Test
    fun directCmakeProvenanceDoesNotRequireWrapperMarker() {
        val (repository, native) = directNativeFixture()
        assertFalse(Files.exists(native.resolve(".mazewall-native-identity")))
        val facts = TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        assertTrue(facts.keys.any { it.endsWith("CMakeCache.txt") })
        assertTrue(facts.keys.any { it.endsWith("TierEAllTargetWorkload.class") })
    }

    @Test
    fun invalidDirectCmakeFactsFailBeforeActors() {
        val (repository, native) = directNativeFixture()
        val cachePath = native.resolve("CMakeCache.txt")
        val cache = Files.readString(cachePath)
        for ((old, replacement) in listOf("Release" to "Debug", "BOOL=OFF" to "BOOL=ON", "CMAKE_CXX_COMPILER:FILEPATH=" to "MISSING_COMPILER:FILEPATH=", "-O3 -DNDEBUG" to "-O0")) {
            Files.writeString(cachePath, cache.replace(old, replacement))
            var actors = 0
            assertFailsWith<IllegalArgumentException> {
                TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
                actors++
            }
            assertEquals(0, actors)
        }
        Files.writeString(cachePath, cache)
        Files.writeString(native.resolve("libmazewall_stack_agent.so"), "wrong binary")
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
    }

    @Test
    fun generatedNativeAndWorkloadRulesMustAgreeWithDirectCache() {
        val (repository, native) = directNativeFixture()
        val flags = native.resolve("CMakeFiles/mazewall_stack_agent.dir/flags.make")
        val original = Files.readString(flags)
        Files.writeString(flags, original.replace("-O3", "-O0"))
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
        Files.writeString(flags, original)
        val rules = native.resolve("CMakeFiles/agent_workload_test_classes.dir/build.make")
        Files.writeString(rules, Files.readString(rules).replace("TierEAllTargetWorkload.java", "Other.java"))
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
    }

    @Test
    fun everyFixedMemberAndReceiptRejectsStaleBindingAndNonExactMembership() {
        val (repository, native) = directNativeFixture()
        val manifest = repository.resolve(fixtureReceiptNames.last())
        val originalManifest = Files.readString(manifest)
        for (name in fixtureMembers + fixtureReceiptNames.last()) {
            val path = repository.resolve(name)
            val bytes = Files.readAllBytes(path)
            val modified = Files.getLastModifiedTime(path)
            TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
            Files.writeString(path, "changed-$name")
            assertFailsWith<IllegalArgumentException>(name) { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
            Files.write(path, bytes)
            Files.setLastModifiedTime(path, modified)
            TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
            Files.delete(path)
            assertFailsWith<IllegalArgumentException>(name) { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
            Files.write(path, bytes)
            Files.setLastModifiedTime(path, modified)
            if (name == fixtureReceiptNames.last()) continue
            val row = originalManifest.lines().single { it.endsWith("  $name") }
            for (bad in listOf(originalManifest.replace("$row\n", ""), originalManifest + "$row\n", originalManifest.replace(row, "${"0".repeat(64)}  $name"))) {
                TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
                Files.writeString(manifest, bad)
                assertFailsWith<IllegalArgumentException>(name) { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
                Files.writeString(manifest, originalManifest)
            }
        }
        for (name in listOf("unexpected", "profiler-native/./src/agent.cpp", "profiler-native/src/../src/agent.cpp", repository.resolve("profiler-native/src/agent.cpp").toString())) {
            TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
            Files.writeString(manifest, originalManifest + "${"0".repeat(64)}  $name\n")
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
            Files.writeString(manifest, originalManifest)
        }
    }

    @Test
    fun hashConsistentInvalidGeneratedFactsReachTheirSemanticGuards() {
        val (repository, native) = directNativeFixture()
        val defects = mutableListOf<Triple<String, Pair<String, String>, String>>()

        fun defect(
            path: String,
            old: String,
            replacement: String,
            guard: String,
        ) {
            defects += Triple(path, old to replacement, guard)
        }
        for ((old, replacement) in listOf(
                "CMAKE_HOME_DIRECTORY:INTERNAL=${repository.resolve("profiler-native")}" to "CMAKE_HOME_DIRECTORY:INTERNAL=/wrong",
                "CMAKE_CACHEFILE_DIR:INTERNAL=$native" to "CMAKE_CACHEFILE_DIR:INTERNAL=/wrong",
                "STRING=Release" to "STRING=Debug",
                "BOOL=OFF" to "BOOL=ON",
                "CMAKE_CXX_FLAGS:STRING=" to "CMAKE_CXX_FLAGS:STRING=-fsanitize=address",
                "CMAKE_CXX_FLAGS_RELEASE:STRING=-O3 -DNDEBUG" to "CMAKE_CXX_FLAGS_RELEASE:STRING=-O0",
                "CMAKE_CACHE_MAJOR_VERSION:INTERNAL=4" to "CMAKE_CACHE_MAJOR_VERSION:INTERNAL=3",
            )) {
                defect("CMakeCache.txt", old, replacement, "invalid direct CMake")
            }
        defect("CMakeFiles/4.2.3/CMakeCXXCompiler.cmake", "GNU", "Clang", "invalid configured compiler identity")
        defect("CMakeFiles/4.2.3/CMakeCXXCompiler.cmake", "15.2.0", "14.0.0", "invalid configured compiler identity")
        defect("CMakeFiles/4.2.3/CMakeCXXCompiler.cmake", "set(CMAKE_CXX_COMPILER ", "set(WRONG_COMPILER ", "compiler/cache mismatch")
        for (target in fixtureTargets) {
            val prefix = "CMakeFiles/$target.dir"
            for (flag in listOf("-O3", "-std=c++20", "-Wall", "-Wextra", "-Werror") + if (target == "mazewall_stack_agent") listOf("-fPIC") else listOf("-UNDEBUG")) {
                defect("$prefix/flags.make", flag, "", "generated compiler/build flags")
            }
            if (target != "mazewall_stack_agent") defect("$prefix/flags.make", "-UNDEBUG", "-UNDEBUG -DNDEBUG", "generated compiler/build flags")
            if (target == "capture_yield_agent_test") defect("$prefix/flags.make", "-DMAZEWALL_CAPTURE_YIELD_TEST", "-DWRONG_TEST", "unexpected native definitions")
            if (target == "mazewall_stack_agent") {
                for (define in listOf(
                        "-DMAZEWALL_CAPTURE_YIELD_TEST",
                        "-fsanitize=address",
                        "-DMAZEWALL_ENABLE_TIER_E_USDT",
                    )) {
                        defect("$prefix/flags.make", "-Dmazewall_stack_agent_EXPORTS", "-Dmazewall_stack_agent_EXPORTS $define", "unexpected native definitions")
                    }
            }
            val source = if (target == "mazewall_stack_agent") "src/agent.cpp" else "tests/$target.cpp"
            defect("$prefix/build.make", "$source.o: ", "wrong.cpp.o: ", "generated source/object contract")
            defect("$prefix/build.make", "-c ${repository.resolve("profiler-native/$source")}", "-c /wrong.cpp", "generated compile contract")
            defect("$prefix/link.txt", "$source.o", "wrong.cpp.o", "generated link contract")
            if (target in listOf("mazewall_stack_agent", "open_boundary_recorder_test", "capture_yield_agent_test")) {
                for (unit in listOf(
                        "live_stack_context",
                        "live_stack_jni",
                    )) {
                        defect(
                            "$prefix/flags.make",
                            "$unit.cpp.o_OPTIONS = -fvisibility=hidden;-fvisibility-inlines-hidden",
                            "$unit.cpp.o_OPTIONS = -fvisibility=default",
                            "source-specific visibility",
                        )
                    }
                defect("$prefix/flags.make", "-I/frozen-jdk/include/linux", "", "missing JNI include")
                defect("$prefix/build.make", "-fvisibility=hidden -fvisibility-inlines-hidden", "", "generated compile contract")
            }
            if (target in listOf("open_boundary_recorder_test", "capture_yield_agent_test")) defect("$prefix/link.txt", "-Wl,--wrap=_Znwm ", "", "allocation/link wrappers")
        }
        for (old in listOf(
                "PASS_REGULAR_EXPRESSION \"capture-yield=failed\"",
                "FAIL_REGULAR_EXPRESSION \"capture-yield=written\"",
            )) {
                defect("CTestTestfile.cmake", old, "", "failed-unload PASS/FAIL properties")
            }
        for (case in fixtureCases) {
            defect("CTestTestfile.cmake", "\"$case\"", "\"wrong-$case\"", "CTest registration")
        }
        defect(
            "CTestTestfile.cmake",
            "\"$native/usdt_marker_note_test\" \"$native/libmazewall_stack_agent.so\" \"disabled\")",
            "\"$native/usdt_marker_note_test\" \"$native/libmazewall_stack_agent.so\" \"enabled\")",
            "CTest registration: usdt_marker_note_test",
        )
        val compiler = repository.resolve("c++")
        for ((old, replacement) in listOf(
                "$compiler -d " to "/wrong/javac -d ",
                "$native/agent-test-classes " to "/wrong/output ",
                "TierEAllTargetWorkload.java" to "Wrong.java",
            )) {
                defect("CMakeFiles/agent_workload_test_classes.dir/build.make", old, replacement, "generated workload rule")
            }
        for ((name, change, guard) in defects) {
            val path = native.resolve(name)
            val original = Files.readString(path)
            require(original.contains(change.first)) { "fixture defect did not change $name: $change" }
            TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
            Files.writeString(path, original.replace(change.first, change.second))
            rehashFixture(repository)
            val failure = assertFailsWith<IllegalArgumentException>("$name $change") { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
            assertTrue(failure.message.orEmpty().contains(guard), "$name $change expected=$guard actual=${failure.message}")
            Files.writeString(path, original)
            rehashFixture(repository)
        }
        val configure = repository.resolve(fixtureReceiptNames[0])
        val original = Files.readString(configure)
        for (fact in listOf("GNU 15.2.0", "-- Configuring done", "-- Generating done", "-- Build files have been written to: $native")) {
            TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
            Files.writeString(configure, original.replace(fact, "wrong"))
            rehashFixture(repository)
            assertTrue(assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }.message.orEmpty().contains("invalid direct configure receipt"))
            Files.writeString(configure, original)
            rehashFixture(repository)
        }
        val workload = native.resolve("agent-test-classes/TierEAllTargetWorkload.class")
        Files.setLastModifiedTime(
            workload,
            java.nio.file.attribute.FileTime
            .fromMillis(1),
        )
        rehashFixture(repository)
        assertTrue(assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }.message.orEmpty().contains("workload class predates source"))
    }

    @Test
    fun everyRequiredOwnedIncludeMustBeTheCompleteArgument() {
        val (repository, native) = directNativeFixture()
        val targets = listOf(
            "mazewall_stack_agent",
            "open_boundary_recorder_test",
            "capture_yield_recorder_test",
            "capture_yield_agent_test",
            "capture_yield_file_test",
            "marker_version_proof_test",
        )
        assertAll(
            "exact owned include",
            targets.map { target ->
                Executable {
                val path = native.resolve("CMakeFiles/$target.dir/flags.make")
                val original = Files.readString(path)
                val include = "-I${repository.resolve("profiler-native/include")}"
                require(original.contains("$include "))
                TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
                try {
                    Files.writeString(path, original.replace("$include ", "$include-other "))
                    rehashFixture(repository)
                    val failure = assertFailsWith<IllegalArgumentException>(target) { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
                    assertEquals("missing owned include: $target", failure.message)
                } finally {
                    Files.writeString(path, original)
                    rehashFixture(repository)
                }
                }
            },
        )
    }

    @Test
    fun ownedIncludeDriftWithoutRehashStillFailsBinding() {
        val (repository, native) = directNativeFixture()
        val path = native.resolve("CMakeFiles/mazewall_stack_agent.dir/flags.make")
        TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        Files.writeString(path, Files.readString(path).replace("profiler-native/include ", "profiler-native/include-other "))
        val failure = assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
        assertEquals("native provenance changed: $fixtureNative/CMakeFiles/mazewall_stack_agent.dir/flags.make", failure.message)
    }

    @Test
    fun eachRequiredCtestNameIsRequiredIndependentlyOfReceiptStructure() {
        val (repository, native) = directNativeFixture()
        val path = repository.resolve(fixtureReceiptNames[2])
        assertAll(
            "required receipt names",
            listOf(false, true).flatMap { focused ->
            fixtureTests.mapIndexed { index, name ->
                Executable {
                    val valid = ctestReceipt(focused)
                    Files.writeString(path, valid)
                    rehashFixture(repository)
                    TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
                    try {
                        Files.writeString(path, valid.replace(": $name ", ": unrelated_receipt_case_$index "))
                        rehashFixture(repository)
                        val failure = assertFailsWith<IllegalArgumentException>("focused=$focused $name") { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
                        assertEquals("native verification receipt missing", failure.message)
                    } finally {
                        Files.writeString(path, valid)
                        rehashFixture(repository)
                    }
                }
            }
        },
        )
    }

    @Test
    fun eachRequiredCtestMustActuallyPassExactlyOnceWithConsistentHashes() {
        val (repository, native) = directNativeFixture()
        val path = repository.resolve(fixtureReceiptNames[2])
        val original = Files.readString(path)
        for (name in fixtureTests) {
            val row = original.lines().single { it.contains(": $name ") }
            for (bad in listOf(
                    original.replace("$row\n", ""),
                    original.replace(row, row.replace("Passed", "***Skipped")),
                    original.replace(row, row.replace("Passed", "***Failed")),
                    original.replace(row, "$row\n$row"),
                )) {
                TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
                Files.writeString(path, bad)
                rehashFixture(repository)
                assertTrue(
                    assertFailsWith<IllegalArgumentException>(
                        name,
                    ) { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }.message.orEmpty().contains("native verification receipt missing"),
                )
                Files.writeString(path, original)
                rehashFixture(repository)
            }
        }
        Files.writeString(
            path,
            "1/3 Test #1: open_boundary_recorder_test ... Passed 0.01 sec\n2/3 Test #2: agent_open_boundary_enabled ... Passed 0.01 sec\n3/3 Test #3: agent_open_boundary_disabled ... Passed 0.01 sec\n100% tests passed, 0 tests failed out of 3\nEXIT_STATUS=0\n",
        )
        rehashFixture(repository)
        assertTrue(assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }.message.orEmpty().contains("native verification receipt missing"))
    }

    private fun prepareReadyEvidence(
        repository: Path,
        native: Path,
        destination: Path,
    ) {
        TierEA4DiagnosticSupport.copyNativeProvenance(repository, native, destination)
        for (name in listOf("native-build-settings.txt", "native-compiler-version.txt", "runtime-fingerprints.txt", "ready-control.txt")) Files.writeString(destination.resolve(name), "checked-$name")
        Files.writeString(
            destination.resolve("source-sha256.txt"),
            TierEA4DiagnosticSupport.diagnosticSources.joinToString("\n") { "${TierEA4DiagnosticSupport.sha256(repository.resolve(it))}  $it" } + "\n",
        )
    }

    @Test
    fun finalLockedReadyRejectsEveryNativeAndRetainedCopyMutationAndConsumesAttempt() {
        val (repository, _, identities) = readyFixture()
        val native = repository.resolve(fixtureNative)
        val input = Files.writeString(directory.resolve("input"), "example")
        val mutations = (fixtureMembers + fixtureReceiptNames.last()).map { it to false } + fixtureCopies.map { it.second to true } + listOf("c++" to false)
        for ((index, mutation) in mutations.withIndex()) {
            val (name, copy) = mutation
            val destination = directory.resolve("freeze-$index")
            var gates = 0
            TierEA4DiagnosticSupport.withPreparedAttempt(destination) { attempt ->
                prepareReadyEvidence(repository, native, destination)
                TierEA4DiagnosticSupport.checkNativeProvenance(repository, native, destination)
                val path = (if (copy) destination else repository).resolve(name)
                val bytes = Files.readAllBytes(path)
                val modified = Files.getLastModifiedTime(path)
                Files.writeString(path, "changed after fingerprint: $name")
                assertFailsWith<Exception>(name) { TierEA4DiagnosticSupport.publishReadyAndArm(attempt, healthy, repository, native, destination, input, identities, { true }) { gates++ } }
                assertFailsWith<IllegalStateException> { attempt.armOnce(healthy, { true }) { gates++ } }
                assertEquals("PREPARED\n", Files.readString(destination.resolve("attempt.state")))
                assertFalse(Files.exists(directory.resolve("a4-discriminator-ready.json")))
                Files.write(path, bytes)
                Files.setLastModifiedTime(path, modified)
            }
            assertEquals(0, gates)
        }
    }

    @Test
    fun exclusivePrivateCopyPreservesPreexistingOutputsAndRefusesInvalidInputs() {
        val (repository, native) = directNativeFixture()
        TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        val firstOutput = fixtureCopies.first().second
        for (kind in listOf("file", "symlink", "directory")) {
            val destination = Files.createDirectory(directory.resolve("copy-$kind"))
            val output = destination.resolve(firstOutput)
            when (kind) {
                "file" -> Files.writeString(output, "existing")
                "symlink" -> Files.createSymbolicLink(output, repository.resolve(fixtureReceiptNames.first()))
                "directory" -> Files.createDirectory(output)
            }
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { TierEA4DiagnosticSupport.copyNativeProvenance(repository, native, destination) }
            if (kind == "file") assertEquals("existing", Files.readString(output))
            if (kind == "symlink") assertTrue(Files.isSymbolicLink(output))
            if (kind == "directory") assertTrue(Files.isDirectory(output))
        }
        for (kind in listOf("missing", "symlink", "directory")) {
            val path = repository.resolve(fixtureReceiptNames.first())
            val bytes = Files.readAllBytes(path)
            TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
            Files.delete(path)
            when (kind) {
                "symlink" -> Files.createSymbolicLink(path, repository.resolve(fixtureReceiptNames[1]))
                "directory" -> Files.createDirectory(path)
            }
            val destination = Files.createDirectory(directory.resolve("input-$kind"))
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.copyNativeProvenance(repository, native, destination) }
            if (kind != "missing") Files.delete(path)
            Files.write(path, bytes)
        }
        val destination = Files.createDirectory(directory.resolve("copy-success"))
        TierEA4DiagnosticSupport.copyNativeProvenance(repository, native, destination)
        for ((name, output) in fixtureCopies) {
            assertEquals(TierEA4DiagnosticSupport.sha256(repository.resolve(name)), TierEA4DiagnosticSupport.sha256(destination.resolve(output)))
            assertEquals(
                "rw-------",
                java.nio.file.attribute.PosixFilePermissions
                .toString(Files.getPosixFilePermissions(destination.resolve(output))),
            )
        }
        TierEA4DiagnosticSupport.checkNativeProvenance(repository, native, destination)
    }

    @Test
    fun retainedGeneratedCopyDriftIsRejectedBySharedCheck() {
        val (repository, native) = directNativeFixture()
        val destination = Files.createDirectory(directory.resolve("retained-copy"))
        TierEA4DiagnosticSupport.copyNativeProvenance(repository, native, destination)
        TierEA4DiagnosticSupport.checkNativeProvenance(repository, native, destination)
        Files.writeString(destination.resolve(fixtureCopies.last().second), "changed copy only")
        assertTrue(
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.checkNativeProvenance(repository, native, destination) }.message.orEmpty().contains("retained native copy changed"),
        )
    }

    @Test
    fun provenanceCopyWriteFailureKeepsOriginalExceptionAndPartialEvidence() {
        val (repository, native) = directNativeFixture()
        val destination = Files.createDirectory(directory.resolve("write-refused"))
        Files.setPosixFilePermissions(
            destination,
            java.nio.file.attribute.PosixFilePermissions
            .fromString("r-x------"),
        )
        assertFailsWith<java.nio.file.AccessDeniedException> { TierEA4DiagnosticSupport.copyNativeProvenance(repository, native, destination) }
        assertFalse(Files.exists(destination.resolve("native-build-provenance.txt")))
        Files.setPosixFilePermissions(
            destination,
            java.nio.file.attribute.PosixFilePermissions
            .fromString("rwx------"),
        )
        val occupied = destination.resolve(fixtureCopies[1].second)
        Files.writeString(occupied, "preexisting second copy")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { TierEA4DiagnosticSupport.copyNativeProvenance(repository, native, destination) }
        assertEquals("preexisting second copy", Files.readString(occupied))
        assertEquals(Files.readString(repository.resolve(fixtureCopies.first().first)), Files.readString(destination.resolve(fixtureCopies.first().second)))
        assertFalse(Files.exists(destination.resolve("native-build-provenance.txt")))
    }

    @Test
    fun fixedNativePathsRefuseAliasesAndOwnedAncestorSymlinks() {
        val (repository, native) = directNativeFixture()
        TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        for (wrong in listOf(native.parent.resolve("a4-discriminator-native"), native.parent.resolve("arbitrary"), native.resolve("../a4-yield-diagnostic-native-001"))) {
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, wrong) }
        }
        val nativeAlias = Files.createSymbolicLink(native.resolveSibling("native-alias"), native)
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, nativeAlias) }
        val alias = Files.createSymbolicLink(directory.resolve("repo-alias"), repository)
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(alias, alias.resolve(fixtureNative)) }
        val include = repository.resolve("profiler-native/include")
        val real = include.resolveSibling("real-include")
        Files.move(include, real)
        Files.createSymbolicLink(include, real)
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }
    }

    @Test
    fun compilerSymlinkIsAllowedButEqualByteRetargetCannotPassFinalReady() {
        val (repository, _, identities) = readyFixture()
        val native = repository.resolve(fixtureNative)
        val compiler = repository.resolve("c++")
        val symlink = Files.createSymbolicLink(repository.resolve("compiler-link"), compiler)
        for (name in fixtureGenerated) {
            val path = native.resolve(name)
            Files.writeString(path, Files.readString(path).replace(compiler.toString(), symlink.toString()))
        }
        Files.writeString(repository.resolve(fixtureReceiptNames[3]), "{\"external\":{\"$symlink\":{\"canonical\":\"$compiler\",\"sha256\":\"${TierEA4DiagnosticSupport.sha256(compiler)}\"}}}")
        rehashFixture(repository)
        val facts = TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
        assertTrue("compiler:$symlink" in facts)
        assertTrue("compiler-target:$compiler" in facts)
        val input = Files.writeString(directory.resolve("input"), "example")
        val destination = directory.resolve("compiler-attempt")
        var gates = 0
        TierEA4DiagnosticSupport.withPreparedAttempt(destination) { attempt ->
            prepareReadyEvidence(repository, native, destination)
            val equal = Files.copy(compiler, repository.resolve("equal-compiler"))
            check(equal.toFile().setExecutable(true))
            Files.delete(symlink)
            Files.createSymbolicLink(symlink, equal)
            assertTrue(
                assertFailsWith<IllegalArgumentException> {
                TierEA4DiagnosticSupport.publishReadyAndArm(attempt, healthy, repository, native, destination, input, identities, {
                    true
                }) { gates++ }
            }.message.orEmpty().contains("compiler differs from frozen toolchain"),
            )
            assertFailsWith<IllegalStateException> { attempt.armOnce(healthy, { true }) { gates++ } }
            assertFalse(Files.exists(directory.resolve("a4-discriminator-ready.json")))
        }
        assertEquals(0, gates)
    }

    @Test
    fun compilerRolesRejectMissingNonExecutableRelativeAndNonNormalizedPaths() {
        val (repository, native) = directNativeFixture()
        val cache = native.resolve("CMakeCache.txt")
        val original = Files.readString(cache)
        val compiler = repository.resolve("c++")
        val nonExecutable = Files.writeString(repository.resolve("not-executable"), "compiler")
        for (field in listOf("CMAKE_CXX_COMPILER", "Java_JAVAC_EXECUTABLE")) {
            for (bad in listOf("relative-compiler", "$repository/./c++", "$repository//c++", "$compiler/", repository.resolve("absent").toString(), nonExecutable.toString())) {
            TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native)
            Files.writeString(cache, original.replace("$field:FILEPATH=$compiler", "$field:FILEPATH=$bad"))
            rehashFixture(repository)
            assertTrue(assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.validatePrebuiltNative(repository, native) }.message.orEmpty().contains("compiler unavailable"))
            Files.writeString(cache, original)
            rehashFixture(repository)
        }
        }
    }

    @Test
    fun freshAttemptPreparationIsPrivateAndCannotBeReused() {
        for (existing in listOf(false, true)) {
            val destination = directory.resolve("prepared-$existing")
            if (existing) Files.createDirectory(destination)
            TierEA4DiagnosticSupport.withPreparedAttempt(destination) {
                assertEquals("PREPARED\n", Files.readString(destination.resolve("attempt.state")))
                assertEquals(
                    "rwx------",
                    java.nio.file.attribute.PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(destination)),
                )
                assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(destination.resolve("attempt.state"))),
                )
            }
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.withPreparedAttempt(destination) { error("reused") } }
            assertFalse(Files.exists(destination.resolve("diagnostic-outcome.txt")))
        }
    }

    @Test
    fun preparationReportingPreservesFailureWithoutWritingRefusedDirectories() {
        val unrelated = Files.createDirectory(directory.resolve("unrelated"))
        Files.writeString(unrelated.resolve("keep"), "untouched")
        val permissions = Files.getPosixFilePermissions(unrelated)
        val link = Files.createSymbolicLink(directory.resolve("link"), unrelated)
        for (refused in listOf(unrelated, link)) {
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.withPreparedAttempt(refused) { error("refused") } }
            assertFalse(Files.exists(unrelated.resolve("diagnostic-outcome.txt")))
            assertEquals(permissions, Files.getPosixFilePermissions(unrelated))
        }
        val original = java.io.IOException("pre-ARM failure")
        val owned = directory.resolve("owned")
        kotlin.test.assertSame(
            original,
            assertFailsWith<java.io.IOException> {
            TierEA4DiagnosticSupport.withPreparedAttempt(owned) { throw original }
        },
        )
        assertTrue(Files.readString(owned.resolve("diagnostic-outcome.txt")).startsWith("INCOMPLETE"))
        assertEquals("PREPARED\n", Files.readString(owned.resolve("attempt.state")))
    }

    private fun production(
        ids: List<Long> = calls().coldMethods.keys.sorted(),
        overrides: Map<Long, String> = emptyMap(),
    ): List<String> =
        ids.map { id ->
        val method = overrides[id] ?: calls().coldMethods[id] ?: "readConfiguration"
        """{"type":"event","capture_flags":0,"resolution_status":"RESOLVED","syscall_number":257,"invocation_id":"$id","frames":[{"class_name":"TierEAllTargetWorkload","method_name":"$method"}]}"""
    }

    private fun withRows(
        k: BoundaryDiagnosticSnapshot,
        rows: List<BoundaryDiagnosticRecord>,
    ): BoundaryDiagnosticSnapshot = k.copy(records = rows, control = BoundaryDiagnosticControl(rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), 0, 0, 0, 0))

    private fun localizedOne(): DiagnosticJoin {
        val k = kernel()
        return TierEA4DiagnosticSupport.joinObservedCalls(calls(), withRows(k, k.records.filterNot { it.plane == 2 && it.invocation == 1uL }), trace())
    }

    @Test
    fun fix1EveryOutcomeReconcilesProductionColdIdentities() {
        val local = localizedOne()
        assertIs<DiagnosticOutcome.LocalizedBoundary>(local.outcome)
        val expected = calls().coldMethods.keys.sorted()
        val cases = listOf(
            production() to listOf("resolved_localized_id=1"),
            production(expected - 2L) to listOf("resolved_localized_id=1", "unexplained_missing_ids=[2]"),
            production(expected - 1L - 2L) to listOf("unexplained_missing_ids=[2]"),
            production(expected - 1L + 2L) to listOf("duplicate_ids=[2]"),
            production(expected - 1L + 999L) to listOf("unexpected_ids=[999]"),
            production(expected - 1L, mapOf(3L to "loadTemplate")) to listOf("method_conflict_ids=[3]"),
        )
        for ((rows, conflicts) in cases) {
            val result = TierEA4DiagnosticSupport.reconcileProduction(local, calls().coldMethods, rows)
            val outcome = assertIs<DiagnosticOutcome.Incomplete>(result.outcome)
            assertEquals(local.matched, result.matched)
            assertTrue(conflicts.all { conflict -> outcome.reasons.any { conflict in it } }, outcome.reasons.toString())
        }
        val consistent = TierEA4DiagnosticSupport.reconcileProduction(local, calls().coldMethods, production(expected - 1L))
        assertIs<DiagnosticOutcome.LocalizedBoundary>(consistent.outcome)
        val baseline = TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), trace())
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.reconcileProduction(baseline, calls().coldMethods, production()).outcome)
        val partial = TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel().copy(quiescent = false), trace())
        val partialResult = TierEA4DiagnosticSupport.reconcileProduction(partial, calls().coldMethods, production(expected + 999L))
        assertTrue(assertIs<DiagnosticOutcome.Incomplete>(partialResult.outcome).reasons.any { "unexpected_ids=[999]" in it })
        assertEquals(partial.matched, partialResult.matched)
    }

    @Test
    fun fix1ProductionProjectionKeepsUnexpectedReportedColdRows() {
        val baseline = TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), trace())
        val extra = production() + production(listOf(999L))
        val result = TierEA4DiagnosticSupport.reconcileProduction(baseline, calls().coldMethods, extra)
        assertTrue(assertIs<DiagnosticOutcome.Incomplete>(result.outcome).reasons.any { "unexpected_ids=[999]" in it })
        val invalid = production() + production(listOf(999L)).single().replace("\"999\"", "null")
        assertTrue(assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.reconcileProduction(baseline, calls().coldMethods, invalid).outcome).reasons.any { "invalid_ids=[null]" in it })
        val unrelated = production() + production(listOf(999L), mapOf(999L to "main"))
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.reconcileProduction(baseline, calls().coldMethods, unrelated).outcome)
    }

    @Test
    fun fix1ReportedUnresolvedColdRowsCannotDisappearFromSourceReconciliation() {
        val baseline = TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), trace())
        for (id in listOf(5L, 999L)) {
            val rows = production() + production(listOf(id)).single().replace("\"RESOLVED\"", "\"CAPTURE_FAILED\"")
            val reconciled = TierEA4DiagnosticSupport.reconcileProduction(baseline, calls().coldMethods, rows)
            assertIs<DiagnosticOutcome.Incomplete>(reconciled.outcome)
            assertTrue(reconciled.reasons.any { "CAPTURE_FAILED" in it && "$id" in it })
            assertEquals(40, reconciled.matched.size)
        }
    }

    @Test
    fun fix1MandatoryRetentionFailureGatesFinalPublicationAndKeepsOriginalErrors() {
        for (localized in listOf(false, true)) {
            val destination = Files.createDirectory(directory.resolve("retention-$localized"))
            val commands = mutableListOf<String>()
            val effects = mutableListOf<String>()
            val original = AssertionError("original missing-open count assertion")
            val export = java.io.IOException("mandatory target.reg export failed")
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), "fix1-retain", destination) { command, _, _ ->
                commands += command[1]
                ContainerCommandResult(0, "exists")
            }
            var positive: DiagnosticJoin? = null
            val thrown = assertFailsWith<Throwable> {
                TierEFixtureLifecycle(owner, destination.resolve("creation.lock"), settle = { _, _ -> }, retainOnSuccess = true, retainOnFailure = true).run {
                    TierEA4DiagnosticSupport.finishDiagnosticAttempt(
                        exercise = {
                            effects += "exercise"
                            if (localized) throw original
                        },
                        quiesce = { effects += "quiesce" },
                        retain = {
                            effects += "retain"
                            Files.writeString(destination.resolve("export-status.txt"), "INCOMPLETE required target.reg missing\n")
                            Files.writeString(destination.resolve("boundary.bpfdiag.tmp"), "partial retained source")
                            throw export
                        },
                        classify = {
                            effects += "classify"
                            val candidate = if (localized) localizedOne() else TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), trace())
                            TierEA4DiagnosticSupport.reconcileProduction(
                                candidate,
                                calls().coldMethods,
                                production(
                                    calls()
                                .coldMethods.keys
                                .sorted()
                                .filter { !localized || it != 1L },
                                ),
                            )
                        },
                        publish = { join, retained ->
                            effects += "publish"
                            positive = join
                            TierEA4DiagnosticSupport.publishDiagnosticOutcome(destination, join, retained)
                        },
                    )
                }
            }
            kotlin.test.assertSame(if (localized) original else export, thrown)
            if (localized) assertTrue(thrown.suppressed.any { it === export })
            assertTrue(Files.readString(destination.resolve("export-status.txt")).startsWith("INCOMPLETE"))
            assertTrue(Files.readString(destination.resolve("diagnostic-outcome.txt")).startsWith("INCOMPLETE"))
            assertTrue(assertIs<DiagnosticOutcome.Incomplete>(requireNotNull(positive).outcome).reasons.any { "target.reg" in it })
            assertEquals(40, requireNotNull(positive).matched.size)
            assertTrue(Files.exists(destination.resolve("boundary.bpfdiag.tmp")))
            assertEquals(listOf("exercise", "quiesce", "retain", "classify", "publish"), effects)
            assertEquals(listOf("inspect", "stop", "inspect", "stop"), commands)
        }
    }

    @Test
    fun fix1ValidLocalizationSurvivesOriginalCountAssertionWithCompleteRetention() {
        val original = AssertionError("original missing-open assertion")
        Files.writeString(directory.resolve("export-status.txt"), "COMPLETE\n")
        val thrown = assertFailsWith<AssertionError> {
            TierEA4DiagnosticSupport.finishDiagnosticAttempt(
                exercise = { throw original },
                quiesce = {},
                retain = {},
                classify = { TierEA4DiagnosticSupport.reconcileProduction(localizedOne(), calls().coldMethods, production(calls().coldMethods.keys.sorted() - 1L)) },
                publish = { join, retained -> TierEA4DiagnosticSupport.publishDiagnosticOutcome(directory, join, retained) },
            )
        }
        kotlin.test.assertSame(original, thrown)
        assertEquals("COMPLETE\n", Files.readString(directory.resolve("export-status.txt")))
        assertTrue(Files.readString(directory.resolve("diagnostic-outcome.txt")).startsWith("LOCALIZED invocation=1"))
    }

    @Test
    fun fix1InheritedFdCloseOnUnrelatedAttachedThreadIsNotLostEvidence() {
        for (fd in listOf(5, 99)) {
            val oracle = listOf("50000 0.1 close($fd) = 0 <0.1>", "50000 0.1 +++ exited with 0 +++") + trace()
            assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), oracle).outcome)
        }
    }

    @Test
    fun fix1CrossThreadCloseUsesSharedFdGeneration() {
        val oracle = listOf("42100 0.1 gettid() = 37 <0.1>", "42101 0.1 gettid() = 38 <0.1>") +
            (1..20).flatMap { ordinal ->
            listOf(
                "42100 0.1 openat(AT_FDCWD, \"\\x2f\\x61\", O_RDONLY) = ${ordinal + 4} <0.1>",
                "${if (ordinal == 1) 42101 else 42100} 0.1 close(${ordinal + 4}) = 0 <0.1>",
                "42101 0.1 openat(AT_FDCWD, \"\\x2f\\x61\", O_RDONLY) = ${ordinal + 4} <0.1>",
                "42101 0.1 close(${ordinal + 4}) = 0 <0.1>",
            )
        } +
            listOf("42100 0.1 +++ exited with 0 +++", "42101 0.1 +++ exited with 0 +++")
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), oracle)
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(joined.outcome)
        assertEquals(40, joined.matched.size)
    }

    @Test
    fun fix1UnrelatedTracedFdMayRemainOpenUntilProcessExit() {
        val oracle = listOf("50000 0.1 openat(AT_FDCWD, \"\\x2f\\x62\", O_RDONLY) = 99 <0.1>", "50000 0.1 +++ exited with 0 +++") + trace()
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), oracle).outcome)
    }

    @Test
    fun fix1SelectedFdReuseCannotBeHiddenByAnotherThreadsTable() {
        val oracle = trace().toMutableList().apply {
            addAll(2, listOf("50000 0.1 openat(AT_FDCWD, \"\\x2f\\x62\", O_RDONLY) = 5 <0.1>", "50000 0.1 close(5) = 0 <0.1>", "50000 0.1 +++ exited with 0 +++"))
        }
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), oracle)
        assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
        assertTrue(1L in joined.ambiguousIds)
    }

    @Test
    fun fix1UntracedFdReplacementMakesOnlySelectedLifetimeUncertain() {
        val k = kernel()
        val replacement = k.records.first().copy(rawId = 292uL, ktime = 118uL, pidTgid = ((1000uL shl 32) or 50000uL))
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), withRows(k, k.records + replacement), trace())
        assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
        assertTrue(1L in joined.ambiguousIds)
        assertTrue(joined.matched.any { it.invocationId == 20L })
    }

    @Test
    fun fix1SelectedCloseMustBeSuccessfulAndUnambiguous() {
        val original = trace()
        val failed = original.toMutableList().apply { this[2] = "42100 0.1 close(5) = -1 EBADF (Bad file descriptor) <0.1>" }
        val missing = original.toMutableList().apply { removeAt(2) }
        val otherBridge = original.indexOfFirst { it.contains("gettid() = 38") }
        val overlapping =
            listOf(
                original[0],
                original[otherBridge],
                "42100 0.1 openat(AT_FDCWD, \"\\x2f\\x61\", O_RDONLY <unfinished ...>",
                "42101 0.1 close(5) = 0 <0.1>",
                "42100 0.1 <... openat resumed>) = 5 <0.1>",
            ) +
            original.filterIndexed { index, _ -> index !in setOf(0, 1, otherBridge) }
        for (oracle in listOf(missing, failed, overlapping)) {
            val result = TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), oracle)
            assertIs<DiagnosticOutcome.Incomplete>(result.outcome)
            assertTrue(1L in result.ambiguousIds)
            assertTrue(result.matched.any { it.invocationId == 20L })
        }
    }

    @Test
    fun fix1RetentionAndPublicationFailuresKeepFatalFirstAndStillPublishIncomplete() {
        val original = java.io.IOException("original exercise")
        val export = java.io.IOException("required artifact")
        val fatal = LinkageError("publication failed after writing")
        val thrown = assertFailsWith<LinkageError> {
            TierEA4DiagnosticSupport.finishDiagnosticAttempt(
                exercise = { throw original },
                quiesce = {},
                retain = { throw export },
                classify = { TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), trace()) },
                publish = { join, retained ->
                    TierEA4DiagnosticSupport.publishDiagnosticOutcome(directory, join, retained)
                    throw fatal
                },
            )
        }
        kotlin.test.assertSame(fatal, thrown)
        assertTrue(thrown.suppressed.any { it === original })
        assertTrue(original.suppressed.any { it === export })
        assertTrue(Files.readString(directory.resolve("export-status.txt")).startsWith("INCOMPLETE"))
        assertTrue(Files.readString(directory.resolve("diagnostic-outcome.txt")).startsWith("INCOMPLETE"))
    }

    private fun fix2KernelWithCloseRows(native: NativeCallSnapshot = calls()): BoundaryDiagnosticSnapshot {
        val k = kernel(native)
        val template = k.records.first { it.plane == 2 }
        val closes = native.calls.map { call ->
            template.copy(
                rawId = 3uL,
                ktime = (call.returned + 1).toULong(),
                pidTgid = (1000uL shl 32) or (42100 + call.tid - 37).toULong(),
                invocation = 0uL,
                stage = BoundaryStage.MAIN_NO_STORAGE,
                validFields = 0u,
            )
        }
        val postClose = native.calls.groupBy { it.tid }.map { (tid, calls) ->
            template.copy(rawId = 186uL, ktime = (calls.maxOf { it.returned } + 12).toULong(), pidTgid = (1000uL shl 32) or (42100 + tid - 37).toULong())
        }
        return withRows(k, k.records + closes + postClose)
    }

    private fun fix2TraceWithPostCalls(): List<String> =
        trace().flatMap { line ->
        if (line.contains("+++ exited")) {
            val host = line.substringBefore(' ').toLong()
            listOf("$host 0.1 gettid() = ${host - 42100 + 37} <0.1>", line)
        } else {
            listOf(line)
        }
    }

    private fun fix2MutatorEvidence(
        entry: Long,
        rawId: ULong = 33uL,
        completedBy: Long? = null,
    ): Pair<BoundaryDiagnosticSnapshot, List<String>> {
        val k = fix2KernelWithCloseRows()
        val mutator = k.records.first { it.plane == 2 }.copy(rawId = rawId, ktime = entry.toULong(), pidTgid = (1000uL shl 32) or 50000uL)
        val proof = completedBy?.let { listOf(mutator.copy(rawId = 186uL, ktime = it.toULong())) }.orEmpty()
        val oracleProof = completedBy?.let { listOf("50000 0.1 gettid() = 50 <0.1>") }.orEmpty()
        val complete = withRows(k, k.records + mutator + proof)
        assertTrue(complete.complete)
        assertEquals(40, complete.records.count { it.plane == 2 && it.rawId == 3uL })
        return complete to (fix2TraceWithPostCalls() + oracleProof + "50000 0.1 +++ exited with 0 +++")
    }

    @Test
    fun fix2CloseEntryIsNotTheSelectedGenerationCompletionBound() {
        for (mutator in listOf(33uL, 436uL)) {
            // Last close enters at 309; its next same-thread gettid enters at 320.
            val (k, oracle) = fix2MutatorEvidence(310, mutator, 312)
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), k, oracle)
            assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
            assertTrue(20L in joined.ambiguousIds)
            assertTrue(joined.matched.any { it.invocationId == 1L })
            assertFalse(1L in joined.ambiguousIds)
        }
    }

    @Test
    fun fix2MutationAfterProvedPostCloseBoundDoesNotPoisonClosedGenerations() {
        for (mutator in listOf(33uL, 425uL, 426uL, 436uL)) {
            val (k, oracle) = fix2MutatorEvidence(330, mutator)
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), k, oracle)
            assertIs<DiagnosticOutcome.InconclusiveBaseline>(joined.outcome)
            assertEquals(40, joined.matched.size)
            assertTrue(joined.ambiguousIds.isEmpty())
        }
    }

    @Test
    fun fix2EarlierMutatorEntryWithoutCompletionProofRemainsUncertain() {
        val (k, oracle) = fix2MutatorEvidence(105)
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), k, oracle)
        assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
        assertTrue(1L in joined.ambiguousIds)
        // Input/result matches remain positive facts, not claims about successful closure.
        assertEquals(40, joined.matched.size)
    }

    @Test
    fun fix2EarlierSynchronousMutatorWithProvedCompletionBeforeOpenIsHarmless() {
        val (k, oracle) = fix2MutatorEvidence(105, completedBy = 108)
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), k, oracle)
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(joined.outcome)
        assertEquals(40, joined.matched.size)
        assertTrue(joined.ambiguousIds.isEmpty())
    }

    @Test
    fun fix2PersistentFdEffectsAreNotCompletedByAnOrdinaryLaterEntry() {
        for (mutator in listOf(426uL, 425uL, 436uL, 59uL, 322uL)) {
            val (k, oracle) = fix2MutatorEvidence(105, mutator, 108)
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), k, oracle)
            assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
            assertTrue(1L in joined.ambiguousIds)
            assertEquals(40, joined.matched.size)
        }
    }

    @Test
    fun fix2CrossThreadUnfinishedCloseRequiresThatClosingThreadsPostOperation() {
        val original = calls()
        val native = NativeCallSnapshot(
            original.session,
            original.namespacePid,
            true,
            original.calls.map {
            if (it.tid ==
            38L
            ) {
                it.copy(entry = it.entry + 400, returned = it.returned + 400)
            } else {
                it
            }
        },
            original.coldMethods,
            true,
        )
        val k = fix2KernelWithCloseRows(native)
        val rows = k.records.map {
            if (it.plane == 2 && it.rawId == 3uL && it.ktime == 309uL && it.pidTgid and 0xffffffffuL == 42100uL) it.copy(pidTgid = (1000uL shl 32) or 42101uL) else it
        }
        val replacement = rows.first { it.plane == 2 }.copy(rawId = 33uL, ktime = 330uL, pidTgid = (1000uL shl 32) or 50000uL)
        val evidence = withRows(k, rows + replacement + replacement.copy(rawId = 186uL, ktime = 332uL))
        val base = fix2TraceWithPostCalls()
        val bridge = base.first { it.startsWith("42101 ") && it.contains("gettid() = 38") }
        var movedBridge = false
        val oracle = listOf(bridge) +
            base
                .filterNot { line ->
            if (!movedBridge && line == bridge) {
                movedBridge = true
                true
            } else {
                false
            }
        }.flatMap { line ->
            if (line.startsWith("42100 ") && line.contains("close(24)")) {
                listOf("42101 0.1 close(24 <unfinished ...>", "42101 0.1 <... close resumed>) = 0 <0.1>")
            } else {
                listOf(line)
            }
        } +
            listOf("50000 0.1 gettid() = 50 <0.1>", "50000 0.1 +++ exited with 0 +++")
        assertTrue(evidence.complete)
        assertEquals(19, evidence.records.count { it.plane == 2 && it.rawId == 3uL && it.pidTgid and 0xffffffffuL == 42100uL })
        assertEquals(21, evidence.records.count { it.plane == 2 && it.rawId == 3uL && it.pidTgid and 0xffffffffuL == 42101uL })
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(native, evidence, oracle)
        assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
        assertEquals(setOf(20L), joined.ambiguousIds)
        assertTrue(joined.matched.any { it.invocationId == 40L })
    }

    @Test
    fun fix2SignalHandlerEntryCannotCertifyUntracedMutatorCompletion() {
        for ((signalLine, returnRow) in listOf(false to true, true to true, true to false)) {
            val (k, base) = fix2MutatorEvidence(105, completedBy = 108)
            val signalReturn = k.records.first { it.plane == 2 }.copy(rawId = 15uL, ktime = 109uL, pidTgid = (1000uL shl 32) or 50000uL)
            val oracle = if (signalLine) base.toMutableList().apply { add(indexOfFirst { it.startsWith("50000 ") }, "50000 0.1 --- SIGUSR1 {si_signo=SIGUSR1} ---") } else base
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), withRows(k, k.records + if (returnRow) listOf(signalReturn) else emptyList()), oracle)
            assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
            assertTrue(1L in joined.ambiguousIds)
        }
    }

    @Test
    fun fix2IncompleteOrMisorderedCloseCorrelationCannotProveCompletion() {
        val (k, oracle) = fix2MutatorEvidence(330)
        for (rows in listOf(
            k.records.filterNot { it.plane == 2 && it.rawId == 3uL && it.ktime == 309uL },
            k.records.map { if (it.plane == 2 && it.rawId == 3uL && it.ktime == 309uL) it.copy(ktime = 321uL) else it },
        )) {
            val evidence = withRows(k, rows)
            assertTrue(evidence.complete)
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), evidence, oracle)
            assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
            assertTrue(20L in joined.ambiguousIds)
            assertEquals(40, joined.matched.size)
        }
    }

    private fun calls(): NativeCallSnapshot {
        val rows = (0 until 40).map { index ->
            val ordinal = index % 20 + 1L
            NativeCall(3, 0, index + 1L, ordinal, 1, 37L + index / 20, 10, 20, 100 + ordinal * 10, 108 + ordinal * 10, 0, 0, ordinal.toInt() + 4, "2f61", true, index + 1L, 1uL)
        }
        return NativeCallSnapshot(19802, 1, true, rows, rows.associate { it.invocation to if (it.tid == 37L) "readConfiguration" else "loadTemplate" }, true)
    }

    private fun kernel(
        native: NativeCallSnapshot = calls(),
        complete: Boolean = true,
    ): BoundaryDiagnosticSnapshot {
        fun row(
            tid: Long,
            time: Long,
            syscall: Long,
            id: Long,
        ) = BoundaryDiagnosticRecord(
                3,
                2,
                time.toULong(),
                ((1000L shl 32) or tid).toULong(),
                syscall.toULong(),
                id.toULong(),
                1uL,
                listOf(1000uL, 1uL, 1uL, 1uL, 1uL, 0uL),
                BoundaryStage.MAIN_ACTIVE_COUNTER_REACHED,
                1u,
                0u,
                991u,
            )
        val rows = listOf(row(42100, 15, 186, 0), row(42101, 15, 186, 0)) +
            native.calls.flatMap {
            val main = row(42100 + it.tid - 37, it.entry + 1, 257, it.invocation)
            listOf(main.copy(plane = 1, rawId = it.invocation.toULong(), ktime = (it.entry - 1).toULong(), stage = BoundaryStage.MARKER_SET, words = listOf(19802uL, 1000uL, 1uL, 0uL, 0uL, 0uL)), main)
        }
        return BoundaryDiagnosticSnapshot(19802, 1000, complete, complete, BoundaryDiagnosticControl(rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), 0, 0, 0, 0), rows)
    }

    private fun trace(): List<String> =
        (0..1).flatMap { thread ->
        val host = 42100 + thread
        listOf("$host 0.000000 gettid() = ${37 + thread} <0.000001>") +
            (1..20).flatMap { ordinal ->
            listOf("$host 0.000001 openat(AT_FDCWD, \"\\x2f\\x61\", O_RDONLY) = ${ordinal + 4} <0.000001>", "$host 0.000001 close(${ordinal + 4}) = 0 <0.000001>")
        } +
            "$host 0.000001 +++ exited with 0 +++"
    }

    @Test
    fun unusedNativeModeDoesNotInvalidateReadOnlyOrDirectoryJoins() {
        for ((flags, rendered) in listOf(0 to "O_RDONLY", 65536 to "O_RDONLY|O_DIRECTORY")) {
            val n = calls().let { NativeCallSnapshot(it.session, it.namespacePid, it.complete, it.calls.map { call -> call.copy(flags = flags, mode = 438) }, it.coldMethods, it.otherPlanesComplete) }
            val result = TierEA4DiagnosticSupport.joinObservedCalls(n, kernel(n), trace().map { it.replace("O_RDONLY", rendered) })
            assertIs<DiagnosticOutcome.InconclusiveBaseline>(result.outcome)
            assertEquals(40, result.matched.size)
            assertTrue(n.calls.all { it.mode == 438 })
        }
    }

    @Test
    fun creationModeRemainsRequiredAndExactAlongsideFlagsPathAndResult() {
        for ((flags, rendered) in listOf(64 to "O_RDONLY|O_CREAT", 4259842 to "O_RDWR|O_TMPFILE")) {
            val n = calls().let { NativeCallSnapshot(it.session, it.namespacePid, it.complete, it.calls.map { call -> call.copy(flags = flags, mode = 438) }, it.coldMethods, it.otherPlanesComplete) }
            val valid = trace().map { it.replace("O_RDONLY)", "$rendered, 0666)") }
            assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.joinObservedCalls(n, kernel(n), valid).outcome)
            for (bad in listOf(
                valid.map { it.replace("0666", "0600") },
                valid.map { it.replace(", 0666", "") },
                valid.map { it.replace("0666", "invalid") },
                valid.map { it.replace(rendered, "$rendered|O_EXCL") },
                valid.map { it.replace("\\x2f\\x61", "\\x2f\\x62") },
                valid.map { it.replace("= 5 <", "= 99 <") },
            )) {
                assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(n, kernel(n), bad).outcome)
            }
        }
    }

    @Test
    fun bridgeMustPrecedeItsOpenInBothOrderedObservers() {
        val original = trace()
        val late = original.toMutableList().apply { add(3, removeAt(0)) }
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), late).outcome)
    }

    @Test
    fun pathnameBytesAndErrorReturnsAreNotTextOrSuccessfulFds() {
        val n = calls()
        val bytes = NativeCallSnapshot(n.session, n.namespacePid, true, n.calls.map { it.copy(pathHex = "2fff") }, n.coldMethods, true)
        val oracle = trace().map { it.replace("\\x2f\\x61", "\\x2f\\xff") }
        assertEquals(40, TierEA4DiagnosticSupport.joinObservedCalls(bytes, kernel(bytes), oracle).matched.size)
        val failed = NativeCallSnapshot(n.session, n.namespacePid, true, n.calls.map { if (it.invocation == 1L) it.copy(result = -1) else it }, n.coldMethods, true)
        val errorTrace = trace().toMutableList().apply {
            this[1] = this[1].replace("= 5 <", "= -1 ENOENT (No such file or directory) <")
            removeAt(2)
        }
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.joinObservedCalls(failed, kernel(failed), errorTrace).outcome)
        val badMode = trace().toMutableList().apply { this[1] = this[1].replace("O_RDONLY)", "O_RDONLY, nonsense)") }
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(n, kernel(), badMode).outcome)
    }

    @Test
    fun earlierJvmGettidUsesFullOrderedSequenceAndUniqueBracket() {
        val k = kernel()
        val bridge = k.records.first()
        val rows = k.records + bridge.copy(ktime = 5uL)
        val earlier = k.copy(records = rows, control = BoundaryDiagnosticControl(rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), 0, 0, 0, 0))
        val oracle = listOf("[pid 42100] 123.0 gettid() = 37 <0.1>") + trace()
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), earlier, oracle)
        assertEquals(40, joined.matched.size)
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(joined.outcome)
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), k, oracle).outcome)
    }

    @Test
    fun multipleMissingBoundariesAndIncompletePlanesRetainPositiveMatches() {
        val k = kernel()
        val rows = k.records.filterNot { it.plane == 2 && it.invocation in setOf(1uL, 2uL) }
        val missing = k.copy(records = rows, control = BoundaryDiagnosticControl(rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), 0, 0, 0, 0))
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), missing, trace())
        assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
        assertEquals(setOf(1L, 2L), joined.missingIds)
        assertEquals(40, joined.matched.size)
        assertTrue(joined.reasons.any { it.contains("missing main entry for invocation 1") })
        val partial = TierEA4DiagnosticSupport.joinObservedCalls(calls(), k.copy(producerClosed = false), trace())
        assertIs<DiagnosticOutcome.Incomplete>(partial.outcome)
        assertEquals(40, partial.matched.size)
    }

    @Test
    fun oracleBytesFlagsErrorsAndBoundsFailClosed() {
        val unknownSignal = trace().toMutableList().apply { add(2, "42100 0.1 --- unrecognized ---") }
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), unknownSignal).outcome)
        for (replacement in listOf(
            "openat(AT_FDCWD, \"\\x2f\\x00\", O_RDONLY) = 5 <0.1>",
            "openat(AT_FDCWD, \"\\x2g\", O_RDONLY) = 5 <0.1>",
            "close(6) = 0 <0.1>",
        )) {
            val bad = trace().toMutableList().apply { this[1] = "42100 0.1 $replacement" }
            assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), bad).outcome)
        }
        for (bad in listOf(trace() + "x".repeat(4097), List(262145) { "" }, List(1025) { "${50000 + it} 0.1 +++ exited with 0 +++" })) {
            assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), bad).outcome)
        }
    }

    @Test
    fun missingOrDuplicateBridgeIsIncomplete() {
        val original = trace()
        for (bad in listOf(
            original.filterNot { it.contains("gettid") },
                original + original.first(),
                original.map { it.replace("= 37 <", "= 42100 <") },
                original.filterNot { it.contains("exited") },
            original + "42100 0.1 gettid() = 37 <0.1>",
        )) {
            assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), bad).outcome)
        }
    }

    @Test
    fun unfinishedRestartedSyscallsAreNotInventedCalls() {
        val original = trace()
        val complete = original[1]
        val restart = complete.replace("= 5 <0.000001>", "= ? ERESTARTSYS (To be restarted)")
        val restarted = original.toMutableList().apply { add(1, restart) }
        val k = kernel()
        val first = k.records.single { it.plane == 2 && it.invocation == 1uL }
        val rows = k.records + first.copy(ktime = first.ktime + 1uL)
        val retriedKernel = k.copy(records = rows, control = BoundaryDiagnosticControl(rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), 0, 0, 0, 0))
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), retriedKernel, restarted).outcome)
        val halves = original.toMutableList().apply {
            this[1] = "42100 0.1 openat(AT_FDCWD, \"\\x2f\\x61\", O_RDONLY <unfinished ...>"
            add(2, "42100 0.1 <... openat resumed>) = 5 <0.000001>")
        }
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), halves).outcome)
        for (bad in listOf(original - complete, original + restart, halves.filterNot { it.contains("resumed>") }, original + "42100 0.1 <... openat resumed>) = 5 <0.1>")) {
            assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), bad).outcome)
        }
    }

    @Test
    fun repeatedInputUsesPerThreadOrderAndReturnedFd() {
        val bad = trace().toMutableList().apply { this[1] = this[1].replace("= 5 <", "= 6 <") }
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), bad).outcome)
        val shuffled = calls().let { NativeCallSnapshot(it.session, 1, true, it.calls.reversed(), it.coldMethods, true) }
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.joinObservedCalls(shuffled, kernel(), trace()).outcome)
    }

    @Test
    fun noMainEntryNeedsCompleteReceiptPlane() {
        val k = kernel()
        val rows = k.records.filterNot { it.plane == 2 && it.invocation == 1uL }
        val missing = k.copy(records = rows, control = BoundaryDiagnosticControl(rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), 0, 0, 0, 0))
        assertIs<DiagnosticOutcome.LocalizedBoundary>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), missing, trace()).outcome)
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), missing.copy(quiescent = false), trace()).outcome)
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls().select(calls().coldMethods, false), missing, trace()).outcome)
    }

    @Test
    fun repeatedSetsJoinTheFinalObservedStateWithoutAssumingMultiplicity() {
        for (count in listOf(2, 5, 8)) {
            val k = repeatedMarkers(count)
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), k, trace())
            assertIs<DiagnosticOutcome.InconclusiveBaseline>(joined.outcome)
            assertEquals(40, joined.matched.size)
            assertTrue(joined.missingIds.isEmpty())
            assertEquals(count, k.records.count { it.plane == 1 && it.rawId == 1uL })
        }
    }

    private fun repeatedMarkers(count: Int): BoundaryDiagnosticSnapshot {
        val k = kernel()
        return withRows(
            k,
            k.records.flatMap { row ->
            if (row.plane == 1 && row.rawId == 1uL) {
                List(count) { index -> row.copy(ktime = 100uL + index.toULong(), sequence = index.toULong() + 1uL) }
            } else {
                listOf(if (row.plane == 2 && row.invocation == 1uL) row.copy(sequence = count.toULong()) else row)
            }
        },
        )
    }

    @Test
    fun supersedingMarkersAreNotHiddenByStageOrInvocationFilters() {
        val k = repeatedMarkers(2)
        val last = k.records.last { it.plane == 1 && it.rawId == 1uL }
        for (later in listOf(
                last.copy(stage = BoundaryStage.MARKER_CLEAR, rawId = 0uL, invocation = 0uL),
                last.copy(rawId = 2uL, invocation = 2uL),
                last.copy(stage = BoundaryStage.MARKER_STORAGE_FAILED),
                last.copy(validFields = 0u),
                last.copy(commit = 1),
            )) {
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), withRows(k, k.records + later.copy(ktime = 109uL)), trace())
            assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
            assertTrue(1L in joined.missingIds)
            assertEquals(40, joined.matched.size)
        }
    }

    @Test
    fun finalMarkerRequiresEmitterKnownFieldsAndCoherentRepeatedState() {
        val k = repeatedMarkers(2)
        val last = k.records.last { it.plane == 1 && it.rawId == 1uL }
        val badWords = listOf(0 to 7uL, 1 to 7uL, 2 to 0uL, 3 to 1uL, 4 to 1uL).map { (index, value) ->
            last.copy(words = last.words.toMutableList().apply { this[index] = value })
        }
        val unknownFields = listOf(0, 1, 2, 3, 4, 6, 7, 8, 9).map { bit -> last.copy(validFields = last.validFields and (1u shl bit).inv()) }
        for (bad in badWords +
            unknownFields +
            listOf(
                last.copy(invocation = 2uL),
                last.copy(sequence = 0uL),
                last.copy(sequence = 1uL),
                last.copy(generation = 0u),
                last.copy(generation = 2u),
                last.copy(aux = 1u),
            )) {
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), withRows(k, k.records.map { if (it === last) bad else it }), trace())
            assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
            assertEquals(40, joined.matched.size)
        }
    }

    @Test
    fun tiedOrInterveningMarkersCannotCertifyStableStateEvenIfRestored() {
        val k = repeatedMarkers(2)
        val last = k.records.last { it.plane == 1 && it.rawId == 1uL }
        for (extra in listOf(
                listOf(last.copy(sequence = 3uL)),
                listOf(last.copy(ktime = 110uL, sequence = 3uL)),
                listOf(last.copy(ktime = 111uL, stage = BoundaryStage.MARKER_CLEAR, rawId = 0uL, invocation = 0uL)),
                listOf(last.copy(ktime = 112uL, sequence = 3uL)),
                listOf(last.copy(ktime = 112uL, rawId = 2uL, invocation = 2uL), last.copy(ktime = 113uL, sequence = 4uL)),
            )) {
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), withRows(k, k.records + extra), trace())
            assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
            assertEquals(40, joined.matched.size)
        }
    }

    @Test
    fun repeatedStateUsesExactFinalMainSequenceAndGeneration() {
        val k = repeatedMarkers(5)
        val main = k.records.single { it.plane == 2 && it.invocation == 1uL }
        for (bad in listOf(main.copy(sequence = 1uL), main.copy(generation = 2u))) {
            val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), withRows(k, k.records.map { if (it === main) bad else it }), trace())
            assertIs<DiagnosticOutcome.Incomplete>(joined.outcome)
            assertTrue(1L in joined.ambiguousIds)
            assertEquals(40, joined.matched.size)
        }
    }

    @Test
    fun absentMainWithRepeatedSetsLocalizesToActualFinalRecordOnlyWhenObserversComplete() {
        val k = repeatedMarkers(5)
        val missing = withRows(k, k.records.filterNot { it.plane == 2 && it.invocation == 1uL })
        val joined = TierEA4DiagnosticSupport.joinObservedCalls(calls(), missing, trace())
        val localized = assertIs<DiagnosticOutcome.LocalizedBoundary>(joined.outcome)
        assertEquals(1L, localized.invocationId)
        assertEquals(listOf(6L), localized.recordOrdinals)
        assertEquals(5uL, missing.records[6].sequence)
        assertEquals(5, missing.records.count { it.plane == 1 && it.rawId == 1uL })
        assertEquals(40, joined.matched.size)
        for (partial in listOf(missing.copy(quiescent = false), missing.copy(producerClosed = false))) {
            val incomplete = TierEA4DiagnosticSupport.joinObservedCalls(calls(), partial, trace())
            assertIs<DiagnosticOutcome.Incomplete>(incomplete.outcome)
            assertEquals(40, incomplete.matched.size)
        }
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls().select(calls().coldMethods, false), missing, trace()).outcome)
    }

    @Test
    fun cleanTwentyPlusTwentyIsInconclusive() {
        val join = TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), trace())
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(join.outcome)
        assertEquals(40, join.matched.size)
        assertFailsWith<UnsupportedOperationException> { (join.matched as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (calls().coldMethods as MutableMap).clear() }
    }

    @Test
    fun mainRejectionPreservesActualZeroStateAndUnknownEntriesNeverProveAbsence() {
        val k = kernel()
        val rejected = k.copy(records = k.records.map { if (it.plane == 2 && it.invocation == 1uL) it.copy(stage = BoundaryStage.MAIN_INACTIVE, invocation = 0uL) else it })
        assertEquals(BoundaryStage.MAIN_INACTIVE, assertIs<DiagnosticOutcome.LocalizedBoundary>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), rejected, trace()).outcome).stage)
        for (bad in listOf(
            k.copy(records = k.records.map { if (it.plane == 2 && it.invocation == 1uL) it.copy(rawId = ULong.MAX_VALUE) else it }),
            k.copy(records = k.records.map { if (it.plane == 2 && it.invocation == 1uL) it.copy(ktime = ULong.MAX_VALUE) else it }),
            k.copy(records = k.records.map { if (it.plane == 2 && it.invocation == 1uL) it.copy(validFields = 0u) else it }),
            k.copy(records = k.records.filterNot { it.plane == 1 }),
        )) {
            assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), bad, trace()).outcome)
        }
        val duplicate = k.records.single { it.plane == 2 && it.invocation == 1uL }
        val rows = k.records + duplicate.copy(ktime = duplicate.ktime + 1uL)
        val cluster = k.copy(records = rows, control = BoundaryDiagnosticControl(rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), rows.size.toLong(), 0, 0, 0, 0))
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), cluster, trace()).outcome)
    }

    @Test
    fun structuredOpenat2UsesActualSyscallNumberAndRelativeTimeDoesNotJoinClocks() {
        val changed = trace().toMutableList().apply { this[1] = "42100 99999.123 openat2(AT_FDCWD, \"\\x2f\\x61\", {flags=O_RDONLY, resolve=0}, 24) = 5 <0.1>" }
        val k = kernel().let { it.copy(records = it.records.map { row -> if (row.plane == 2 && row.invocation == 1uL) row.copy(rawId = 437uL) else row }) }
        assertIs<DiagnosticOutcome.InconclusiveBaseline>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), k, changed).outcome)
        assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), changed).outcome)
    }

    @Test
    fun unknownFlagsOpenat2MalformedBytesAndPartialLinesAreIncomplete() {
        for (replacement in listOf(
                "openat2(AT_FDCWD, \"\\x2f\\x61\", {flags=O_RDONLY, resolve=0}, 24) = 5 <0.1>",
                "openat(AT_FDCWD, 0x123, O_RDONLY) = 5 <0.1>",
                "openat(AT_FDCWD, \"\\x2f\"..., O_RDONLY) = 5 <0.1>",
                "openat(AT_FDCWD, \"\\x2f\\x61\", O_UNKNOWN) = 5 <0.1>",
                "openat(AT_FDCWD,",
            )) {
            val bad = trace().toMutableList().apply { this[1] = "42100 0.1 $replacement" }
            assertIs<DiagnosticOutcome.Incomplete>(TierEA4DiagnosticSupport.joinObservedCalls(calls(), kernel(), bad).outcome)
        }
    }

    private fun image(
        reserved: Long = 1,
        commit: Int = 3,
    ): ByteArray {
        val bytes = ByteArray(128 + minOf(reserved, 1024).toInt() * 384)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b
            .putInt(0x424f5a4d)
            .putShort(1)
            .putShort(128)
            .putInt(384)
            .putInt(1024)
        b
            .putLong(16, 19802)
            .putLong(24, reserved)
            .putLong(32, reserved)
            .putLong(40, 1)
            .putLong(48, if (commit == 3) 1 else 0)
            .putLong(56, maxOf(0, reserved - 1024))
            .putLong(88, 1)
            .putLong(96, 1)
        b
            .putInt(128, commit)
            .putLong(136, 7)
            .putLong(144, 1)
            .putInt(152, 1)
            .putInt(156, 37)
            .putLong(160, 10)
            .putLong(168, 20)
            .putLong(176, 100)
            .putLong(
            184,
            if (commit ==
            3
            ) {
                110
            } else {
                0
            },
        ).putLong(192, 1)
            .putInt(208, 5)
            .putInt(212, 3)
            .putInt(216, 1)
            .putLong(480, 9)
        bytes[224] = 47
        bytes[225] = 97
        return bytes
    }

    @Test
    fun nativeCapacitySemanticsAndPartialReturnsStayVisible() {
        val complete = TierEA4DiagnosticSupport.decodeNativeCalls(image())
        assertTrue(complete.complete)
        assertEquals("2f61", complete.calls.single().pathHex)
        assertFalse(TierEA4DiagnosticSupport.decodeNativeCalls(image(commit = 1)).complete)
        val overflow = TierEA4DiagnosticSupport.decodeNativeCalls(image(1025))
        assertEquals(1024, overflow.calls.size)
        assertFalse(overflow.complete)
        for (offset in listOf(0, 12, 104, 220, 488)) {
            val bad = image().apply { this[offset] = 99 }
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.decodeNativeCalls(bad) }
        }
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.decodeNativeCalls(image().copyOf(511)) }
    }

    private fun receipt(): String =
        "tier-e-boundary-control: version=1 status=ok installation=1 ordinal=1 initialized=1 target=1000 epoch=19802 capacity=32768 record_bytes=112 control_bytes=128 allocated_bytes=3678208 combined_storage_bound=6278592 bookkeeping_bound=65536 attempted=8 reserved=9 entries=10 terminals=11 drops=0 helper_failures=0 main_in_flight=1 marker_in_flight=1"

    @Test
    fun statusReceiptIsStrictFreshAndNonAtomicNotFinalProof() {
        val reply = "OK engine=active collector_state=RUNNING worker_alive=true failure=none"
        val first = TierEA4DiagnosticSupport.liveReceipt(listOf(receipt()), reply, 1000, 19802)
        assertEquals(11, first.values.getValue("terminals"))
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.liveReceipt(listOf(receipt()), reply, 1000, 19802, first) }
        for (bad in listOf(
                receipt() + " unknown=1",
                receipt() + " drops=0",
                receipt().replace("drops=0", "drops=1"),
                receipt().replace("attempted=8", "attempted=32768"),
                receipt().replace("target=1000", "target=1"),
                receipt().replace("initialized=1", "initialized=0"),
            )) {
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.liveReceipt(listOf(bad), reply, 1000, 19802) }
        }
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.liveReceipt(listOf(receipt(), receipt()), reply, 1000, 19802) }
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.liveReceipt(listOf(receipt()), "ERR", 1000, 19802) }
    }

    @Test
    fun staleVerifierBudgetIsRejected() {
        val stale = receipt().replace("combined_storage_bound=6278592", "combined_storage_bound=6234520")
        assertFailsWith<IllegalArgumentException> {
            TierEA4DiagnosticSupport.liveReceipt(listOf(stale), "OK engine=active collector_state=RUNNING worker_alive=true failure=none", 1000, 19802)
        }
    }

    @Test
    fun failureArtifactExportUsesSharedExactBoundAndPreservesRawPartialBytes() {
        for ((index, path) in listOf("/fixture/evidence/prog-load-failure.diag", "/fixture/evidence/prog-load-failure.diag.tmp").withIndex()) {
            val cap = TierEA4DiagnosticSupport.diagnosticArtifactMaximum(path)
            assertEquals(43008L, cap)
            val raw = ByteArray(43008) { (it % 256).toByte() }
            val encoded = directory.resolve("failure-$index.base64")
            Files.write(
                encoded,
                java.util.Base64
                .getEncoder()
                .encode(raw),
            )
            val output = directory.resolve("failure-$index")
            TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, output, cap)
            kotlin.test.assertContentEquals(raw, Files.readAllBytes(output))
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, output, cap) }
            Files.write(
                encoded,
                java.util.Base64
                .getEncoder()
                .encode(raw.copyOf(43009)),
            )
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, directory.resolve("over-$index"), cap) }
            Files.write(
                encoded,
                java.util.Base64
                .getEncoder()
                .encode(raw.copyOf(7)),
            )
            val partial = directory.resolve("partial-$index")
            TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, partial, cap)
            assertEquals(7, Files.size(partial))
            assertFailsWith<IllegalStateException> { TierEA4DiagnosticSupport.retainEncodedArtifact(44, encoded, directory.resolve("missing-$index"), cap) }
        }
    }

    @Test
    fun attemptIsConsumedBeforeReadinessAndArmIsSingleUse() {
        val path = directory.resolve("attempt.state")
        val attempt = DiagnosticAttempt(path)
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { DiagnosticAttempt(path) }
        var gates = 0
        attempt.armOnce(healthy, { true }) { gates++ }
        assertEquals("ARM\n", Files.readString(path))
        assertFailsWith<IllegalStateException> { attempt.armOnce(healthy, { true }) { gates++ } }
        assertEquals(1, gates)
        val failed = DiagnosticAttempt(directory.resolve("failed.state"))
        assertFailsWith<IllegalArgumentException> { failed.armOnce(DiagnosticReadyFacts(), { true }) { gates++ } }
        assertFailsWith<IllegalStateException> { failed.armOnce(healthy, { true }) { gates++ } }
        assertEquals(1, gates)
        assertFailsWith<IllegalStateException> { DiagnosticAttempt(directory.resolve("closed.state")).armOnce(healthy, { false }) { gates++ } }
    }

    @Test
    fun streamingStderrCapsActualOwnedChildAndPreservesFailure() {
        for (size in listOf(65536, 65537)) {
            val child = ProcessBuilder("python3", "-c", "import sys; sys.stderr.write('x'*$size); sys.stderr.flush()").start()
            val path = directory.resolve("stderr-$size")
            val copier = DiagnosticStderr(child, path)
            try {
                assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                if (size == 65536) {
                    copier.finish()
                } else {
                    val error = assertFailsWith<IllegalStateException> { copier.finish() }
                    assertTrue(error.message.orEmpty().contains("64 KiB"))
                    kotlin.test.assertSame(error, assertFailsWith<IllegalStateException> { copier.checkFailure() })
                }
                assertEquals(65536, Files.size(path))
            } finally {
                child.destroyForcibly().waitFor()
            }
        }
        val child = ProcessBuilder("bash", "-c", "echo retained >&2").start()
        val copier = DiagnosticStderr(child, directory.resolve("missing-parent/stderr"))
        try {
            assertFailsWith<java.nio.file.NoSuchFileException> { copier.finish() }
        } finally {
            child.destroyForcibly().waitFor()
        }
    }

    @Test
    fun creationLockCoversStartupAndPendingExecStaysAdopted() {
        val lock = directory.resolve("creation.lock")
        Files.writeString(Path.of("$lock.state"), "open\n")
        var adopted: Process? = null
        val failure = java.io.IOException("startup unresolved")
        try {
            kotlin.test.assertSame(
                failure,
                assertFailsWith<java.io.IOException> {
                TierEA4DiagnosticSupport.captureStartup(lock, { ProcessBuilder("bash", "-c", "sleep 30").start() }, { adopted = it }, {
                    assertTrue(requireNotNull(adopted).isAlive)
                    java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE).use { other ->
                        assertFailsWith<java.nio.channels.OverlappingFileLockException> { other.tryLock() }
                    }
                    throw failure
                })
            },
            )
            assertTrue(requireNotNull(adopted).isAlive)
            java.nio.channels.FileChannel
                .open(lock, java.nio.file.StandardOpenOption.WRITE)
                .use { other -> other.tryLock().use { assertTrue(it.isValid) } }
            Files.writeString(Path.of("$lock.state"), "closed\n")
            var launched = false
            assertFailsWith<IllegalStateException> {
                TierEA4DiagnosticSupport.captureStartup(lock, {
                    launched = true
                    error("must not launch")
                }, {}, { error("must not observe") })
            }
            assertFalse(launched)
        } finally {
            adopted?.destroyForcibly()?.waitFor()
        }
    }

    @Test
    fun missingReadinessNeverTouchesFirstGate() {
        var gate = false
        assertFailsWith<IllegalArgumentException> {
            TierEA4DiagnosticSupport.requireReady(DiagnosticReadyFacts())
            gate = true
        }
        assertFalse(gate)
        for (bad in listOf(
                healthy.copy(attached = false),
                healthy.copy(gateClosed = false),
                healthy.copy(receiptsAllocated = false),
                healthy.copy(identityPinned = false),
                healthy.copy(tracerPidMatches = false),
                healthy.copy(tracerAlive = false),
                healthy.copy(followsClones = false),
                healthy.copy(limitsVerified = false),
                healthy.copy(oracleHealthy = false),
                healthy.copy(sameTimeNamespace = false),
                healthy.copy(fingerprintsComplete = false),
            )) {
            assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.requireReady(bad) }
        }
    }

    @Test
    fun coldSelectionUsesClosedDefinitionsAndOpenBindingNotDictionaryOrder() {
        val frames = listOf("readConfiguration", "loadTemplate", "main").mapIndexed { index, method ->
            StackDefinition(StackTraceId(index + 1L), listOf(ManagedFrame("boot", "TierEAllTargetWorkload", method, "()V", 0, ManagedFrameKind.JAVA)), StackCaptureQuality.COMPLETE)
        }
        val definitions = (0 until 42).map { index ->
            InvocationDefinition(
                InvocationId(1000L + index),
                null,
                StackTraceId(
                    if (index <
                40
                    ) {
                    1L + index / 20
                } else {
                    3L
                },
                ),
                    executionContext = ExecutionContext(ExecutionId(1L + index / 20), ExecutionKind.PLATFORM),
            )
        }
        val dictionary = AgentDefinitionFile.Contents(frames, definitions.reversed(), 0, publication = SnapshotPublication(1, 42, true, SessionEpoch(19802)))
        val yield = TierECaptureYield.Report(
            19802,
            definitions.map {
            TierECaptureYield.Record(
                it.invocationId.value,
                if (it.invocationId.value ==
            1041L
                ) {
                3
            } else {
                1
            },
                TierECaptureYield.Outcome.CAPTURED,
            )
        },
        )
        val methods = TierEA4DiagnosticSupport.selectColdMethods(dictionary, yield)
        assertEquals(40, methods.size)
        assertFalse(1040L in methods || 1041L in methods)
        assertEquals("loadTemplate", methods[1027])
        assertFailsWith<UnsupportedOperationException> { (methods as MutableMap).clear() }
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.selectColdMethods(dictionary, yield.copy(records = yield.records.drop(1))) }
        val live = AgentDefinitionFile.Contents(frames, definitions, 0, publication = SnapshotPublication(1, 42, false, SessionEpoch(19802)))
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.selectColdMethods(live, yield) }
    }

    @Test
    fun boundedTextAndMandatoryStreamingExportFailClosed() {
        val text = directory.resolve("text")
        Files.writeString(text, "strace: Process 100 attached\npartial")
        assertTrue(TierEA4DiagnosticSupport.hasTextLine(text, 65536) { it.endsWith("attached") })
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.hasTextLine(text, 65536, true) { true } }
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.hasTextLine(text, 1) { true } }
        Files.writeString(text, "x".repeat(4097))
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.hasTextLine(text, 65536) { true } }
        val encoded = directory.resolve("raw.encoded.tmp")
        val bytes = ByteArray(10000) { it.toByte() }
        Files.write(
            encoded,
            java.util.Base64
            .getEncoder()
            .encode(bytes),
        )
        val output = directory.resolve("retained")
        TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, output, 10000)
        assertTrue(Files.readAllBytes(output).contentEquals(bytes))
        assertTrue(Files.exists(encoded))
        assertFailsWith<IllegalStateException> { TierEA4DiagnosticSupport.retainEncodedArtifact(44, encoded, directory.resolve("absent"), 10000) }
        assertFalse(Files.exists(directory.resolve("absent")))
        assertFailsWith<IllegalArgumentException> { TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, directory.resolve("overflow"), 100) }
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, output, 10000) }
        Files.writeString(encoded, "!!!!")
        assertFailsWith<java.io.IOException> { TierEA4DiagnosticSupport.retainEncodedArtifact(0, encoded, directory.resolve("bad-base64"), 10000) }
    }

    @Test
    fun tracerClientDeathDoesNotProveTracerDeath() {
        assertFalse(TierEA4DiagnosticSupport.tracerStopped(clientDead = true, pinnedAbsent = false))
    }

    @Test
    fun staleIdentityNeverSignals() {
        var signaled = false
        val expected = TracerIdentity(10, 20, "owned", "a".repeat(64), 30, 40)
        assertFailsWith<IllegalArgumentException> {
            TierEA4DiagnosticSupport.stopPinned(expected, expected.copy(startTicks = 21)) { signaled = true }
        }
        assertFalse(signaled)
    }

    @Test
    fun stderrOverflowAndFileLimitInvalidateOracle() {
        assertFalse(TierEA4DiagnosticSupport.oracleHealthy(65537, 1, 0))
        assertFalse(TierEA4DiagnosticSupport.oracleHealthy(1, 16777216, 0))
        assertFalse(TierEA4DiagnosticSupport.oracleHealthy(1, 1, 153))
        assertTrue(TierEA4DiagnosticSupport.oracleStderrLineHealthy("strace: Process 123 attached with 12 threads"))
        for (error in listOf(
                "strace: attach: ptrace(PTRACE_SEIZE, 123): No such process",
                "strace: Process 123 detached",
                "strace: write: No space left on device",
                "strace: WARNING: output incomplete",
            )) {
            assertFalse(TierEA4DiagnosticSupport.oracleStderrLineHealthy(error))
        }
    }
}
