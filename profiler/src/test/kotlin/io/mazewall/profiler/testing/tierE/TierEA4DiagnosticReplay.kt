package io.mazewall.profiler.testing.tierE

import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.tierE.engine.decodeBoundaryDiagnostics
import io.mazewall.profiler.tierE.stress.TierECaptureYield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.system.exitProcess

/** Data-plane replay only: physical writer retirement and runtime custody remain separate gates. */
internal object TierEA4DiagnosticReplay {
    private enum class Outcome(
        val exitCode: Int,
    ) {
        INCONCLUSIVE_BASELINE(0),
        INCOMPLETE(1),
        LOCALIZED_BOUNDARY(1),
        INVALID_EVIDENCE(2),
    }

    val inputLimits: Map<String, Int> = mapOf(
        "work-definitions.mzse" to 32 * 1024 * 1024,
        "work-capture-yield.mzcy" to 24 + 65536 * 16,
        "work-open-calls.diag" to 128 + 1024 * 384,
        "evidence-boundary.bpfdiag" to 256 + 32768 * 112,
        "evidence-ptrace-open.log" to 16 * 1024 * 1024,
        "evidence-orders-19802.events.jsonl" to 32 * 1024 * 1024,
        "evidence-orders-19802.tier-e.json" to 64 * 1024,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "retained-directory target-pid process-start-ticks new-output-json" }
        exitProcess(run(Path.of(args[0]), args[1].toLong(), args[2].toLong(), Path.of(args[3])))
    }

    fun run(
        directory: Path,
        target: Long,
        incarnation: Long,
        output: Path,
    ): Int {
        val root = directory.toAbsolutePath().normalize()
        val destination = output
            .toAbsolutePath()
            .parent
            .toRealPath()
            .resolve(output.fileName)
        require(!destination.startsWith(root) && (!Files.exists(root) || !destination.startsWith(root.toRealPath()))) { "replay output must be outside retained evidence" }
        val report = try {
            require(directory.isAbsolute && target in 1..Int.MAX_VALUE.toLong() && incarnation > 0) { "explicit absolute directory and positive process identity required" }
            val inputs = inputLimits.mapValues { (name, limit) -> readBounded(root.resolve(name), limit) }
            val dictionaryPath = root.resolve("work-definitions.mzse")
            val dictionary = AgentDefinitionFile.decodeForSession(inputs.getValue("work-definitions.mzse"), SessionEpoch(19802))
            val yield = TierECaptureYield.read(root.resolve("work-capture-yield.mzcy"))
            val methods = TierEA4DiagnosticSupport.selectColdMethods(dictionary, yield)
            val native = TierEA4DiagnosticSupport.decodeNativeCalls(inputs.getValue("work-open-calls.diag"))
            val inputHex = HexFormat.of().formatHex("/fixture/work/input".toByteArray(Charsets.US_ASCII))
            require(
                native.calls.filter { it.invocation in methods }.all { call ->
                call.pathHex == inputHex &&
                    dictionary.invocations
                    .single { it.invocationId.value == call.invocation }
                    .executionContext
                    ?.executionId
                    ?.value == call.execution
            },
            ) { "selected native path/execution differs from cold dictionary" }
            val trace = inputs.getValue("evidence-ptrace-open.log").decodeToString(throwOnInvalidSequence = true)
            require(trace.endsWith('\n')) { "unterminated oracle trace" }
            val joined = TierEA4DiagnosticSupport.classifyRetainedProduction(
                native,
                decodeBoundaryDiagnostics(inputs.getValue("evidence-boundary.bpfdiag")),
                trace.removeSuffix("\n").lines(),
                methods,
                inputs.getValue("evidence-orders-19802.events.jsonl").decodeToString(throwOnInvalidSequence = true).lines(),
                inputs.getValue("evidence-orders-19802.tier-e.json").decodeToString(throwOnInvalidSequence = true),
                target,
                incarnation,
                dictionaryPath,
            )
            for ((name, bytes) in inputs) {
                require(bytes.contentEquals(readBounded(root.resolve(name), inputLimits.getValue(name)))) { "retained input changed: $name" }
            }
            JsonObject(
                summarize(joined) +
                (
                    "inputs" to JsonArray(
                        inputs.map { (name, bytes) ->
                buildJsonObject {
                    put("path", name)
                    put("bytes", bytes.size)
                    put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                }
            },
                    )
                ),
            )
        } catch (failure: Exception) {
            buildJsonObject {
                put("schema_version", 1)
                put("execution", "OFFLINE_REPLAY")
                put("qualification", false)
                put("outcome", Outcome.INVALID_EVIDENCE.name)
                put("reason", "${failure.javaClass.simpleName}: ${failure.message}".take(512))
            }
        }
        val finalReport = JsonObject(report + mapOf("root" to JsonPrimitive(root.toString()), "target_pid" to JsonPrimitive(target), "process_incarnation" to JsonPrimitive(incarnation)))
        val payload = (finalReport.toString() + "\n").toByteArray()
        require(payload.size <= 64 * 1024) { "replay report exceeds 64 KiB" }
        Files.write(destination, payload, CREATE_NEW, WRITE)
        val outcome = Outcome.valueOf(report.getValue("outcome").jsonPrimitive.content)
        println("OFFLINE_REPLAY $outcome; qualification=false")
        return outcome.exitCode
    }

    fun readBounded(
        path: Path,
        limit: Int,
    ): ByteArray {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "missing regular input: ${path.fileName}" }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { stream ->
            stream.readNBytes(limit + 1).also { require(it.size <= limit) { "oversized input: ${path.fileName}" } }
        }
    }

    fun summarize(join: DiagnosticJoin): JsonObject =
        buildJsonObject {
        put("schema_version", 1)
        put("execution", "OFFLINE_REPLAY")
        put("qualification", false)
        put("matched_count", join.matched.size)
        put("missing_count", join.missingIds.size)
        put("ambiguous_count", join.ambiguousIds.size)
        put(
            "missing_ids",
            JsonArray(
                join.missingIds
            .sorted()
            .take(20)
            .map(::JsonPrimitive),
            ),
        )
        put(
            "ambiguous_ids",
            JsonArray(
                join.ambiguousIds
            .sorted()
            .take(20)
            .map(::JsonPrimitive),
            ),
        )
        put("reason_count", join.reasons.size)
        put("reasons", JsonArray(join.reasons.take(20).map { JsonPrimitive(it.take(512)) }))
        put("truncated", join.reasons.size > 20 || join.reasons.any { it.length > 512 } || join.missingIds.size > 20 || join.ambiguousIds.size > 20)
        when (val outcome = join.outcome) {
            DiagnosticOutcome.InconclusiveBaseline -> put("outcome", Outcome.INCONCLUSIVE_BASELINE.name)
            is DiagnosticOutcome.Incomplete -> put("outcome", Outcome.INCOMPLETE.name)
            is DiagnosticOutcome.LocalizedBoundary -> {
                put("outcome", Outcome.LOCALIZED_BOUNDARY.name)
                put("invocation_id", outcome.invocationId)
                put("stage", outcome.stage?.name)
                put("record_ordinals", JsonArray(outcome.recordOrdinals.take(20).map(::JsonPrimitive)))
            }
        }
    }
}
