package io.mazewall.profiler.internal.hotspot

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class HotSpotWireMapper(
    private val limits: HotSpotReplayLimits,
) {
    fun event(row: HotSpotWireRecord): HotSpotEvent {
        val event = row.objectValue.objectField("event")
        checkHotSpotProtocol(event.text("session_id") == row.sessionId && event.text("event_id") == row.eventId, "nested occurrence identity mismatch")
        checkHotSpotProtocol(event.text("clock_domain") == "CLOCK_BOOTTIME" && event.text("event_kind") == "openat_handler_entry", "unsupported event/clock domain")
        val pid = processId(event, "host_pid")
        val tid = processId(event, "host_tid")
        val ticks = event.ulong("process_start_ticks")
        checkHotSpotProtocol(ticks != 0uL, "missing process generation")
        return HotSpotEvent(Pid(pid), Tid(tid), ticks, event.ulong("capture_boot_ns"), event.ulong("correlation_id"), event.text("filename"), event.boolean("path_truncated"), event.toString())
    }

    fun evidence(row: HotSpotWireRecord): HotSpotStackEvidence {
        val evidence = row.objectValue.objectField("evidence")
        val attribution = evidence.text("attribution")
        checkHotSpotProtocol(attribution in setOf("event_correlated", "unavailable", "skipped"), "unsupported attribution terminal")
        val source = evidence.array("frames")
        checkHotSpotProtocol(source.size <= limits.maxFramesPerEvidence, "symbolized frame limit exceeded")
        val frames = source.map { frame(it as? JsonObject ?: throw HotSpotProtocolException("frame is not an object")) }
        val provider = (evidence["provider"] as? JsonObject)?.let { HotSpotProvider(it.text("name"), it.text("gadget_revision"), it.text("profiler_revision"), it.text("patch_sha256")) }
        checkHotSpotProtocol(attribution != "event_correlated" || (provider != null && frames.isNotEmpty()), "correlated evidence lacks provider/frames")
        val errors = evidence["raw_errors"]
            ?.let {
            evidence.array("raw_errors").map { error -> (error as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw HotSpotProtocolException("invalid raw error") }
        }.orEmpty()
        return HotSpotStackEvidence(attribution, evidence.text("walk"), evidence.text("scope_lookup"), evidence.optionalText("reason"), provider, frames, errors, evidence.toString())
    }

    private fun processId(
        value: JsonObject,
        key: String,
    ): Int = value.ulong(key).takeIf { it in 1uL..Int.MAX_VALUE.toULong() }?.toInt() ?: throw HotSpotProtocolException("invalid process identifier $key")

    private fun frame(value: JsonObject): HotSpotFrame =
        HotSpotFrame(
        value.uint("provider_type"),
            value.text("frame_type"),
            value.text("function_name"),
            value.optionalUint("function_offset"),
        value.optionalText("source_file"),
            value.optionalUint("source_line"),
            value.optionalUint("source_column"),
            value.optionalUlong("native_address"),
        value.optionalUint("bci"),
            value.optionalUint("raw_bci"),
            value.ulong("raw_position"),
            value.text("position_domain"),
            value.boolean("error_frame"),
        value.optionalText("scope_lookup"),
            value.optionalText("scope_walk"),
            value.optionalText("bci_state"),
            value.optionalText("quality_diagnostic"),
            value.toString(),
    )

    fun summary(row: HotSpotWireRecord): HotSpotReplaySummary {
        val summary = row.objectValue.objectField("summary")
        val counters = summary.objectField("enrichment")
        val event = summary["event_finalization_observation"] as? JsonObject
        val definition = summary["definition_stop_observation"] as? JsonObject
        val physical = event != null &&
            definition != null &&
            listOf("producers_quiesced", "buffer_drained", "receiver_joined", "final_counter_read", "resources_retired").all { event.boolean(it) } &&
            listOf("reader_stopped", "consumer_stopped", "buffers_drained", "auxiliary_stopped").all { definition.boolean(it) }
        val observedLoss = (summary["event_loss_observation"] as? JsonObject)?.optionalUlong("observed")
        return HotSpotReplaySummary(
            counters.ulong("received"),
            counters.ulong("resolved"),
            counters.ulong("unavailable"),
            counters.ulong("skipped"),
            summary.boolean("terminal_accounting_complete"),
            physical,
            observedLoss,
            summary.toString(),
        )
    }
}
