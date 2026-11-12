package io.mazewall.profiler.testing.tierE

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.profiler.tierE.engine.BoundaryDiagnosticSnapshot
import io.mazewall.profiler.tierE.engine.BoundaryStage
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicReference

internal data class TracerIdentity(
    val pid: Long,
    val startTicks: Long,
    val cgroup: String,
    val containerId: String,
    val targetPid: Long,
    val targetStartTicks: Long,
)

internal data class DiagnosticReadyFacts(
    val attached: Boolean = false,
    val gateClosed: Boolean = false,
    val receiptsAllocated: Boolean = false,
    val identityPinned: Boolean = false,
    val tracerPidMatches: Boolean = false,
    val tracerAlive: Boolean = false,
    val followsClones: Boolean = false,
    val limitsVerified: Boolean = false,
    val oracleHealthy: Boolean = false,
    val sameTimeNamespace: Boolean = false,
    val fingerprintsComplete: Boolean = false,
)

internal sealed interface DiagnosticOutcome {
    data object InconclusiveBaseline : DiagnosticOutcome

    class Incomplete(
        reasons: List<String>,
    ) : DiagnosticOutcome {
        val reasons: List<String> = java.util.List.copyOf(reasons)
    }

    class LocalizedBoundary(
        val invocationId: Long,
        val stage: BoundaryStage?,
        recordOrdinals: List<Long>,
    ) : DiagnosticOutcome {
        val recordOrdinals: List<Long> = java.util.List.copyOf(recordOrdinals)
    }
}

internal class DiagnosticJoin(
    matched: List<DiagnosticMatchedCall>,
    reasons: List<String>,
    val outcome: DiagnosticOutcome,
    missingIds: Set<Long>,
    ambiguousIds: Set<Long>,
) {
    val matched: List<DiagnosticMatchedCall> = java.util.List.copyOf(matched)
    val reasons: List<String> = java.util.List.copyOf(reasons)
    val missingIds: Set<Long> = java.util.Set.copyOf(missingIds)
    val ambiguousIds: Set<Long> = java.util.Set.copyOf(ambiguousIds)
}

internal data class DiagnosticMatchedCall(
    val invocationId: Long,
    val hostTid: Long,
    val nativeOrdinal: Long,
    val returnedFd: Int,
    val syscallNumber: Long,
)

internal data class NativeCall(
    val commit: Int,
    val failures: Int,
    val invocation: Long,
    val ordinal: Long,
    val pid: Long,
    val tid: Long,
    val bridgeStart: Long,
    val bridgeEnd: Long,
    val entry: Long,
    val returned: Long,
    val flags: Int,
    val mode: Int,
    val result: Int,
    val pathHex: String,
    val original: Boolean,
    val execution: Long,
    val pathPointer: ULong,
)

internal class NativeCallSnapshot(
    val session: Long,
    val namespacePid: Long,
    val complete: Boolean,
    calls: List<NativeCall>,
    coldMethods: Map<Long, String> = emptyMap(),
    val otherPlanesComplete: Boolean = false,
) {
    val calls: List<NativeCall> = java.util.List.copyOf(calls)
    val coldMethods: Map<Long, String> = java.util.Map.copyOf(coldMethods)

    fun select(
        methods: Map<Long, String>,
        complete: Boolean,
    ): NativeCallSnapshot = NativeCallSnapshot(session, namespacePid, this.complete, calls, methods, complete)
}

internal class LiveControlReceipt(
    val installation: Long,
    val ordinal: Long,
    values: Map<String, Long>,
) {
    val values: Map<String, Long> = java.util.Map.copyOf(values)
}

/** Effects stay in the owned fixture; these decisions also run against synthetic host evidence. */
internal class DiagnosticAttempt(
    private val state: Path,
) {
    init {
        Files.newByteChannel(state, setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use {
            val bytes = ByteBuffer.wrap("PREPARED\n".toByteArray())
            while (bytes.hasRemaining()) it.write(bytes)
        }
    }

    private var armed = false

    fun armOnce(
        facts: DiagnosticReadyFacts,
        creationOpen: () -> Boolean,
        release: () -> Unit,
    ) = freezeAndArmOnce(creationOpen, { facts }, release)

    fun freezeAndArmOnce(
        creationOpen: () -> Boolean,
        freezeReady: () -> DiagnosticReadyFacts,
        release: () -> Unit,
    ) {
        check(!armed) { "attempt already consumed" }
        armed = true
        check(creationOpen()) { "creation admission is closed" }
        TierEA4DiagnosticSupport.requireReady(freezeReady())
        check(creationOpen()) { "creation admission closed during READY publication" }
        Files.writeString(state, "ARM\n")
        release()
    }
}

/** A streaming copier, not another process supervisor or retained stderr mirror. */
internal class DiagnosticStderr(
    process: Process,
    output: Path,
) {
    private val failure = AtomicReference<Throwable?>()
    private val worker = Thread.ofPlatform().name("a4-owned-tracer-stderr").start {
        try {
            Files.newOutputStream(output, CREATE_NEW, WRITE).use { sink ->
                Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"))
                process.errorStream.use { input ->
                    val buffer = ByteArray(1024)
                    var written = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        val accepted = minOf(count, 65536 - written)
                        sink.write(buffer, 0, accepted)
                        sink.flush()
                        written += accepted
                        check(accepted == count) { "tracer stderr exceeds 64 KiB; oracle incomplete" }
                    }
                }
            }
        } catch (cause: Throwable) {
            failure.compareAndSet(null, cause)
        }
    }

    fun checkFailure() {
        failure.get()?.let { throw it }
    }

    fun finish() {
        worker.join(15000)
        check(!worker.isAlive) { "stderr writer is unresolved" }
        checkFailure()
    }
}

internal object TierEA4DiagnosticSupport {
    fun writeStartupRegistration(
        destination: Path,
        registration: String,
    ) {
        Files.newByteChannel(destination.resolve("startup-target.reg"), setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            val bytes = ByteBuffer.wrap(registration.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) check(channel.write(bytes) > 0) { "startup registration write made no progress" }
        }
    }

    val diagnosticSources: List<String> get() = (
        listOf(
        "profiler-native/src/agent.cpp",
        "profiler-native/include/open_boundary_recorder.hpp",
        "profiler-native/include/capture_yield_recorder.hpp",
        "profiler-native/CMakeLists.txt",
        "profiler-native/tests/TierEAllTargetWorkload.java",
        "profiler-native/tests/OpenBoundaryWorkload.java",
        "profiler-native/tests/open_boundary_recorder_test.cpp",
        "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt",
        "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt",
        "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBoundaryDiagnostics.kt",
        "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoder.kt",
        "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramLoadFailureEvidence.kt",
        "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt",
        "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt",
        "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt",
        "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEMissingOpenDiscriminatorIntegrationTest.kt",
        "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierEFixtureLifecycle.kt",
        "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupport.kt",
        "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt",
        "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureLifecycleTest.kt",
        "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilderTest.kt",
        "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEPreGateBytecodeExecutionTest.kt",
        "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBoundaryDiagnosticsTest.kt",
        "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierEProfilerSessionLifecycleTest.kt",
        "profiler/src/test/resources/tier-e-a4-tracer.py",
        "profiler/src/test/resources/tier-e-a4-fingerprints.py",
        "profiler/build.gradle.kts",
    ) +
        nativeSources
    ).distinct()

    fun diagnosticArtifactMaximum(path: String): Long =
        when (path) {
        "/fixture/evidence/prog-load-failure.diag", "/fixture/evidence/prog-load-failure.diag.tmp" -> io.mazewall.profiler.tierE.engine.TierEBpfProgramLoadFailureEvidence.MAXIMUM_BYTES
        "/fixture/evidence/ptrace-open.log" -> 16777216L
        "/fixture/work/open-calls.diag", "/fixture/work/open-calls.diag.tmp" -> 128L + 1024 * 384
        "/fixture/evidence/boundary.bpfdiag", "/fixture/evidence/boundary.bpfdiag.tmp" -> 256L + 32768 * 112
        else -> 32L * 1024 * 1024
    }

    fun publishReadyAndArm(
        attempt: DiagnosticAttempt,
        facts: DiagnosticReadyFacts,
        repository: Path,
        native: Path,
        destination: Path,
        input: Path,
        identities: Map<String, String>,
        creationOpen: () -> Boolean,
        release: () -> Unit,
    ) {
        attempt.freezeAndArmOnce(creationOpen, {
            requireRuntimeReady(facts)
            checkNativeProvenance(repository, native, destination)
            val requiredIdentities = setOf("target", "daemon", "tracer", "target_container", "collector_container", "image", "live_control", "attach", "tracer_argv", "time_namespace")
            require(identities.keys == requiredIdentities && identities.values.all { it.isNotBlank() }) { "missing checked runtime identity/readiness observations" }
            val sources = diagnosticSources.associateWith { name ->
                val path = repository.resolve(name)
                require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "missing diagnostic source: $name" }
                sha256(path)
            }
            require(Files.isRegularFile(input, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(input) in 1..4096) { "missing/bounded pre-ARM input" }
            val inputBytes = Files.readAllBytes(input)
            require(inputBytes.contentEquals("example".toByteArray(Charsets.US_ASCII))) { "unexpected fixture input" }
            val artifacts = listOf(
                "native-build-provenance.txt",
                "native-build-settings.txt",
                "native-compiler-version.txt",
                "source-sha256.txt",
                "runtime-fingerprints.txt",
                "ready-control.txt",
            ).associateWith { name ->
                val path = destination.resolve(name)
                require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(path) > 0) { "missing READY provenance: $name" }
                sha256(path)
            }
            val expectedSources = sources.entries.joinToString("\n") { (name, digest) -> "$digest  $name" } + "\n"
            require(Files.readString(destination.resolve("source-sha256.txt")) == expectedSources) { "diagnostic sources changed after fingerprint checks" }
            val commands = Files
                .newDirectoryStream(destination) { path ->
                path.fileName.toString().matches(Regex("command-[0-9]+\\.(?:txt|output)|(?:startup-)?target\\.reg|daemon\\.log|daemon\\.command|tracer-startup\\.txt"))
            }.use { paths ->
                paths
                    .associate { path ->
                    require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "invalid readiness evidence: $path" }
                    path.fileName.toString() to sha256(path)
                }.toSortedMap()
            }
            val receipt = kotlinx.serialization.json
                .buildJsonObject {
                put("schema_version", kotlinx.serialization.json.JsonPrimitive(1))
                put("state", kotlinx.serialization.json.JsonPrimitive("READY-before-ARM"))
                put(
                    "readiness",
                    kotlinx.serialization.json.buildJsonObject {
                    mapOf(
                        "attached" to facts.attached,
                        "gate_closed" to facts.gateClosed,
                        "receipts_allocated" to facts.receiptsAllocated,
                        "identity_pinned" to facts.identityPinned,
                        "tracer_pid_matches" to facts.tracerPidMatches,
                        "tracer_alive" to facts.tracerAlive,
                        "follows_clones" to facts.followsClones,
                        "limits_verified" to facts.limitsVerified,
                        "oracle_healthy" to facts.oracleHealthy,
                        "same_time_namespace" to facts.sameTimeNamespace,
                    ).forEach { (name, value) -> put(name, kotlinx.serialization.json.JsonPrimitive(value)) }
                },
                )
                put("sources", kotlinx.serialization.json.buildJsonObject { sources.forEach { (name, digest) -> put(name, kotlinx.serialization.json.JsonPrimitive(digest)) } })
                put("identities_and_observations", kotlinx.serialization.json.buildJsonObject { identities.forEach { (name, value) -> put(name, kotlinx.serialization.json.JsonPrimitive(value)) } })
                put("prebuilt_runtime_receipts", kotlinx.serialization.json.buildJsonObject { artifacts.forEach { (name, digest) -> put(name, kotlinx.serialization.json.JsonPrimitive(digest)) } })
                put(
                    "checked_command_and_owner_receipts",
                    kotlinx.serialization.json.buildJsonObject {
                    commands.forEach { (name, digest) ->
                        put(name, kotlinx.serialization.json.JsonPrimitive(digest))
                    }
                },
                )
                put(
                    "input",
                    kotlinx.serialization.json.buildJsonObject {
                    put("path", kotlinx.serialization.json.JsonPrimitive("/fixture/work/input"))
                    put("path_hex", kotlinx.serialization.json.JsonPrimitive("/fixture/work/input".toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }))
                    put("byte_length", kotlinx.serialization.json.JsonPrimitive(inputBytes.size))
                    put("sha256", kotlinx.serialization.json.JsonPrimitive(sha256(input)))
                },
                )
            }.toString() +
                "\n"
            val output = destination.parent.resolve("a4-discriminator-ready.json")
            val temporary = output.resolveSibling("${output.fileName}.tmp")
            FileChannel
                .open(
                temporary,
                setOf(CREATE_NEW, WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            ).use { channel ->
                val bytes = ByteBuffer.wrap(receipt.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) check(channel.write(bytes) > 0) { "READY write made no progress" }
                channel.force(true)
            }
            Files.createLink(output, temporary)
            Files.delete(temporary)
            facts.copy(fingerprintsComplete = true)
        }, release)
    }

    fun classifyRetainedProduction(
        native: NativeCallSnapshot,
        kernel: BoundaryDiagnosticSnapshot,
        trace: List<String>,
        methods: Map<Long, String>,
        lines: List<String>,
        terminal: String,
        target: Long,
        incarnation: Long,
        dictionary: Path,
    ): DiagnosticJoin {
        val defects = productionIntegrity(lines, terminal, native.session, target, incarnation, dictionary, kernel).toMutableList()
        val positive = joinObservedCalls(native.select(methods, defects.isEmpty()), kernel, trace)
        val joined = try {
            reconcileProduction(positive, methods, lines)
        } catch (failure: Exception) {
            defects += "production rows undecodable: ${failure.javaClass.name}: ${failure.message}"
            positive
        }
        if (defects.isEmpty()) return joined
        val reasons = (joined.reasons + defects).distinct()
        return DiagnosticJoin(joined.matched, reasons, DiagnosticOutcome.Incomplete(reasons), joined.missingIds, joined.ambiguousIds)
    }

    private fun productionIntegrity(
        lines: List<String>,
        terminal: String,
        session: Long,
        target: Long,
        incarnation: Long,
        dictionary: Path,
        kernel: BoundaryDiagnosticSnapshot,
    ): List<String> =
        try {
        val rows = lines.filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
        require(rows.size >= 2) { "missing production header/footer" }
        val header = rows.first()
        val footer = rows.last()
        val report = Json.parseToJsonElement(terminal).jsonObject
        val events = rows.drop(1).dropLast(1)

        fun expect(
            row: kotlinx.serialization.json.JsonObject,
            name: String,
            value: String,
        ) {
            require(row[name]?.jsonPrimitive?.content == value) { "production $name expected=$value actual=${row[name]}" }
        }
        for (row in rows + report) {
            expect(row, "schema_version", "1")
            expect(row, "registration_id", "orders")
            expect(row, "session_epoch", "$session")
        }
        expect(header, "type", "header")
        expect(footer, "type", "terminal")
        for ((index, event) in events.withIndex()) {
            expect(event, "type", "event")
            expect(event, "ordinal", "${index + 1}")
            expect(event, "host_tgid", "$target")
            require(event["resolution_status"]?.jsonPrimitive?.content in setOf("RESOLVED", "NO_ACTIVE_INVOCATION")) { "production resolution failure" }
            val flags = requireNotNull(event["capture_flags"]?.jsonPrimitive) { "missing production capture flags" }
            require(!flags.isString) { "malformed production capture flags" }
            val bits = requireNotNull(flags.content.toIntOrNull()) { "malformed production capture flags" }
            val resolved = event["resolution_status"]?.jsonPrimitive?.content == "RESOLVED"
            val allowedFlags = if (resolved) setOf(0) else setOf(SyscallInvocationEvent.FLAG_TASK_STORAGE_UNAVAILABLE, SyscallInvocationEvent.FLAG_INACTIVE_INVOCATION)
            require(bits in allowedFlags) { "contradictory production capture flags/resolution" }
        }
        for (row in listOf(header, report)) {
            expect(row, "target_pid", "$target")
            expect(row, "process_incarnation", "$incarnation")
            expect(row, "policy_eligibility", "false")
        }
        for ((name, value) in mapOf(
                "clock_domain" to "CLOCK_MONOTONIC",
                "observation_scope" to "SYS_ENTER_TRACEPOINT",
                "marker_version" to "v1",
                "stack_transport" to "session-bound-mzse-v2",
            )) {
                expect(header, name, value)
            }
        val resolved = events.count { it["resolution_status"]?.jsonPrimitive?.content == "RESOLVED" }
        for (field in listOf("event_count", "capture_admitted_count", "capture_submitted_count")) expect(footer, field, "${events.size}")
        expect(footer, "attributed_count", "$resolved")
        expect(footer, "unattributed_count", "${events.size - resolved}")
        val inactive = events.count { it.getValue("capture_flags").jsonPrimitive.content == SyscallInvocationEvent.FLAG_INACTIVE_INVOCATION.toString() }
        expect(footer, "ambient_inactive_count", "$inactive")
        require(kernel.complete && kernel.session == session && kernel.targetTgid == target) { "incomplete or mismatched kernel ambient evidence" }
        val inactiveReceipts = kernel.records.filter { it.plane == 2 && it.stage == BoundaryStage.MAIN_INACTIVE }
        require(
            inactiveReceipts.size == inactive &&
                inactiveReceipts.all {
                it.commit == 3 &&
                    it.pidTgid shr 32 == target.toULong() &&
                    it.validFields and 95u == 95u &&
                    it.words[0] == target.toULong() &&
                    it.words[1] == 1uL &&
                    it.words[2] == 1uL &&
                    it.words[3] in 1uL..UInt.MAX_VALUE.toULong() &&
                    it.words[4] == 1uL &&
                    it.invocation == 0uL
            },
        ) { "production/kernel inactive receipts differ" }
        for (field in listOf("capture_complete", "export_complete", "transport_complete")) expect(footer, field, "true")
        for (field in listOf("observed_loss_count", "marker_state_unavailable_count")) expect(footer, field, "0")
        for (row in listOf(footer, report)) expect(row, "terminal_reason", "COMPLETE")
        expect(footer, "policy_eligibility", "false")
        for (field in listOf("capture_domain_validity", "transport_completeness", "dictionary_integrity", "lifecycle_validity")) expect(report, field, "true")
        expect(report, "resolved_syscalls", "$resolved")
        val attributed = events.size == resolved
        expect(footer, "attribution_complete", "$attributed")
        expect(report, "attribution_coverage", "$attributed")
        expect(report, "evidence_integrity", if (attributed) "COMPLETE" else "INCOMPLETE")
        val failed = report
            .getValue("failed_dimensions")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        require(failed == if (attributed) emptySet() else setOf("attribution_coverage")) { "unexpected failed production dimensions" }
        require(Files.isRegularFile(dictionary, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "missing retained dictionary" }
        expect(report, "artifact_byte_length", "${Files.size(dictionary)}")
        expect(report, "artifact_sha256", sha256(dictionary))
        emptyList()
    } catch (failure: Exception) {
        listOf("production integrity incomplete: ${failure.javaClass.name}: ${failure.message}")
    }

    private const val nativeWorkspace = ".superpowers/sdd/2026-11-03-tier-e-improvement-roadmap"
    private const val nativeDirectory = "$nativeWorkspace/a4-yield-diagnostic-native-001"
    private val nativeSources = listOf(
        "profiler-native/CMakeLists.txt",
        "profiler-native/src/agent.cpp",
        "profiler-native/src/live_stack_context.cpp",
        "profiler-native/src/live_stack_jni.cpp",
    ) +
        listOf(
        "binding_manifest.hpp",
            "capture_profile.hpp",
            "capture_timing_diagnostic.hpp",
            "capture_yield_recorder.hpp",
        "invocation_fd_recorder.hpp",
            "invocation_registry.hpp",
            "open_boundary_recorder.hpp",
            "lifecycle_barrier.hpp",
        "live_stack_context.hpp",
            "live_stack_context_abi.h",
            "live_stack_jni.hpp",
            "marker_version_proof.hpp",
        "symbolization_generation.hpp",
            "tier_e_usdt_marker.hpp",
    ).map { "profiler-native/include/$it" } +
        listOf(
        "open_boundary_recorder_test.cpp",
            "capture_yield_recorder_test.cpp",
            "capture_yield_agent_test.cpp",
        "capture_yield_file_test.cpp",
            "marker_export_test.cpp",
            "marker_version_proof_test.cpp",
            "usdt_marker_note_test.cpp",
        "AgentWorkload.java",
            "OpenatProfileWorkload.java",
            "AgentCoverageWorkload.java",
            "NativeOutcomeWorkload.java",
        "InvocationFdWorkload.java",
            "TierEAllTargetWorkload.java",
            "OpenBoundaryWorkload.java",
    ).map { "profiler-native/tests/$it" }

    private val nativeTargets = listOf(
        "mazewall_stack_agent",
        "open_boundary_recorder_test",
        "capture_yield_recorder_test",
        "capture_yield_agent_test",
        "capture_yield_file_test",
        "marker_export_test",
        "marker_version_proof_test",
        "usdt_marker_note_test",
    )
    private val nativeGenerated = listOf(
        "CMakeCache.txt",
        "CMakeFiles/4.2.3/CMakeCXXCompiler.cmake",
        "CMakeFiles/mazewall_stack_agent.dir/flags.make",
        "CMakeFiles/mazewall_stack_agent.dir/build.make",
        "CMakeFiles/mazewall_stack_agent.dir/link.txt",
        "CMakeFiles/agent_workload_test_classes.dir/build.make",
        "CTestTestfile.cmake",
    ) +
        nativeTargets.drop(1).flatMap { target -> listOf("flags.make", "build.make", "link.txt").map { "CMakeFiles/$target.dir/$it" } }
    private val nativeReceipts = listOf("configure", "build", "ctest", "source-before", "dependencies", "hashes")
        .map { "$nativeWorkspace/a4-yield-diagnostic-001-$it.log" }
    private val nativeCopies = (nativeReceipts + nativeGenerated.map { "$nativeDirectory/$it" }).mapIndexed { index, name ->
        name to "native-evidence-${index.toString().padStart(3, '0')}-${Path.of(name).fileName}"
    }
    private val captureYieldCases = listOf(
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
    private val nativeRequiredTests = listOf(
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
        captureYieldCases.map { "capture_yield_agent_$it" }

    private fun requireOwnedFile(
        repository: Path,
        name: String,
    ): Path {
        val relative = Path.of(name)
        require(!relative.isAbsolute && relative.normalize() == relative && relative.none { it.toString() in setOf(".", "..") }) { "invalid owned path: $name" }
        val path = repository.resolve(relative)
        require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path) { "missing/aliased provenance: $name" }
        return path
    }

    private fun nativeFingerprintText(facts: Map<String, String>): String = facts.entries.joinToString("\n") { (name, digest) -> "$digest  $name" } + "\n"

    fun copyNativeProvenance(
        repository: Path,
        native: Path,
        destination: Path,
    ) {
        require(destination.isAbsolute && destination.normalize() == destination && destination.toRealPath() == destination) { "invalid provenance destination" }
        val facts = validatePrebuiltNative(repository, native)
        for ((name, output) in nativeCopies) {
            val source = requireOwnedFile(repository, name)
            FileChannel
                .open(
                destination.resolve(output),
                setOf(CREATE_NEW, WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            ).use { sink ->
                Files.newByteChannel(source, setOf(java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)).use { input ->
                    val buffer = ByteBuffer.allocate(4096)
                    while (input.read(buffer) != -1) {
                        buffer.flip()
                        while (buffer.hasRemaining()) check(sink.write(buffer) > 0) { "provenance copy made no progress: $name" }
                        buffer.clear()
                    }
                }
            }
            require(sha256(requireOwnedFile(repository, name)) == facts.getValue(name) && sha256(destination.resolve(output)) == facts.getValue(name)) { "native copy changed: $name" }
        }
        FileChannel
            .open(
            destination.resolve("native-build-provenance.txt"),
            setOf(CREATE_NEW, WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        ).use { channel ->
            val bytes = ByteBuffer.wrap(nativeFingerprintText(facts).toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) check(channel.write(bytes) > 0) { "provenance write made no progress" }
        }
        checkNativeProvenance(repository, native, destination)
    }

    fun checkNativeProvenance(
        repository: Path,
        native: Path,
        destination: Path,
    ) {
        require(destination.isAbsolute && destination.normalize() == destination && destination.toRealPath() == destination) { "invalid provenance destination" }
        val facts = validatePrebuiltNative(repository, native)
        val fingerprint = requireOwnedFile(destination, "native-build-provenance.txt")
        require(Files.readString(fingerprint) == nativeFingerprintText(facts)) { "prebuilt provenance changed before READY" }
        for ((name, output) in nativeCopies) {
            require(sha256(requireOwnedFile(destination, output)) == facts.getValue(name)) { "retained native copy changed: $output" }
        }
    }

    fun validatePrebuiltNative(
        repository: Path,
        native: Path,
    ): Map<String, String> {
        require(repository.isAbsolute && repository.normalize() == repository && repository.toRealPath() == repository) { "not the real normalized checkout" }
        require(native == repository.resolve(nativeDirectory)) { "not the approved direct native build" }
        val retained = linkedMapOf<String, String>()
        val members =
            nativeSources +
                listOf("$nativeDirectory/libmazewall_stack_agent.so", "$nativeDirectory/agent-test-classes/TierEAllTargetWorkload.class") +
                nativeGenerated.map { "$nativeDirectory/$it" } +
                nativeReceipts.dropLast(1)
        val hashReceipt = requireOwnedFile(repository, nativeReceipts.last())
        val hashes = Files.readAllLines(hashReceipt).filter(String::isNotBlank).map { line ->
            require(line.matches(Regex("[a-f0-9]{64}  .+"))) { "invalid native hash receipt" }
            line.substringAfter("  ") to line.substringBefore("  ")
        }
        require(hashes.size == members.size && hashes.map { it.first }.toSet() == members.toSet()) { "incomplete/duplicate/extra native membership" }
        val expectedHashes = hashes.toMap()
        for (name in members + nativeReceipts.last()) {
            val digest = sha256(requireOwnedFile(repository, name))
            require(name == nativeReceipts.last() || digest == expectedHashes.getValue(name)) { "native provenance changed: $name" }
            retained[name] = digest
        }

        fun read(name: String): String = Files.readString(requireOwnedFile(repository, name))

        fun generated(name: String): String = read("$nativeDirectory/$name")
        val cache = generated("CMakeCache.txt")
            .lineSequence()
            .filter { it.matches(Regex("[A-Za-z0-9_]+:[A-Z]+=.*")) }
            .map { it.substringBefore(':') to it.substringAfter('=') }
            .toList()
        require(cache.map { it.first }.distinct().size == cache.size) { "duplicate CMake cache facts" }
        val fields = cache.toMap()
        val expected = mapOf(
            "CMAKE_HOME_DIRECTORY" to repository.resolve("profiler-native").toString(),
            "CMAKE_CACHEFILE_DIR" to native.toString(),
            "CMAKE_BUILD_TYPE" to "Release",
            "CMAKE_CXX_FLAGS" to "",
            "CMAKE_CXX_FLAGS_RELEASE" to "-O3 -DNDEBUG",
            "MAZEWALL_ENABLE_TIER_E_USDT" to "OFF",
            "CMAKE_CACHE_MAJOR_VERSION" to "4",
            "CMAKE_CACHE_MINOR_VERSION" to "2",
            "CMAKE_CACHE_PATCH_VERSION" to "3",
        )
        expected.forEach { (key, value) -> require(fields[key] == value) { "invalid direct CMake $key" } }
        val toolchain = Json
            .parseToJsonElement(read(nativeReceipts[3]))
            .jsonObject
            .getValue("external")
            .jsonObject

        fun compilerRole(field: String): Path {
            val lexical = requireNotNull(fields[field]) { "missing compiler: $field" }
            val compiler = Path.of(lexical)
            require(
                compiler.toString() == lexical &&
                compiler.isAbsolute &&
                compiler.normalize() == compiler &&
                compiler.none {
                it.toString() in setOf(".", "..")
            } &&
                Files.isRegularFile(compiler) &&
                Files.isExecutable(compiler),
            ) { "compiler unavailable: $field" }
            val fact = requireNotNull(toolchain[compiler.toString()]) { "compiler lexical identity missing from frozen toolchain: $field" }.jsonObject
            val canonical = compiler.toRealPath()
            val digest = sha256(canonical)
            require(fact["canonical"]?.jsonPrimitive?.content == canonical.toString() && fact["sha256"]?.jsonPrimitive?.content == digest) { "compiler differs from frozen toolchain: $field" }
            for (key in listOf("compiler:$compiler", "compiler-target:$canonical")) {
                require(retained[key] == null || retained[key] == digest) { "conflicting compiler roles" }
                retained[key] = digest
            }
            return compiler
        }
        val compiler = compilerRole("CMAKE_CXX_COMPILER")
        val compilerInfo = generated("CMakeFiles/4.2.3/CMakeCXXCompiler.cmake")
        require(compilerInfo.contains("set(CMAKE_CXX_COMPILER \"$compiler\")")) { "compiler/cache mismatch" }
        val compilerId = Regex("set\\(CMAKE_CXX_COMPILER_ID \"([^\"]+)\"\\)").find(compilerInfo)?.groupValues?.get(1)
        val compilerVersion = Regex("set\\(CMAKE_CXX_COMPILER_VERSION \"([^\"]+)\"\\)").find(compilerInfo)?.groupValues?.get(1)
        require(compilerId == "GNU" && compilerVersion == "15.2.0") { "invalid configured compiler identity" }
        val configure = read(nativeReceipts[0])
        require(
            configure.contains("compiler identification is $compilerId $compilerVersion") &&
                configure.contains("-- Configuring done") &&
                configure.contains("-- Generating done") &&
                configure.contains("-- Build files have been written to: $native"),
        ) { "invalid direct configure receipt" }
        val build = read(nativeReceipts[1])
        require(build.contains("Built target mazewall_stack_agent") && build.contains("Built target agent_workload_test_classes")) { "incomplete prebuilt receipt" }
        val ctest = read(nativeReceipts[2])
        val lines = ctest.lines()
        val summaries = lines.filter { it.contains("tests passed,") }
        val summary = summaries.singleOrNull()?.let { Regex("100% tests passed, 0 tests failed out of ([1-9][0-9]*)").matchEntire(it) }
        val count = summary?.groupValues?.get(1)?.toLongOrNull()
        val requiredTests = nativeRequiredTests
        val resultRows = lines.filter { Regex("\\bTest\\s+#[0-9]+:").containsMatchIn(it) }
        val passedRows = resultRows.mapNotNull { Regex("\\s*([1-9][0-9]*)/$count\\s+Test\\s+#[1-9][0-9]*:\\s+([^ ]+)\\s+\\.+\\s+Passed\\s+[0-9]+\\.[0-9]+ sec\\s*").matchEntire(it) }
        require(
            count != null &&
                count >= requiredTests.size &&
                resultRows.size.toLong() == count &&
                passedRows.size == resultRows.size &&
                passedRows.map { it.groupValues[1].toLong() }.toSet() == (1L..count).toSet() &&
                passedRows.map { it.groupValues[2] }.distinct().size == resultRows.size &&
                requiredTests.all { name ->
                    val results = lines.filter { Regex("\\bTest\\s+#[0-9]+:\\s+${Regex.escape(name)}(?:\\s|\\.)").containsMatchIn(it) }
                    results.size == 1 && Regex("\\s*[1-9][0-9]*/$count\\s+Test\\s+#[1-9][0-9]*:\\s+${Regex.escape(name)}\\s+\\.+\\s+Passed\\s+[0-9]+\\.[0-9]+ sec\\s*").matches(results.single())
                } &&
                lines.filter { it.startsWith("EXIT_STATUS=") }.singleOrNull()?.matches(Regex("EXIT_STATUS=0(?: ELAPSED_SECONDS=[0-9]+\\.[0-9]+)?")) == true &&
                lines.lastOrNull { it.isNotBlank() }?.startsWith("EXIT_STATUS=") == true,
        ) { "native verification receipt missing" }
        val workloadSource = repository.resolve("profiler-native/tests/TierEAllTargetWorkload.java")
        val workloadClass = native.resolve("agent-test-classes/TierEAllTargetWorkload.class")
        validateNativeGenerated(repository, native, compiler, requireNotNull(fields["Java_JAVA_EXECUTABLE"]) { "missing configured Java launcher" }, ::generated)
        val javac = compilerRole("Java_JAVAC_EXECUTABLE")
        val workloadRules = generated("CMakeFiles/agent_workload_test_classes.dir/build.make")
        val javaSources = listOf("AgentWorkload", "OpenatProfileWorkload", "AgentCoverageWorkload", "NativeOutcomeWorkload", "InvocationFdWorkload", "TierEAllTargetWorkload", "OpenBoundaryWorkload")
        val javacCommand = "$javac -d $native/agent-test-classes " + javaSources.joinToString(" ") { "${repository.resolve("profiler-native/tests/$it.java")}" }
        require(
            workloadRules.contains("agent-test-classes/TierEAllTargetWorkload.class:") &&
                workloadRules.lineSequence().any { line ->
                    line.trim() == javacCommand
                },
        ) { "generated workload rule does not bind approved source/compiler/output" }
        require(
            Files.isRegularFile(workloadSource, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(workloadClass, java.nio.file.LinkOption.NOFOLLOW_LINKS),
        ) { "missing workload provenance" }
        require(Files.getLastModifiedTime(workloadClass) >= Files.getLastModifiedTime(workloadSource)) { "workload class predates source" }
        retained[repository.relativize(workloadSource).toString()] = sha256(workloadSource)
        retained[repository.relativize(workloadClass).toString()] = sha256(workloadClass)
        return retained.toMap()
    }

    private fun validateNativeGenerated(
        repository: Path,
        native: Path,
        compiler: Path,
        java: String,
        generated: (String) -> String,
    ) {
        for (target in nativeTargets) {
            val prefix = "CMakeFiles/$target.dir"
            val flags = generated("$prefix/flags.make")
            val production = target == "mazewall_stack_agent"
            val expectedFlags = if (production) "-O3 -DNDEBUG -std=c++20 -fPIC -Wall -Wextra -Werror" else "-O3 -DNDEBUG -std=c++20 -Wall -Wextra -Werror -UNDEBUG"
            require(
                flags.lines().singleOrNull { it.startsWith("CXX_FLAGS = ") } == "CXX_FLAGS = $expectedFlags" && flags.contains("# compile CXX with $compiler\n"),
            ) { "generated compiler/build flags: $target" }
            val define = when (target) {
                "mazewall_stack_agent" -> "-Dmazewall_stack_agent_EXPORTS"
                "capture_yield_agent_test" -> "-DMAZEWALL_CAPTURE_YIELD_TEST"
                else -> ""
            }
            require(flags.lines().singleOrNull { it.startsWith("CXX_DEFINES = ") } == "CXX_DEFINES = $define") { "unexpected native definitions: $target" }
            val sharedUnits = target in listOf("mazewall_stack_agent", "open_boundary_recorder_test", "capture_yield_agent_test")
            val sourceNames = if (production) {
                listOf("src/agent.cpp", "src/live_stack_context.cpp", "src/live_stack_jni.cpp")
            } else {
                listOf("tests/$target.cpp") +
                if (sharedUnits) listOf("src/live_stack_context.cpp", "src/live_stack_jni.cpp") else emptyList()
            }
            if (sharedUnits) {
                for (unit in listOf("live_stack_context", "live_stack_jni")) {
                    val option = "# Custom options: $prefix/src/$unit.cpp.o_OPTIONS = -fvisibility=hidden;-fvisibility-inlines-hidden"
                    require(flags.lines().count { it == option } == 1) { "source-specific visibility: $target/$unit" }
                }
            }
            val includes = flags.lines().singleOrNull { it.startsWith("CXX_INCLUDES = ") }.orEmpty()
            if (target !in listOf("marker_export_test", "usdt_marker_note_test")) {
                require("-I${repository.resolve("profiler-native/include")}" in includes.split(' ')) { "missing owned include: $target" }
            }
            if (sharedUnits) {
                require(Regex("-I[^ ]+/include(?: |$)").containsMatchIn(includes.substringAfter("profiler-native/include")) && Regex("-I[^ ]+/include/linux(?: |$)").containsMatchIn(includes)) {
                    "missing JNI include: $target"
                }
            }
            val rules = generated("$prefix/build.make")
            for (source in sourceNames) {
                require(rules.lines().count { it == "$prefix/$source.o: ${repository.resolve("profiler-native/$source")}" } == 1) { "generated source/object contract: $target/$source" }
                val command = "\$(CXX_DEFINES) \$(CXX_INCLUDES) \$(CXX_FLAGS)" + if (source.startsWith("src/live_stack_")) " -fvisibility=hidden -fvisibility-inlines-hidden" else ""
                require(
                    rules.lines().count {
                        it.trim() == "$compiler $command -MD -MT $prefix/$source.o -MF $prefix/$source.o.d -o $prefix/$source.o -c ${repository.resolve(
                        "profiler-native/$source",
                    )}"
                    } ==
                        1,
                ) { "generated compile contract: $target/$source" }
            }
            val link = generated("$prefix/link.txt").trim().split(Regex("\\s+"))
            val objects = sourceNames.map { "$prefix/$it.o" }
            require(
                link.firstOrNull() == compiler.toString() &&
                    link.filter { it.endsWith(".o") } == objects &&
                    link.containsAll(listOf("-O3", "-DNDEBUG", "-o", if (production) "libmazewall_stack_agent.so" else target)) &&
                    link.none { "sanitize" in it || "MAZEWALL_CAPTURE_YIELD_TEST" in it },
            ) { "generated link contract: $target" }
            if (production) require(link.containsAll(listOf("-fPIC", "-shared", "-Wl,-soname,libmazewall_stack_agent.so"))) { "production shared link contract" }
            val wrappers = when (target) {
                "open_boundary_recorder_test" -> listOf("__cxa_thread_atexit", "_Znwm", "_ZnwmRKSt9nothrow_t")
                "capture_yield_agent_test" -> listOf("_Znwm")
                else -> emptyList()
            }
            require(link.filter { it.startsWith("-Wl,--wrap=") } == wrappers.map { "-Wl,--wrap=$it" }) { "allocation/link wrappers: $target" }
            if (target == "marker_export_test") require("-ldl" in link) { "marker export link contract" }
        }
        val registrations = generated("CTestTestfile.cmake").lines()
        val library = "$native/libmazewall_stack_agent.so"
        val classpath = listOf("-cp", "$native/agent-test-classes")
        val contracts = linkedMapOf(
            "open_boundary_recorder_test" to listOf("$native/open_boundary_recorder_test"),
            "open_boundary_export_test" to listOf("$native/open_boundary_recorder_test", "--exports", library),
            "capture_yield_recorder_test" to listOf("$native/capture_yield_recorder_test"),
            "capture_yield_file_test" to listOf("$native/capture_yield_file_test", "$native/tier-e-capture-yield.mzcy"),
            "marker_export_test" to listOf("$native/marker_export_test", library),
            "marker_version_proof_test" to listOf("$native/marker_version_proof_test"),
            "usdt_marker_note_test" to listOf("$native/usdt_marker_note_test", library, "disabled"),
            "agent_open_boundary_enabled" to listOf(java) + classpath + listOf("OpenBoundaryWorkload", "enabled", library),
            "agent_open_boundary_disabled" to listOf(java) + classpath + listOf("OpenBoundaryWorkload", "disabled", library),
            "agent_native_outcome_preservation" to
                listOf(java, "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED", "-agentpath:$library=benchmark-phases,capture-profile=jdk25-unix-openat0,force-capture-failure-once") +
                    classpath +
                    "NativeOutcomeWorkload",
            "agent_smoke_test" to listOf(java, "-agentpath:$library") + classpath + "AgentWorkload",
            "agent_virtual_smoke_test" to listOf(java, "-agentpath:$library") + classpath + listOf("AgentWorkload", "0", "virtual", "20"),
            "agent_capture_yield_sidecar" to
                listOf(java, "-agentpath:$library=benchmark-phases,capture-yield=$native/tier-e-capture-yield.mzcy") + classpath + listOf("AgentWorkload", "0", "platform", "10"),
        )
        for ((name, argv) in contracts) {
            val expected = "add_test([=[$name]=] " + argv.joinToString(" ") { "\"$it\"" } + ")"
            require(registrations.count { it == expected } == 1 && registrations.count { it.startsWith("add_test([=[$name]=]") } == 1) { "CTest registration: $name" }
        }
        for ((name, property) in mapOf(
            "agent_native_outcome_preservation" to "PASS_REGULAR_EXPRESSION \"native-outcome-workload: return=preserved exception=preserved errno=2\"",
            "agent_smoke_test" to "PASS_REGULAR_EXPRESSION \"proxied-binds=[1-9][0-9]* stack-captures=[1-9][0-9]*\"",
            "agent_virtual_smoke_test" to "PASS_REGULAR_EXPRESSION \"proxied-binds=[1-9][0-9]* stack-captures=[1-9][0-9]*\"",
            "agent_capture_yield_sidecar" to "PASS_REGULAR_EXPRESSION \"capture-yield=written\"",
            "capture_yield_file_test" to "DEPENDS \"agent_capture_yield_sidecar\"",
        )) {
            require(registrations.singleOrNull { it.startsWith("set_tests_properties([=[$name]=] PROPERTIES ") }?.contains(" $property ") == true) { "CTest properties: $name" }
        }
        for (case in captureYieldCases) {
            val name = "capture_yield_agent_$case"
            val extra = if (case == "full") " \"$native/capture_yield_file_test\"" else ""
            require(
                registrations.count {
                it == "add_test([=[$name]=] \"$native/capture_yield_agent_test\" \"$case\"$extra)"
            } == 1 &&
                registrations.count { it.startsWith("add_test([=[$name]=]") } == 1,
            ) { "CTest registration: $name" }
            val properties = registrations.singleOrNull { it.startsWith("set_tests_properties([=[$name]=] PROPERTIES ") }.orEmpty()
            require(properties.contains(" TIMEOUT \"30\" ")) { "CTest timeout: $name" }
            if (case ==
                "unload"
            ) {
                    require(properties.contains(" FAIL_REGULAR_EXPRESSION \"capture-yield=written\" PASS_REGULAR_EXPRESSION \"capture-yield=failed\" ")) { "failed-unload PASS/FAIL properties" }
                }
        }
    }

    fun sha256(path: Path): String =
        Files.newInputStream(path).use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun withPreparedAttempt(
        destination: Path,
        body: (DiagnosticAttempt) -> Unit,
    ) {
        var owned = false
        try {
            if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "attempt destination is not a real directory" }
                Files.newDirectoryStream(destination).use { require(!it.iterator().hasNext()) { "attempt destination is reused/nonempty" } }
                owned = true
                Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rwx------"))
            } else {
                Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                owned = true
            }
            body(DiagnosticAttempt(destination.resolve("attempt.state")))
        } catch (failure: Throwable) {
            if (owned) {
                try {
                    val outcome = destination.resolve("diagnostic-outcome.txt")
                    if (!Files.exists(outcome, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        Files.newByteChannel(outcome, setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
                            val bytes = ByteBuffer.wrap("INCOMPLETE pre-ARM/preparation or lifecycle failure\n${failure.message}\n".toByteArray())
                            while (bytes.hasRemaining()) check(channel.write(bytes) > 0) { "outcome write made no progress" }
                        }
                    }
                } catch (publication: Throwable) {
                    throw CleanupFailures.merge(failure, publication)
                }
            }
            throw failure
        }
    }

    fun reconcileProduction(
        join: DiagnosticJoin,
        methods: Map<Long, String>,
        lines: List<String>,
    ): DiagnosticJoin {
        val coldNames = methods.values.toSet()
        val observed = lines.filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.mapNotNull { row ->
            val syscall = row["syscall_number"]?.jsonPrimitive?.content
            if (syscall !in setOf("257", "437")) return@mapNotNull null
            val rawId = row["invocation_id"]?.jsonPrimitive?.content ?: "<missing>"
            val id = rawId.toLongOrNull()?.takeIf { it > 0 }
            val names = row["frames"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonObject["method_name"]?.jsonPrimitive?.content }
                .filter { it in coldNames }
                .toSet()
            if ((id == null || id !in methods) && names.isEmpty()) return@mapNotNull null
            ProductionColdOpen(id, rawId, names, requireNotNull(syscall), row["resolution_status"]?.jsonPrimitive?.content ?: "<missing>")
        }
        val counts = observed.mapNotNull { it.id }.groupingBy { it }.eachCount()
        val resolvedIds = observed.filter { it.resolution == "RESOLVED" }.mapNotNull { it.id }.toSet()
        val unresolved = observed.filter { it.resolution != "RESOLVED" }
        val missing = methods.keys - resolvedIds
        val unexpected = counts.keys - methods.keys
        val duplicates = counts.filterValues { it != 1 }.keys
        val invalid = observed
            .filter { it.id == null }
            .map { it.rawId }
            .distinct()
            .sorted()
        val methodConflicts = observed.filter { it.id != null && it.id in methods && it.methods != setOf(methods.getValue(it.id)) }.mapNotNull { it.id }.toSet()
        val syscallConflicts = observed.filter { it.syscall != "257" }.mapNotNull { it.id }.toSet()
        val localized = when (val outcome = join.outcome) {
            is DiagnosticOutcome.LocalizedBoundary -> outcome.invocationId
            DiagnosticOutcome.InconclusiveBaseline, is DiagnosticOutcome.Incomplete -> null
        }
        val unexplainedMissing = missing - setOfNotNull(localized)
        val conflicts = mutableListOf<String>()
        if (localized != null && localized in resolvedIds) conflicts += "resolved_localized_id=$localized"
        if (localized != null && localized !in methods) conflicts += "unexpected_localized_id=$localized"
        if (unexplainedMissing.isNotEmpty()) conflicts += "unexplained_missing_ids=${unexplainedMissing.sorted()}"
        if (unexpected.isNotEmpty()) conflicts += "unexpected_ids=${unexpected.sorted()}"
        if (duplicates.isNotEmpty()) conflicts += "duplicate_ids=${duplicates.sorted()}"
        if (invalid.isNotEmpty()) conflicts += "invalid_ids=$invalid"
        if (methodConflicts.isNotEmpty()) conflicts += "method_conflict_ids=${methodConflicts.sorted()}"
        if (syscallConflicts.isNotEmpty()) conflicts += "unexpected_syscall_ids=${syscallConflicts.sorted()}"
        if (unresolved.isNotEmpty()) conflicts += "unresolved_production_rows=${unresolved.map { "${it.rawId}:${it.resolution}" }.sorted()}"
        if (methods.values.groupingBy { it }.eachCount() != mapOf("readConfiguration" to 20, "loadTemplate" to 20)) conflicts += "closed cold definitions are not 20+20"
        if (conflicts.isEmpty()) return join
        val reasons = (join.reasons + (join.outcome as? DiagnosticOutcome.Incomplete)?.reasons.orEmpty() + conflicts.map { "production source conflict: $it" }).distinct()
        return DiagnosticJoin(
            join.matched,
            reasons,
            DiagnosticOutcome.Incomplete(reasons),
            join.missingIds + missing,
            join.ambiguousIds + unexpected + duplicates + methodConflicts + syscallConflicts + setOfNotNull(localized?.takeIf { it in counts }),
        )
    }

    private data class ProductionColdOpen(
        val id: Long?,
        val rawId: String,
        val methods: Set<String>,
        val syscall: String,
        val resolution: String,
    )

    fun finishDiagnosticAttempt(
        exercise: () -> Unit,
        quiesce: () -> Unit,
        retain: () -> Unit,
        classify: () -> DiagnosticJoin,
        publish: (DiagnosticJoin, Boolean) -> Unit,
    ) {
        var primary: Throwable? = null
        try {
            exercise()
        } catch (failure: Throwable) {
            primary = failure
        }
        val evidenceErrors = mutableListOf<String>()

        fun evidence(
            label: String,
            effect: () -> Unit,
        ): Boolean {
            try {
                effect()
                return true
            } catch (failure: Throwable) {
                primary = CleanupFailures.merge(primary, failure)
                evidenceErrors += "$label failed: ${failure.javaClass.name}: ${failure.message}"
                return false
            }
        }
        val stopped = evidence("writer quiescence", quiesce)
        val retained = evidence("mandatory retention", retain)
        val joined = try {
            classify()
        } catch (failure: Throwable) {
            primary = CleanupFailures.merge(primary, failure)
            evidenceErrors += "classification failed: ${failure.javaClass.name}: ${failure.message}"
            DiagnosticJoin(emptyList(), emptyList(), DiagnosticOutcome.Incomplete(emptyList()), emptySet(), emptySet())
        }
        val result = if (evidenceErrors.isEmpty()) {
            joined
        } else {
            val reasons = (joined.reasons + (joined.outcome as? DiagnosticOutcome.Incomplete)?.reasons.orEmpty() + evidenceErrors).distinct()
            DiagnosticJoin(joined.matched, reasons, DiagnosticOutcome.Incomplete(reasons), joined.missingIds, joined.ambiguousIds)
        }
        try {
            publish(result, stopped && retained)
        } catch (failure: Throwable) {
            primary = CleanupFailures.merge(primary, failure)
        }
        if (result.outcome is DiagnosticOutcome.Incomplete) primary = CleanupFailures.merge(primary, IllegalStateException(diagnosticOutcomeText(result)))
        primary?.let { throw it }
    }

    fun publishDiagnosticOutcome(
        destination: Path,
        join: DiagnosticJoin,
        retained: Boolean,
    ) {
        if (!retained) Files.writeString(destination.resolve("export-status.txt"), "INCOMPLETE mandatory retention did not complete; source retained\n")
        Files.writeString(destination.resolve("diagnostic-outcome.txt"), diagnosticOutcomeText(join))
    }

    private fun diagnosticOutcomeText(join: DiagnosticJoin): String {
        val text = when (val outcome = join.outcome) {
            DiagnosticOutcome.InconclusiveBaseline -> "INCONCLUSIVE instrumented 20+20 baseline; not fixed/qualified\n"
            is DiagnosticOutcome.Incomplete -> "INCOMPLETE\n${outcome.reasons.joinToString("\n")}\n"
            is DiagnosticOutcome.LocalizedBoundary -> "LOCALIZED invocation=${outcome.invocationId} stage=${outcome.stage} records=${outcome.recordOrdinals}\n"
        }
        return text + "positive_joined_ids=${join.matched.map { it.invocationId }.sorted()}\n"
    }

    fun oracleStderrLineHealthy(line: String): Boolean = line.isEmpty() || Regex("strace: Process [1-9][0-9]* attached(?: with [1-9][0-9]* threads)?").matches(line)

    fun retainEncodedArtifact(
        exitCode: Int,
        encoded: Path,
        output: Path,
        maximum: Long,
    ) {
        check(exitCode == 0) { "mandatory artifact export failed exit=$exitCode" }
        require(maximum > 0 && Files.size(encoded) <= ((maximum + 2) / 3) * 4) { "encoded artifact exceeds bound" }
        java.util.Base64.getDecoder().wrap(Files.newInputStream(encoded, java.nio.file.LinkOption.NOFOLLOW_LINKS)).use { input ->
            Files.newOutputStream(output, CREATE_NEW, WRITE).use { sink ->
                Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"))
                val buffer = ByteArray(1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    check(total <= maximum) { "decoded artifact exceeds bound" }
                    sink.write(buffer, 0, count)
                }
            }
        }
        java.util.Base64.getDecoder().wrap(Files.newInputStream(encoded, java.nio.file.LinkOption.NOFOLLOW_LINKS)).use { original ->
            Files.newInputStream(output, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { retained ->
                val a = ByteArray(1024)
                val b = ByteArray(1024)
                while (true) {
                    val count = original.readNBytes(a, 0, a.size)
                    check(retained.readNBytes(b, 0, b.size) == count && java.util.Arrays.mismatch(a, 0, count, b, 0, count) == -1) { "retained artifact bytes differ" }
                    if (count == 0) break
                }
            }
        }
    }

    fun hasTextLine(
        path: Path,
        maximum: Long,
        requireTerminated: Boolean = false,
        predicate: (String) -> Boolean,
    ): Boolean {
        var matched = false
        Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(1024)
            val line = StringBuilder()
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= maximum) { "text evidence exceeds bound" }
                for (index in 0 until count) {
                    val value = buffer[index].toInt() and 255
                    require(value in 9..126) { "non-ASCII diagnostic text" }
                    if (value == 10) {
                        matched = predicate(line.toString()) || matched
                        line.setLength(0)
                    } else {
                        require(line.length < 4096) { "diagnostic text line exceeds bound" }
                        line.append(value.toChar())
                    }
                }
            }
            require(!requireTerminated || line.isEmpty()) { "partial final text line" }
        }
        return matched
    }

    fun selectColdMethods(
        dictionary: io.mazewall.profiler.internal.attribution.AgentDefinitionFile.Contents,
        yield: io.mazewall.profiler.tierE.stress.TierECaptureYield.Report,
    ): Map<Long, String> {
        require(dictionary.publication?.closed == true && dictionary.publication.sessionEpoch?.value == yield.sessionTag && dictionary.agentLosses == 0L)
        require(
            yield.records.size < 65536 &&
            yield.records
            .map { it.invocationId }
            .toSet()
            .size == yield.records.size,
        )
        val joined = io.mazewall.profiler.tierE.stress.TierECaptureYield
            .join(yield, dictionary.invocations)
        require(joined.missingDefinition == 0 && joined.outcomeMismatch == 0 && joined.unrecordedDefinitions == 0)
        val openIds = yield.records
            .filter { it.bindingCode == 1 }
            .map { it.invocationId }
            .toSet()
        val methods = dictionary.invocations
            .mapNotNull { invocation ->
            if (invocation.invocationId.value !in openIds) return@mapNotNull null
            val stack = dictionary.stacks.singleOrNull { it.stackTraceId == invocation.stackTraceId }
            val cold = stack
                ?.frames
                ?.filter { it.className in setOf("TierEAllTargetWorkload", "LTierEAllTargetWorkload;") }
                ?.map { it.methodName }
                ?.filter {
                it in
                setOf("readConfiguration", "loadTemplate")
            }?.distinct()
                .orEmpty()
            require(cold.size <= 1)
            if (cold.isNotEmpty()) {
                require(stack?.captureQuality == io.mazewall.profiler.internal.attribution.StackCaptureQuality.COMPLETE)
                require(invocation.executionContext?.kind == io.mazewall.profiler.internal.attribution.ExecutionKind.PLATFORM)
            }
            cold.singleOrNull()?.let { invocation.invocationId.value to it }
        }.toMap()
        require(methods.values.groupingBy { it }.eachCount() == mapOf("readConfiguration" to 20, "loadTemplate" to 20)) { "closed dictionary/yield does not declare 20+20 cold open0 identities" }
        return java.util.Map.copyOf(methods)
    }

    fun captureStartup(
        lock: Path,
        launch: () -> Process,
        adopt: (Process) -> Unit,
        observe: () -> TracerIdentity,
    ): TracerIdentity =
        FileChannel.open(lock, CREATE, WRITE).use { channel ->
            channel.lock().use {
                check(Files.readString(Path.of("$lock.state")).trim() == "open") { "creation admission closed" }
                val process = launch()
                adopt(process)
                observe()
            }
        }

    fun requireReady(facts: DiagnosticReadyFacts) {
        requireRuntimeReady(facts)
        require(facts.fingerprintsComplete) { "diagnostic fingerprints incomplete" }
    }

    private fun requireRuntimeReady(facts: DiagnosticReadyFacts) {
        require(
            facts.attached &&
            facts.gateClosed &&
            facts.receiptsAllocated &&
            facts.identityPinned &&
            facts.tracerPidMatches &&
            facts.tracerAlive &&
            facts.followsClones &&
            facts.limitsVerified &&
            facts.oracleHealthy &&
            facts.sameTimeNamespace,
        ) {
            "diagnostic readiness incomplete: $facts"
        }
    }

    fun oracleHealthy(
        stderrBytes: Long,
        fileBytes: Long,
        exitCode: Int?,
    ): Boolean = stderrBytes in 0..65536 && fileBytes in 0 until 16777216 && (exitCode == null || exitCode == 0)

    fun tracerStopped(
        clientDead: Boolean,
        pinnedAbsent: Boolean,
    ): Boolean = clientDead && pinnedAbsent

    fun stopPinned(
        expected: TracerIdentity,
        actual: TracerIdentity,
        signal: () -> Unit,
    ) {
        require(expected == actual) { "tracer incarnation mismatch; no signal" }
        signal()
    }

    fun tracerArgv(targetPid: Long): List<String> {
        require(targetPid in 1..Int.MAX_VALUE.toLong())
        return java.util.List.of("strace", "-f", "-r", "-T", "-s", "256", "-xx", "-e", "trace=openat,openat2,close,gettid", "-o", "/fixture/evidence/ptrace-open.log", "-p", "$targetPid")
    }

    fun liveReceipt(
        lines: List<String>,
        reply: String,
        target: Long,
        epoch: Long,
        previous: LiveControlReceipt? = null,
    ): LiveControlReceipt {
        require(reply.startsWith("OK engine=active ") && reply.split(' ').containsAll(listOf("worker_alive=true", "failure=none", "collector_state=RUNNING"))) { "STATUS not healthy" }
        val receipts = lines.filter { it.startsWith("tier-e-boundary-control:") }
        require(receipts.size == 1) { "missing/ambiguous fresh STATUS receipt" }
        val line = receipts.single()
        require(line.length + 1 <= 1024 && line.all { it.code in 32..126 })
        val pairs = line.removePrefix("tier-e-boundary-control: ").split(' ').map {
            require(it.count { c -> c == '=' } == 1)
            it.substringBefore('=') to it.substringAfter('=')
        }
        val fields = pairs.toMap()
        val numericKeys =
            setOf(
                "version",
                "installation",
                "ordinal",
                "initialized",
                "target",
                "epoch",
                "capacity",
                "record_bytes",
                "control_bytes",
                "allocated_bytes",
                "combined_storage_bound",
                "bookkeeping_bound",
                "attempted",
                "reserved",
                "entries",
                "terminals",
                "drops",
                "helper_failures",
                "main_in_flight",
                "marker_in_flight",
            )
        require(pairs.size == fields.size && fields.keys == numericKeys + "status" && fields["status"] == "ok") { "invalid receipt keys" }
        val n = numericKeys.associateWith { key ->
            val text = fields.getValue(key)
            require(text.matches(Regex("0|[1-9][0-9]*")))
            requireNotNull(text.toLongOrNull())
        }
        require(n["version"] == 1L && n["initialized"] == 1L && n["target"] == target && n["epoch"] == epoch)
        require(n["capacity"] == 32768L && n["record_bytes"] == 112L && n["control_bytes"] == 128L && n["bookkeeping_bound"] == 65536L)
        val allocated = n.getValue("allocated_bytes")
        require(allocated in 3670144L..3691072L && n["combined_storage_bound"] == 6270528L + allocated - 3670144L)
        require(n.getValue("combined_storage_bound") <= 6291456)
        require(n.getValue("installation") > 0 && n.getValue("ordinal") > 0)
        require(previous == null || n["installation"] == previous.installation && n.getValue("ordinal") > previous.ordinal)
        require(n.getValue("attempted") < 32768 && n.getValue("reserved") < 32768)
        require(n.getValue("entries") <= 32768 && n.getValue("terminals") <= 32768 && n["drops"] == 0L && n["helper_failures"] == 0L)
        return LiveControlReceipt(n.getValue("installation"), n.getValue("ordinal"), n)
    }

    fun decodeNativeCalls(bytes: ByteArray): NativeCallSnapshot {
        require(bytes.size in 128..(128 + 1024 * 384)) { "MZOB size" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == 0x424f5a4d && b.short.toInt() == 1 && b.short.toInt() == 128 && b.int == 384)
        val capacity = b.int.also { require(it in 1..1024) }

        fun count(offset: Int): Long = b.getLong(offset).also { require(it >= 0) { "MZOB counter overflow" } }
        val session = count(16).also { require(it in 1..Int.MAX_VALUE.toLong()) }
        val attempted = count(24)
        val reserved = count(32)
        val entries = count(40)
        val returned = count(48)
        val overflow = count(56)
        val failures = listOf(count(64), count(72), count(80))
        val closed = count(88).also { require(it in 0..1) }
        val pid = count(96).also { require(it in 1..Int.MAX_VALUE.toLong()) }
        require((104 until 128).all { bytes[it] == 0.toByte() })
        // Native reserves even rejected attempts; the kernel control reservation is saturated instead.
        val slots = minOf(reserved, capacity.toLong())
        require(reserved == attempted && entries <= slots && returned <= entries && overflow == maxOf(0, reserved - capacity))
        require(bytes.size.toLong() == 128 + slots * 384)
        val calls = List(slots.toInt()) { index ->
            val o = 128 + index * 384

            fun l(at: Int): Long = b.getLong(o + at).also { require(it >= 0) { "MZOB unsupported unsigned identity/time" } }

            fun i(at: Int): Int = b.getInt(o + at)
            val commit = i(0).also { require(it in setOf(0, 1, 3)) }
            val failed = i(4).also { require(it in 0..15) }
            val size = i(84).also { require(it in 0..256) }
            val original = i(88).also { require(it in 0..1) }
            require(i(92) == 0 && (360 until 384).all { bytes[o + it] == 0.toByte() })
            require(size == 0 || bytes[o + 96 + size - 1] == 0.toByte())
            require(size == 0 || (0 until size - 1).none { bytes[o + 96 + it] == 0.toByte() })
            require((size until 256).all { bytes[o + 96 + it] == 0.toByte() })
            val path = (0 until maxOf(0, size - 1)).joinToString("") { "%02x".format(bytes[o + 96 + it].toInt() and 255) }
            NativeCall(
                commit,
                failed,
                l(8),
                l(16),
                i(24).toUInt().toLong(),
                i(28).toUInt().toLong(),
                l(32),
                l(40),
                l(48),
                l(56),
                i(72),
                i(76),
                i(80),
                path,
                original == 1,
                l(352),
                b.getLong(o + 64).toULong(),
            )
        }
        val complete = closed == 1L &&
            attempted < capacity &&
            failures.all { it == 0L } &&
            overflow == 0L &&
            entries == reserved &&
            returned == reserved &&
            calls.all {
                it.commit == 3 &&
                it.failures == 0 &&
                it.original &&
                it.pathPointer != 0uL &&
                it.invocation > 0 &&
                it.execution > 0 &&
                it.pid == pid &&
                it.tid > 0 &&
                it.ordinal > 0 &&
                it.bridgeStart > 0 &&
                it.bridgeEnd >= it.bridgeStart &&
                it.entry >= it.bridgeEnd &&
                it.returned >= it.entry &&
                it.pathHex.isNotEmpty()
            }
        return NativeCallSnapshot(session, pid, complete, calls)
    }

    fun joinObservedCalls(
        native: NativeCallSnapshot,
        kernel: BoundaryDiagnosticSnapshot,
        strace: List<String>,
    ): DiagnosticJoin {
        // READY must already prove owned identities and equal native/kernel time-namespace offsets.
        val reasons = linkedSetOf<String>()
        val missing = linkedSetOf<Long>()
        val ambiguous = linkedSetOf<Long>()
        val matched = mutableListOf<DiagnosticMatchedCall>()
        val localized = mutableListOf<DiagnosticOutcome.LocalizedBoundary>()
        val oracle = parseOracle(strace, reasons)
        if (!native.complete || !native.otherPlanesComplete) reasons += "native/dictionary/yield plane is incomplete"
        if (!kernel.complete || kernel.control.reserved != kernel.records.size.toLong() || kernel.control.reserved >= 32768) reasons += "kernel receipt plane is incomplete"
        if (native.session != kernel.session) reasons += "session mismatch"
        if (native.calls.size > 1024 || kernel.records.size > 32768) {
            reasons += "diagnostic record bound exceeded"
            return DiagnosticJoin(matched, reasons.toList(), DiagnosticOutcome.Incomplete(reasons.toList()), native.coldMethods.keys, ambiguous)
        }
        if (native.coldMethods.values
            .groupingBy { it }
            .eachCount() != mapOf("readConfiguration" to 20, "loadTemplate" to 20)
        ) {
                reasons += "closed cold identities are not 20+20"
            }
        val callsById = native.calls.groupBy { it.invocation }
        for (id in native.coldMethods.keys) {
            if (callsById[id].isNullOrEmpty()) {
                missing += id
                reasons += "missing native invocation $id"
            } else if (callsById.getValue(id).size != 1) {
                ambiguous += id
                reasons += "duplicate native invocation $id"
            }
        }
        val indexed = kernel.records.withIndex().toList()
        if (indexed.any { (_, row) ->
                row.ktime == 0uL ||
                    row.ktime > Long.MAX_VALUE.toULong() ||
                    row.pidTgid shr 32 != kernel.targetTgid.toULong() ||
                    row.plane == 2 &&
                    row.rawId > Long.MAX_VALUE.toULong()
            }
        ) {
            reasons += "unrepresentable time/raw syscall or unowned kernel identity"
        }
        val mainByTid = indexed.filter { it.value.plane == 2 }.groupBy { (it.value.pidTgid and 0xffffffffuL).toLong() }
        val fds = oracleFdLifetimes(oracle)
        val operationEntries = oracle.values.associate { thread ->
            thread.hostTid to oracleOperationEntries(thread, mainByTid[thread.hostTid].orEmpty().map { it.value })
        }
        val closeCompletionBounds = oracle.values
            .flatMap { thread ->
            val entries = operationEntries.getValue(thread.hostTid)
            thread.events.filterIsInstance<OracleClose>().mapNotNull { close ->
                thread.events
                    .firstOrNull { it.startedAt > close.position && it.position in entries }
                    ?.let { close.position to entries.getValue(it.position) }
            }
        }.toMap()
        for ((namespaceTid, threadCalls) in native.calls.groupBy { it.tid }) {
            val selected = threadCalls.filter { it.invocation in native.coldMethods }
            if (selected.isEmpty()) continue
            val ordered = threadCalls.sortedBy { it.ordinal }
            if (ordered.map { it.ordinal }.distinct().size != ordered.size || ordered.zipWithNext().any { (a, b) -> a.returned > b.entry }) {
                reasons += "ambiguous native order for namespace TID $namespaceTid"
                ambiguous += selected.map { it.invocation }
                continue
            }
            val bridges = ordered.map { it.bridgeStart to it.bridgeEnd }.distinct()
            val candidates = oracle.filter { (_, trace) ->
                val tids = trace.events.filterIsInstance<OracleTid>()
                val host = trace.hostTid
                val raw = mainByTid[host]
                    .orEmpty()
                    .map { it.value }
                    .filter { it.rawId == 186uL }
                    .sortedBy { it.ktime }
                trace.closed &&
                    tids.isNotEmpty() &&
                    tids.all { it.namespaceTid == namespaceTid } &&
                    tids.size == raw.size &&
                    bridges.all { (start, end) -> raw.count { it.commit == 3 && it.ktime >= start.toULong() && it.ktime <= end.toULong() } == 1 }
            }
            if (candidates.size != 1) {
                reasons += "missing/ambiguous gettid bridge for namespace TID $namespaceTid"
                if (candidates.isEmpty()) missing += selected.map { it.invocation } else ambiguous += selected.map { it.invocation }
                continue
            }
            val trace = candidates.values.single()
            val hostTid = trace.hostTid
            val mains = mainByTid[hostTid].orEmpty().sortedBy { it.value.ktime }
            val opens = trace.events.filterIsInstance<OracleOpen>()
            val rawOpens = mains.filter { it.value.rawId in setOf(257uL, 437uL) }
            // A native interval owns a retry cluster; rows outside every interval remain separate oracle calls.
            val slots = mutableListOf<Pair<NativeCall?, List<IndexedValue<io.mazewall.profiler.tierE.engine.BoundaryDiagnosticRecord>>>>()
            for (call in ordered) {
                val rows = rawOpens.filter { it.value.ktime >= call.entry.toULong() && it.value.ktime <= call.returned.toULong() }
                slots += call to rows
            }
            rawOpens.filter { row -> ordered.none { row.value.ktime >= it.entry.toULong() && row.value.ktime <= it.returned.toULong() } }.forEach { slots += null to listOf(it) }
            val sequence = slots.sortedBy { (call, rows) -> call?.entry?.toULong() ?: rows.single().value.ktime }
            if (sequence.size != opens.size) {
                reasons += "oracle/kernel open sequence differs for host TID $hostTid"
                ambiguous += selected.map { it.invocation }
                continue
            }
            val observedOrder = trace.events.mapNotNull {
                when (it) {
                    is OracleTid -> 186L
                    is OracleOpen -> it.syscall
                    is OracleClose -> null
                }
            }
            val kernelOrder = (
                mains.filter { it.value.rawId == 186uL }.map { it.value.ktime to 186L } +
                sequence.zip(opens).map { (slot, open) ->
                    (
                        slot.first?.entry?.toULong() ?: slot.second
                    .single()
                    .value.ktime
                    ) to open.syscall
                }
            ).sortedBy { it.first }
                .map { it.second }
            if (observedOrder != kernelOrder) {
                reasons += "oracle/kernel gettid/open order differs for host TID $hostTid"
                ambiguous += selected.map { it.invocation }
                continue
            }
            for ((slot, open) in sequence.zip(opens)) {
                val (call, rows) = slot
                if (call == null) {
                    if (rows.single().value.rawId != open.syscall.toULong() || open.attempts != 1) reasons += "unresolved non-native open cluster for host TID $hostTid"
                    continue
                }
                val id = call.invocation
                if (open.pathHex != call.pathHex ||
                    open.flags != call.flags ||
                    (openNeedsMode(open.flags) && open.mode != call.mode) ||
                    open.result != call.result ||
                    call.commit != 3 ||
                    call.failures != 0 ||
                    !call.original ||
                    call.pid != native.namespacePid ||
                    call.bridgeStart <= 0 ||
                    call.bridgeEnd < call.bridgeStart ||
                    call.entry < call.bridgeEnd ||
                    call.returned < call.entry ||
                    call.pathPointer == 0uL
                ) {
                    reasons += "oracle/native input/result mismatch for invocation $id"
                    ambiguous += id
                    continue
                }
                if (id !in native.coldMethods) continue
                // Input/result correspondence is a positive fact, not a certificate of FD closure.
                matched += DiagnosticMatchedCall(id, hostTid, call.ordinal, open.result, open.syscall)
                if (open.result >= 0) {
                    val close = fds.closes[open.position]
                    val end = close?.let { closeCompletionBounds[it.position] }
                    // These untraced operations can replace/close FDs; their arguments are unknown here.
                    val hazards = indexed.filter { (_, row) ->
                        val synchronous = row.rawId in setOf(33uL, 292uL)
                        val postOperation = if (synchronous) {
                            operationEntries[(row.pidTgid and 0xffffffffuL).toLong()]?.values?.filter { it > row.ktime }?.minOrNull()
                        } else {
                            null
                        }
                        row.plane == 2 &&
                            row.rawId in setOf(33uL, 292uL, 436uL, 426uL, 59uL, 322uL, 425uL) &&
                            (postOperation == null || postOperation >= call.entry.toULong()) &&
                            (end == null || row.ktime <= end)
                    }
                    if (close == null || open.position in fds.conflicts || close.hostTid !in mainByTid || end != null && end < call.entry.toULong() || hazards.isNotEmpty()) {
                        reasons += "missing/conflicting shared FD lifetime for invocation $id fd=${open.result} untraced_rows=${hazards.map { it.index }}"
                        ambiguous += id
                        continue
                    }
                }
                val finalMarker = finalObservedMarker(indexed, hostTid, call, native.session, kernel.targetTgid)
                if (finalMarker == null) {
                    reasons += "missing/unknown or unstable final marker state for invocation $id"
                    missing += id
                    continue
                }
                val marker = finalMarker.value
                if (rows.isEmpty()) {
                    missing += id
                    if (open.attempts != 1) reasons += "missing kernel retry cluster for invocation $id"
                    localized += DiagnosticOutcome.LocalizedBoundary(id, null, listOf(finalMarker.index.toLong()))
                    continue
                }
                if (rows.size != open.attempts || rows.any { it.value.rawId != open.syscall.toULong() || it.value.commit != 3 }) {
                    reasons += "unresolved kernel retry/syscall cluster for invocation $id"
                    ambiguous += id
                    continue
                }
                val rejected = rows.filter { it.value.stage != BoundaryStage.MAIN_ACTIVE_COUNTER_REACHED }
                if (rejected.isNotEmpty()) {
                    if (rows.size == 1 && consistentMainRejection(rejected.single().value, kernel.targetTgid, marker)) {
                        localized += DiagnosticOutcome.LocalizedBoundary(id, rejected.single().value.stage, rows.map { it.index.toLong() })
                    } else {
                        reasons += "unresolved rejection cluster for invocation $id"
                        ambiguous += id
                    }
                } else if (rows.any { (_, row) -> row.validFields and 991u != 991u || row.invocation != id.toULong() || row.sequence != marker.sequence || row.generation != marker.generation }) {
                    reasons += "unknown/conflicting active state for invocation $id"
                    ambiguous += id
                }
            }
        }
        if (localized.size > 1) reasons += "multiple unresolved boundaries"
        if (matched.size != native.coldMethods.size || localized.isEmpty() && (missing.isNotEmpty() || ambiguous.isNotEmpty())) reasons += "cold invocation join is incomplete"
        val reported = reasons +
            localized.map {
            if (it.stage == null) "missing main entry for invocation ${it.invocationId}" else "kernel rejection ${it.stage} for invocation ${it.invocationId}"
        }
        val outcome = when {
            reasons.isNotEmpty() -> DiagnosticOutcome.Incomplete(reported.toList())
            localized.size == 1 -> localized.single()
            else -> DiagnosticOutcome.InconclusiveBaseline
        }
        return DiagnosticJoin(matched, reported.toList(), outcome, missing, ambiguous)
    }

    private fun finalObservedMarker(
        indexed: List<IndexedValue<io.mazewall.profiler.tierE.engine.BoundaryDiagnosticRecord>>,
        hostTid: Long,
        call: NativeCall,
        session: Long,
        target: Long,
    ): IndexedValue<io.mazewall.profiler.tierE.engine.BoundaryDiagnosticRecord>? {
        // All stages/IDs participate: a later CLEAR or rejected transition cannot disappear.
        val markers = indexed
            .filter { (_, row) ->
            row.plane == 1 &&
                row.pidTgid and 0xffffffffuL == hostTid.toULong() &&
                row.ktime >= call.bridgeEnd.toULong() &&
                row.ktime <= call.returned.toULong()
        }.sortedBy { it.value.ktime }
        if (markers.isEmpty() ||
            markers.last().value.ktime >= call.entry.toULong() ||
            markers.zipWithNext().any { (a, b) -> a.value.ktime >= b.value.ktime }
        ) {
            return null
        }
        val final = markers.last()
        val repeated = markers.takeLastWhile { it.value.stage == BoundaryStage.MARKER_SET && it.value.rawId == call.invocation.toULong() }
        if (repeated.isEmpty() ||
            repeated.any { (_, row) ->
                row.commit != 3 ||
                    row.validFields != 991u ||
                    row.invocation != call.invocation.toULong() ||
                    row.words[0] != session.toULong() ||
                    row.words[1] != target.toULong() ||
                    row.words[2] != 1uL ||
                    row.words[3].toUInt() != 0u ||
                    row.words[4] != 0uL ||
                    row.aux != 0u ||
                    row.sequence == 0uL ||
                    row.generation == 0u ||
                    row.generation != final.value.generation
            } ||
            repeated.zipWithNext().any { (a, b) -> a.value.sequence >= b.value.sequence }
        ) {
            return null
        }
        return final
    }

    private fun consistentMainRejection(
        row: io.mazewall.profiler.tierE.engine.BoundaryDiagnosticRecord,
        target: Long,
        marker: io.mazewall.profiler.tierE.engine.BoundaryDiagnosticRecord,
    ): Boolean {
        // These masks follow the unchanged emitter's read/branch order; unread fields remain unknown.
        val mask = when (row.stage) {
            BoundaryStage.MAIN_NO_SESSION -> 0u
            BoundaryStage.MAIN_WRONG_TARGET -> 1u
            BoundaryStage.MAIN_NO_INFLIGHT -> 17u
            BoundaryStage.MAIN_CAPTURE_DISABLED -> 19u
            BoundaryStage.MAIN_DETACHED -> 23u
            BoundaryStage.MAIN_NO_TASK, BoundaryStage.MAIN_NO_STORAGE -> 31u
            BoundaryStage.MAIN_INACTIVE -> 95u
            BoundaryStage.MAIN_STALE -> 991u
            else -> return false
        }
        if (row.validFields and mask != mask) return false
        if (row.stage == BoundaryStage.MAIN_NO_SESSION) return true
        if (row.stage == BoundaryStage.MAIN_WRONG_TARGET) return row.words[0] != target.toULong()
        if (row.words[0] != target.toULong()) return false
        if (row.stage == BoundaryStage.MAIN_NO_INFLIGHT) return row.words[4] == 0uL
        if (row.words[4] != 1uL) return false
        if (row.stage == BoundaryStage.MAIN_CAPTURE_DISABLED) return row.words[1] == 0uL
        if (row.words[1] != 1uL) return false
        if (row.stage == BoundaryStage.MAIN_DETACHED) return row.words[2] == 0uL
        if (row.words[2] != 1uL) return false
        if (row.words[3] == 0uL || row.words[3] > UInt.MAX_VALUE.toULong()) return false
        if (row.stage == BoundaryStage.MAIN_NO_TASK || row.stage == BoundaryStage.MAIN_NO_STORAGE) return true
        if (row.stage == BoundaryStage.MAIN_INACTIVE) return row.invocation == 0uL
        return row.invocation == marker.invocation &&
            row.sequence == marker.sequence &&
            row.sequence > 0uL &&
            row.generation == marker.generation &&
            row.generation.toULong() != row.words[3]
    }

    private sealed interface OracleEvent {
        val position: Int
        val startedAt: Int
    }

    private data class OracleTid(
        val namespaceTid: Long,
        override val position: Int,
        override val startedAt: Int,
    ) : OracleEvent

    private data class OracleOpen(
        val syscall: Long,
        val pathHex: String,
        val flags: Int,
        val mode: Int,
        val result: Int,
        val attempts: Int,
        override val startedAt: Int,
        override val position: Int,
    ) : OracleEvent

    private data class OracleClose(
        val fd: Int,
        val result: Int,
        val hostTid: Long,
        override val startedAt: Int,
        override val position: Int,
    ) : OracleEvent

    private data class OracleThread(
        val hostTid: Long,
        val events: List<OracleEvent>,
        val closed: Boolean,
        val interrupted: Boolean,
    )

    private fun oracleOperationEntries(
        thread: OracleThread,
        rows: List<io.mazewall.profiler.tierE.engine.BoundaryDiagnosticRecord>,
    ): Map<Int, ULong> {
        // A signal handler can enter another syscall before the interrupted operation completes.
        if (!thread.closed || thread.interrupted || rows.any { it.rawId == 15uL }) return emptyMap()
        val visible = rows.filter { it.rawId in setOf(3uL, 186uL, 257uL, 437uL) }.sortedBy { it.ktime }
        val expected = thread.events.sortedBy { it.startedAt }.flatMap { event ->
            when (event) {
                is OracleTid -> listOf(event to 186uL)
                is OracleClose -> listOf(event to 3uL)
                is OracleOpen -> List(event.attempts) { event to event.syscall.toULong() }
            }
        }
        if (visible.size != expected.size ||
            visible.zip(expected).any { (row, event) -> row.commit != 3 || row.rawId != event.second } ||
            visible.zipWithNext().any { (a, b) -> a.ktime >= b.ktime } ||
            thread.events
                .sortedBy { it.startedAt }
                .zipWithNext()
                .any { (a, b) -> a.position >= b.startedAt }
        ) {
            return emptyMap()
        }
        return expected.zip(visible).groupBy { it.first.first.position }.mapValues { (_, attempts) -> attempts.first().second.ktime }
    }

    private data class OracleFdLifetimes(
        val closes: Map<Int, OracleClose>,
        val conflicts: Set<Int>,
    )

    private fun oracleFdLifetimes(threads: Map<Long, OracleThread>): OracleFdLifetimes {
        // This is only the observed generations, never a claim that the process table began empty.
        val observed = mutableMapOf<Int, OracleOpen>()
        val lastClose = mutableMapOf<Int, OracleClose>()
        val closes = mutableMapOf<Int, OracleClose>()
        val conflicts = mutableSetOf<Int>()
        for (event in threads.values.flatMap { it.events }.sortedBy { it.position }) {
            when (event) {
                is OracleTid -> Unit
                is OracleOpen -> if (event.result >= 0) {
                    observed.put(event.result, event)?.let { conflicts += it.position }
                    if (lastClose[event.result]?.position?.let { it >= event.startedAt } == true) conflicts += event.position
                }
                is OracleClose -> if (event.result == 0) {
                    lastClose[event.fd] = event
                    observed.remove(event.fd)?.let { open ->
                        if (open.position < event.startedAt) closes[open.position] = event else conflicts += open.position
                    }
                }
            }
        }
        return OracleFdLifetimes(closes.toMap(), conflicts.toSet())
    }

    private fun parseOracle(
        lines: List<String>,
        reasons: MutableSet<String>,
    ): Map<Long, OracleThread> {
        if (lines.size > 262144 || lines.sumOf { it.length.toLong() + 1 } >= 16777216 || lines.any { it.length > 4096 }) {
            reasons += "oracle input exceeds byte/row/line bound"
            return emptyMap()
        }
        val events = linkedMapOf<Long, MutableList<OracleEvent>>()
        val closed = mutableSetOf<Long>()
        val interrupted = mutableSetOf<Long>()
        val pending = mutableMapOf<Long, Pair<String, Int>>()
        val retries = mutableMapOf<Long, Triple<String, Int, Int>>()
        val leader = Regex("\\s*(?:\\[pid\\s+([1-9][0-9]*)]\\s+|([1-9][0-9]*)\\s+)([0-9]+\\.[0-9]+)\\s+(.+)")
        for ((index, line) in lines.withIndex()) {
            try {
                require(line.all { it.code in 32..126 || it == '\t' })
                val parts = requireNotNull(leader.matchEntire(line)) { "unknown leader/partial line" }
                val tid = (parts.groupValues[1].ifEmpty { parts.groupValues[2] }).toLong()
                require(tid in 1..Int.MAX_VALUE.toLong())
                require(tid in events || events.size < 1024) { "TID bound" }
                val thread = events.getOrPut(tid) { mutableListOf() }
                require(tid !in closed) { "reused/closed TID" }
                var body = parts.groupValues[4]
                var startedAt = index
                if (body.matches(Regex("\\+\\+\\+ exited with [0-9]+ \\+\\+\\+"))) {
                    require(tid !in pending && tid !in retries) { "unresolved syscall at exit" }
                    closed += tid
                    continue
                }
                if (body.matches(Regex("--- SIG[A-Z0-9]+ \\{.*} ---"))) {
                    interrupted += tid
                    continue
                }
                if (body.endsWith("<unfinished ...>")) {
                    require(tid !in pending)
                    pending[tid] = body.removeSuffix("<unfinished ...>").trimEnd() to index
                    continue
                }
                if (body.startsWith("<... ")) {
                    val resumed = requireNotNull(Regex("<\\.\\.\\. (openat2?|close|gettid) resumed>(.*)").matchEntire(body))
                    val entry = requireNotNull(pending.remove(tid)) { "orphan resumed syscall" }
                    require(entry.first.startsWith(resumed.groupValues[1] + "("))
                    body = entry.first + resumed.groupValues[2]
                    startedAt = entry.second
                } else {
                    require(tid !in pending) { "unresolved unfinished syscall" }
                }
                val call = requireNotNull(Regex("(openat2?|close|gettid)\\((.*)\\)\\s+=\\s+(.+)").matchEntire(body)) { "unknown syscall grammar" }
                val name = call.groupValues[1]
                val args = call.groupValues[2]
                val key = "$name($args)"
                val previous = retries[tid]
                require(previous == null || previous.first == key) { "conflicting restart" }
                val resultText = call.groupValues[3]
                if (resultText.matches(Regex("\\? ERESTART(?:SYS|NOINTR|NOHAND|_RESTARTBLOCK) \\(To be restarted\\)(?: <[0-9]+\\.[0-9]+>)?"))) {
                    retries[tid] = Triple(key, (previous?.second ?: 0) + 1, previous?.third ?: startedAt)
                    continue
                }
                val result = requireNotNull(Regex("(-?[0-9]+)(?: ([A-Z][A-Z0-9_]+) \\([^<>]*\\))? <[0-9]+\\.[0-9]+>").matchEntire(resultText)) { "missing syscall return/duration" }
                val value = result.groupValues[1].toInt()
                require(value >= -1 && (value == -1) == result.groupValues[2].isNotEmpty())
                val attempts = (previous?.second ?: 0) + 1
                retries.remove(tid)
                thread += when (name) {
                    "gettid" -> {
                        require(args.isEmpty() && value > 0 && attempts == 1)
                        OracleTid(value.toLong(), index, startedAt)
                    }
                    "close" -> {
                        require(attempts == 1)
                        OracleClose(args.toInt().also { require(it >= 0) }, value, tid, startedAt, index)
                    }
                    else -> parseOracleOpen(name, args, value, attempts, previous?.third ?: startedAt, index)
                }
            } catch (failure: IllegalArgumentException) {
                reasons += "oracle row ${index + 1}: ${failure.message}"
            }
        }
        if (pending.isNotEmpty() || retries.isNotEmpty()) reasons += "unresolved oracle syscall halves/restarts"
        return events.mapValues { (tid, thread) ->
            if (tid !in closed) reasons += "unclosed traced lifetime for TID $tid"
            OracleThread(tid, thread.toList(), tid in closed, tid in interrupted)
        }
    }

    private fun openNeedsMode(flags: Int): Boolean = flags and 64 != 0 || flags and 4259840 == 4259840

    private fun parseOracleOpen(
        name: String,
        args: String,
        result: Int,
        attempts: Int,
        startedAt: Int,
        position: Int,
    ): OracleOpen {
        val fields = requireNotNull(Regex("AT_FDCWD, \\\"((?:\\\\x[0-9a-fA-F]{2})*)\\\", (.+)").matchEntire(args)) { "unrepresentable pathname/dirfd" }
        val path = fields.groupValues[1].replace("\\x", "").lowercase()
        require(path.length <= 510 && path.chunked(2).none { it == "00" }) { "unrepresentable native pathname" }
        val tail = fields.groupValues[2]
        val flags: String
        val mode: String?
        if (name == "openat2") {
            val how = requireNotNull(Regex("\\{flags=([^,}]+)(?:, mode=([0-7]+))?, resolve=0}, (?:24|[3-9][0-9]+)").matchEntire(tail)) { "unknown openat2 how" }
            flags = how.groupValues[1]
            mode = how.groupValues[2].ifEmpty { null }
        } else {
            val values = tail.split(", ")
            require(values.size in 1..2)
            flags = values[0]
            mode = values.getOrNull(1)
        }
        val known = mapOf(
            "O_RDONLY" to 0,
            "O_WRONLY" to 1,
            "O_RDWR" to 2,
            "O_CREAT" to 64,
            "O_EXCL" to 128,
            "O_NOCTTY" to 256,
            "O_TRUNC" to 512,
            "O_APPEND" to 1024,
            "O_NONBLOCK" to 2048,
            "O_DSYNC" to 4096,
            "O_DIRECT" to 16384,
            "O_LARGEFILE" to 32768,
            "O_DIRECTORY" to 65536,
            "O_NOFOLLOW" to 131072,
            "O_NOATIME" to 262144,
            "O_CLOEXEC" to 524288,
            "O_SYNC" to 1052672,
                "O_PATH" to 2097152,
            "O_TMPFILE" to 4259840,
        )
        val bits = flags.split('|').fold(0) { value, flag -> value or requireNotNull(known[flag]) { "unknown open flags" } }
        require(bits and 3 != 3)
        require(!openNeedsMode(bits) || mode != null) { "missing creation mode" }
        val modeBits = if (mode == null) 0 else requireNotNull(mode.toIntOrNull(8)) { "unknown creation mode" }.also { require(it in 0..4095) }
        return OracleOpen(if (name == "openat") 257 else 437, path, bits, modeBits, result, attempts, startedAt, position)
    }
}
