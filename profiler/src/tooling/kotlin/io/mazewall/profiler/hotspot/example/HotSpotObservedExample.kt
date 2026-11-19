package io.mazewall.profiler.hotspot.example

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.HotSpotProfilingSession
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.ProfilingResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Observes an independently identified external JVM; never owns or terminates that JVM. */
internal object HotSpotObservedExample {
    private const val MAX_WINDOW_MILLIS = 30000L

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 8) {
            "Usage: HotSpotObservedExample <assets> <new-output-directory> <prefix-json-file> " +
                "<host-pid> <birth-ticks> <cgroupv2-id> <mount-namespace-inode> <window-millis>"
        }
        val assets = absolutePath(args[0])
        val output = absolutePath(args[1])
        val prefixFile = absolutePath(args[2])
        require(Files.isDirectory(assets)) { "Assets must be an existing directory" }
        require(Files.isRegularFile(prefixFile)) { "Prefix must be an existing regular file" }
        require(!Files.exists(output)) { "Output must be a new directory" }
        val pid = args[3].takeIf(::decimal)?.toIntOrNull()
        require(pid != null && pid > 0) { "Host PID must be a positive decimal Int" }
        val target = HotSpotObservationTarget(Pid(pid), identity(args[4]), identity(args[5]), identity(args[6]))
        val window = args[7].takeIf(::decimal)?.toLongOrNull()
        require(window != null && window in 1..MAX_WINDOW_MILLIS) { "Window must be 1..30000 decimal milliseconds" }
        val array = Json.parseToJsonElement(Files.readString(prefixFile))
        require(array is JsonArray) { "Prefix must be a JSON array of literal strings" }
        val prefix = array.map { element ->
            require(element is JsonPrimitive && element.isString && element.content.isNotEmpty()) {
                "Prefix entries must be nonempty JSON strings"
            }
            element.content
        }
        val options = HotSpotOptions(assets, prefix)
        Files.createDirectory(output)
        retain(
            output,
            "target-identity.json",
            buildJsonObject {
                put("host_pid", pid)
                put("birth_ticks", target.processStartTicks.toString())
                put("cgroupv2_id", target.cgroupId.toString())
                put("mount_namespace_inode", target.mountNamespace.toString())
                put("assets", assets.toString())
                put("window_millis", window)
                put("collector_admission", false)
            }.toString(),
        )
        collect(output, target, options, window)
    }

    private fun absolutePath(text: String): Path {
        require(text.isNotBlank()) { "Paths must be nonblank and absolute" }
        return Path.of(text).also { require(it.isAbsolute) { "Paths must be absolute" } }
    }

    private fun decimal(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }

    private fun identity(text: String): ULong {
        val value = text.takeIf(::decimal)?.toULongOrNull()
        require(value != null && value > 0uL) { "Identity fields must be positive decimal ULong values" }
        return value
    }

    // Also used by host tests to force CREATE_NEW collisions without permission or timing assumptions.
    internal fun collect(
        output: Path,
        target: HotSpotObservationTarget,
        options: HotSpotOptions,
        window: Long,
    ) {
        var session: HotSpotProfilingSession? = null
        var result: ProfilingResult<Unit>? = null
        var failure: Throwable? = null
        var originalOwnership: Boolean? = null
        var evidenceWritten = false
        val failures = mutableListOf<Pair<String, Throwable>>()

        fun record(
            stage: String,
            caught: Throwable,
        ) {
            failures.add(stage to caught)
            failure = CleanupFailures.merge(failure, caught)
        }

        fun attempt(
            stage: String,
            action: () -> Unit,
        ) {
            try {
                action()
            } catch (caught: Throwable) {
                record(stage, caught)
            }
        }

        fun writeEvidence() {
            result?.structuredEvidence?.let {
                retain(output, "evidence.json", it.toJson())
                evidenceWritten = true
            }
        }

        try {
            val acquired = MazewallProfiler.observeHotSpot(target, options)
            session = acquired
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Interrupted before observation readiness publication")
            val console = System.out
            console.print("MAZEWALL_OBSERVATION_READY\n")
            console.flush()
            if (console.checkError()) throw IOException("Failed to publish observation readiness to stdout")
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Interrupted during observation readiness publication")
            Thread.sleep(window)
            result = acquired.finish()
            writeEvidence()
        } catch (caught: Throwable) {
            record("primary", caught)
            originalOwnership = session?.pendingOwnership ?: (caught as? HotSpotProfilingException)?.pendingOwnership
            if (result == null) {
                result = (caught as? HotSpotProfilingException)?.partialResult
                attempt("partial_evidence_output", ::writeEvidence)
            }
        } finally {
            var interrupted = Thread.interrupted() || failure is InterruptedException
            try {
                val acquired = session
                if (failure == null) originalOwnership = acquired?.pendingOwnership
                if (acquired != null) attempt("close") { acquired.close() }
                val ownershipAfterClose = acquired?.pendingOwnership
                // Retry the actual retained owner once, never reinterpret failed collection as success.
                val owner = failures.mapNotNull { it.second as? HotSpotProfilingException }.lastOrNull { it.pendingOwnership }
                if (owner != null) {
                    interrupted = Thread.interrupted() || interrupted
                    attempt("retry_cleanup") { owner.retryCleanup() }
                }
                val finalOwnership = acquired?.pendingOwnership ?: owner?.pendingOwnership
                    ?: failures.mapNotNull { it.second as? HotSpotProfilingException }.lastOrNull()?.pendingOwnership
                val diagnosticFailures = failures.mapNotNull { it.second as? HotSpotProfilingException }
                attempt("diagnostics_output") {
                    val diagnostics = failures.map { (_, caught) ->
                        (caught as? HotSpotProfilingException)?.diagnostics ?: caught.stackTraceToString()
                    } +
                        listOfNotNull(acquired?.diagnostics)
                    retain(output, "diagnostics.txt", diagnostics.joinToString("\n"))
                }
                attempt("outcome_output") {
                    val evidence = result?.structuredEvidence
                    val summary = evidence?.summary
                    val outcome = buildJsonObject {
                        put("outcome", if (failure == null) "successful" else "failed")
                        put("coverage_complete", false)
                        put("policy_eligibility", false)
                        put("evidence_available", evidence != null)
                        put("evidence_written", evidenceWritten)
                        put("finalized", evidence?.finalized.json())
                        put("runtime_profile", evidence?.runtimeProfile.json())
                        put("terminal_accounting_complete", evidence?.terminalAccountingComplete.json())
                        put("physical_drain_complete", summary?.physicalDrainComplete.json())
                        put("retention_complete", evidence?.retentionComplete.json())
                        put("collection_loss_complete", evidence?.collectionLossComplete.json())
                        put("received", summary?.received?.toString().json())
                        put("resolved", summary?.resolved?.toString().json())
                        put("unavailable", summary?.unavailable?.toString().json())
                        put("skipped", summary?.skipped?.toString().json())
                        put("observed_event_loss", summary?.observedEventLoss?.toString().json())
                        put("discarded_occurrences", evidence?.discardedOccurrences?.toString().json())
                        put("pending_occurrences", evidence?.pendingOccurrences?.toString().json())
                        put("retained_bytes", evidence?.retainedBytes?.let(::JsonPrimitive) ?: JsonNull)
                        put("original_pending_ownership", originalOwnership.json())
                        put("close_attempted", acquired != null)
                        put("close_succeeded", acquired?.let { failures.none { it.first == "close" } }.json())
                        put("pending_ownership_after_close", ownershipAfterClose.json())
                        put("retry_cleanup_attempted", owner != null)
                        put("retry_cleanup_succeeded", owner?.let { failures.none { it.first == "retry_cleanup" } }.json())
                        put("final_pending_ownership", finalOwnership.json())
                        put(
                            "discarded_diagnostic_bytes",
                            acquired?.discardedDiagnosticBytes?.let(::JsonPrimitive)
                                ?: diagnosticFailures.lastOrNull()?.discardedDiagnosticBytes?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put(
                            "native_errnos",
                            buildJsonArray {
                                diagnosticFailures.flatMap { it.nativeErrnos }.distinct().forEach { add(JsonPrimitive(it.toLong())) }
                            },
                        )
                        put(
                            "failures",
                            buildJsonArray {
                                failures.forEach { (stage, caught) ->
                                    add(
                                        buildJsonObject {
                                            put("stage", stage)
                                            put("type", caught.javaClass.name)
                                            put("message", caught.message.json())
                                            put("details", caught.stackTraceToString())
                                        },
                                    )
                                }
                            },
                        )
                    }
                    retain(output, "outcome.json", outcome.toString())
                }
            } finally {
                if (Thread.interrupted() || interrupted) Thread.currentThread().interrupt()
            }
        }
        failure?.let { throw it }
    }

    private fun Boolean?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun retain(
        output: Path,
        name: String,
        text: String,
    ) {
        Files.writeString(output.resolve(name), text, CREATE_NEW)
    }
}
