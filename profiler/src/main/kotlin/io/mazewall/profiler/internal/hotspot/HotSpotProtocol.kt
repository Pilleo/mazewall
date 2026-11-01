package io.mazewall.profiler.internal.hotspot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val GADGET_REVISION = "7097db144ccd8dab15373f27ec98d2c3d22e4588"
private const val PROFILER_REVISION = "ef6cf29492c8a980f889052903fa2dd0c0765cc0"
private const val PATCH_SHA256 = "b9a9d70db7e37bba07293a635cac94cc540688b85e1f3aa31c2e9ad25cfeecf3"
private const val JVM_BUILD_ID = "e4dad22744e32e4d71755f4a58c5cf1f1d336f87"
private const val JVM_SHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"

internal class HotSpotCollectorFailure(
    message: String,
    val nativeErrno: UInt?,
) : IllegalStateException(message)

/** Stateless schema-2 decoding. Lifecycle order/ownership is decided by HotSpotSessionMachine. */
internal class HotSpotProtocol(
    private val expectedPid: Int,
    private val expectedStartTicks: ULong,
) {
    init {
        require(expectedPid > 0 && expectedStartTicks > 0uL)
    }

    fun controlEvent(row: HotSpotWireRecord): HotSpotSessionEvent? {
        checkHotSpotProtocol(row.objectValue.ulong("schema_version") == 2uL, "production protocol requires explicit schema 2")
        return when (row.kind) {
            "session_started" -> {
                lifecycle(row)
                HotSpotSessionEvent.Started(row.sessionId)
            }
            "collector_ready" -> {
                val value = lifecycle(row)
                checkHotSpotProtocol(value.ulong("process_start_ticks") == expectedStartTicks, "ready target generation mismatch")
                checkHotSpotProtocol(value.text("jvm_build_id") == JVM_BUILD_ID && value.text("jvm_sha256") == JVM_SHA256, "ready runtime build mismatch")
                HotSpotSessionEvent.Ready(row.sessionId)
            }
            "collector_error" -> {
                val value = lifecycle(row)
                HotSpotSessionEvent.Failure(HotSpotCollectorFailure(value.text("message"), value.optionalUint("errno")))
            }
            "event" -> {
                val value = row.objectValue.objectField("event")
                checkHotSpotProtocol(value.uint("host_pid").toULong() == expectedPid.toULong() && value.ulong("process_start_ticks") == expectedStartTicks, "event target generation mismatch")
                null
            }
            "enrichment", "recovery", "session_summary" -> null
            else -> throw HotSpotProtocolException("unsupported production record kind ${row.kind}")
        }
    }

    /** Call only with evidence whose delivered occurrence pairing/counters were verified by the assembler. */
    fun summaryEvent(evidence: StructuredProfileEvidence): HotSpotSessionEvent.Summary {
        checkHotSpotProtocol(evidence.finalized, "partial snapshot cannot certify final accounting")
        val final = evidence.summary
        val value = Json.parseToJsonElement(final.rawJson) as? JsonObject ?: throw HotSpotProtocolException("summary is not an object")
        val errorFree = !value.containsKey("errors") || value.array("errors").isEmpty()
        return HotSpotSessionEvent.Summary(evidence.sessionId, final.physicalDrainComplete && final.terminalAccountingComplete && errorFree)
    }

    private fun lifecycle(row: HotSpotWireRecord): JsonObject {
        val value = row.objectValue.objectField("lifecycle")
        checkHotSpotProtocol(value.uint("pid").toULong() == expectedPid.toULong(), "lifecycle target PID mismatch")
        checkHotSpotProtocol(value.text("clock_domain") == "CLOCK_BOOTTIME" && value.text("runtime_profile") == "temurin25_xint_serial", "unsupported lifecycle clock/profile")
        value.ulong("boot_ns")
        val provider = value.objectField("provider")
        checkHotSpotProtocol(
            provider.text("name") == "otel-ebpf-profiler-via-gadget" &&
            provider.text("gadget_revision") == GADGET_REVISION &&
            provider.text("profiler_revision") == PROFILER_REVISION &&
            provider.text("patch_sha256") == PATCH_SHA256,
                "unsupported production provider provenance",
        )
        return value
    }
}
