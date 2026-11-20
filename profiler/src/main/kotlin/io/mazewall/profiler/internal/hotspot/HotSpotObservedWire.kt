package io.mazewall.profiler.internal.hotspot

import io.mazewall.profiler.api.HotSpotObservationTarget
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

private const val MAX_LINUX_ERRNO = 4095u
private const val MAX_ARGUMENT_READ = 256
private const val MAX_PENDING = 1024uL
private const val MAX_PENDING_BYTES = 16777216uL
private const val UNKNOWN_LANE_COUNT = 4
private const val EVENT_FIELDS = "session_id,event_id,cpu,sequence,host_pid,host_tid,process_start_ticks,cgroup_id," +
    "capture_boot_ns,clock_domain,event_kind,correlation_id,dirfd,flags,mode,filename,filename_bytes,register_read,argument_read,path_truncated"

/** Observable schema3 invariants from hotspot-bridge/wire; no metadata-lifetime claim. */
internal object HotSpotObservedWire {
    fun record(row: HotSpotWireRecord) {
        val value = row.objectValue
        checkHotSpotProtocol(!value.boolean("policy_eligibility"), "observations cannot be policy eligible")
        val payload = when (row.kind) {
            "session_started", "collector_ready", "collector_error" -> "lifecycle"
            "event" -> "event"
            "enrichment" -> "evidence"
            "recovery" -> "recovery"
            "session_summary" -> "summary"
            else -> throw HotSpotProtocolException("unsupported observed row")
        }
        checkHotSpotProtocol(listOf("lifecycle", "event", "evidence", "recovery", "summary").count { value.containsKey(it) } == 1, "ambiguous row payload")
        val body = value.objectField(payload)
        requiredPayload(payload, body)
        checkHotSpotProtocol((row.eventId != null) == (payload in setOf("event", "evidence", "recovery")), "invalid occurrence row shape")
        checkHotSpotProtocol(':' !in row.sessionId, "invalid session identity")
        if (payload == "lifecycle" && row.kind != "collector_error") {
            checkHotSpotProtocol(!body.containsKey("errno_causes"), "compound causes require collector_error")
        }
    }

    private fun requiredPayload(
        payload: String,
        body: JsonObject,
    ) {
        val fields = when (payload) {
            "lifecycle" -> "pid,clock_domain,boot_ns,runtime_profile,provider"
            "event" -> EVENT_FIELDS
            "evidence" -> "attribution,walk,scope_lookup,provider,frames"
            "recovery" -> "correlation_id,recovery_boot_ns,invalidated"
            "summary" -> "enrichment,collection_health,terminal_accounting_complete"
            else -> throw HotSpotProtocolException("unsupported observed payload")
        }
        required(body, fields)
        if (payload == "evidence") {
            for (frame in body.array("frames")) {
                required(frame as? JsonObject ?: throw HotSpotProtocolException("invalid frame"), "provider_type,frame_type,function_name,function_offset,raw_position,position_domain,error_frame")
            }
        }
    }

    fun lifecycle(
        value: JsonObject,
        target: HotSpotObservationTarget,
        ready: Boolean,
    ) {
        checkHotSpotProtocol(value.ulong("boot_ns") > 0uL, "missing lifecycle timestamp")
        for ((key, expected) in listOf("process_start_ticks" to target.processStartTicks, "cgroup_id" to target.cgroupId, "mount_namespace" to target.mountNamespace)) {
            val actual = value.optionalUlong(key) ?: 0uL
            checkHotSpotProtocol((!ready && actual == 0uL) || actual == expected, "observed lifecycle $key mismatch")
        }
        val mode = value.optionalText("validation_mode").orEmpty()
        checkHotSpotProtocol((!ready && mode.isEmpty()) || mode == "upstream_generation_checks", "unsupported observed validation mode")
        if (ready) checkHotSpotProtocol(value.optionalText("message").isNullOrEmpty() && (value.optionalUint("errno") ?: 0u) == 0u, "ready carries failure")
        value.optionalUint("errno")?.let { checkHotSpotProtocol(it <= MAX_LINUX_ERRNO, "invalid native errno") }
    }

    fun event(
        row: HotSpotWireRecord,
        target: HotSpotObservationTarget,
    ) {
        val value = row.objectValue.objectField("event")
        checkHotSpotProtocol(value.uint("host_pid").toInt() == target.pid.value && value.ulong("process_start_ticks") == target.processStartTicks, "event target generation mismatch")
        checkHotSpotProtocol(
            value.text("session_id") == row.sessionId &&
                value.text("event_id") == row.eventId &&
                value.text("clock_domain") == "CLOCK_BOOTTIME" &&
                value.text("event_kind") == "openat_handler_entry",
            "event scope mismatch",
        )
        val cpu = value.uint("cpu")
        val sequence = value.ulong("sequence")
        checkHotSpotProtocol(sequence > 0uL && row.eventId == "${row.sessionId}:$cpu:$sequence", "event sequence/identity mismatch")
        checkHotSpotProtocol(value.ulong("cgroup_id") == target.cgroupId && value.uint("host_tid") in 1u..Int.MAX_VALUE.toUInt(), "event cgroup/thread mismatch")
        checkHotSpotProtocol(value.ulong("capture_boot_ns") > 0uL && value.ulong("correlation_id") <= Long.MAX_VALUE.toULong(), "invalid event timestamp/correlation")
        arguments(value)
    }

    private fun arguments(value: JsonObject) {
        value.signedInt("dirfd")
        value.ulong("flags")
        value.ulong("mode")
        val bytes = try {
            Base64.getDecoder().decode(value.text("filename_bytes"))
        } catch (failure: IllegalArgumentException) {
            throw HotSpotProtocolException("invalid filename bytes", failure)
        }
        checkHotSpotProtocol(value.text("filename") == bytes.toString(Charsets.UTF_8), "filename bytes mismatch")
        val registers = value.signedInt("register_read")
        val arguments = value.signedInt("argument_read")
        val validRead = if (arguments > 0) {
            arguments <= MAX_ARGUMENT_READ && arguments == bytes.size + 1 && registers == 0
        } else {
            bytes.isEmpty() && (registers == 0 || arguments == registers)
        }
        checkHotSpotProtocol(registers <= 0 && validRead, "invalid argument read accounting")
        checkHotSpotProtocol(value.boolean("path_truncated") == (arguments == MAX_ARGUMENT_READ), "filename truncation mismatch")
    }

    fun summary(
        row: HotSpotWireRecord,
        target: HotSpotObservationTarget,
    ) {
        val value = row.objectValue.objectField("summary")
        val stats = value.objectField("enrichment")
        val terminal = checkedSum(checkedSum(stats.ulong("resolved"), stats.ulong("unavailable")), stats.ulong("skipped"))
        checkHotSpotProtocol(terminal == stats.ulong("received") && value.boolean("terminal_accounting_complete"), "incomplete terminal accounting")
        checkHotSpotProtocol(stats.ulong("max_pending") <= MAX_PENDING && stats.ulong("max_pending_payload_bytes") <= MAX_PENDING_BYTES, "enrichment budget exceeded")
        val selected = value.objectField("selected_event_accounting")
        checkHotSpotProtocol(
            selected.ulong("schema_version") == 1uL &&
                selected.text("scope") == "owned_openat_handler_entry" &&
                selected.text("session_id") == row.sessionId &&
                selected.uint("host_pid").toInt() == target.pid.value &&
                selected.ulong("process_start_ticks") == target.processStartTicks,
            "selected accounting target mismatch",
        )
        checkHotSpotProtocol(
            selected.uint("map_id") > 0u &&
                selected.uint("possible_cpus") > 0u &&
                selected.boolean("complete") &&
                selected.optionalText("reason").isNullOrEmpty(),
            "incomplete selected accounting",
        )
        val attempted = selected.ulong("attempted")
        val submitted = selected.ulong("submitted")
        checkHotSpotProtocol(
            submitted <= attempted &&
                selected.ulong("reserve_failed") == attempted - submitted &&
                selected.ulong("received") <= submitted &&
                selected.ulong("received") == terminal &&
                selected.ulong("terminals") == terminal,
            "selected accounting equations mismatch",
        )
        finality(value, selected.ulong("reserve_failed"))
    }

    private fun finality(
        value: JsonObject,
        reserveFailed: ULong,
    ) {
        val event = value.objectField("event_finalization_observation")
        val definition = value.objectField("definition_stop_observation")
        checkHotSpotProtocol(
            event.text("scope") == "owned_openat_event_ring" &&
                event.text("phase") == "resources_retired" &&
                event.text("stop_cause") == "request" &&
                event.optionalText("error").isNullOrEmpty(),
            "invalid event finalization scope",
        )
        checkHotSpotProtocol(
            listOf("producers_quiesced", "buffer_drained", "receiver_joined", "final_counter_read", "resources_retired").all { event.boolean(it) } &&
                !event.boolean("background_loss_sampler_started") &&
                event.ulong("native_reserve_failures") == reserveFailed,
            "event owner not retired",
        )
        checkHotSpotProtocol(
            listOf("reader_stopped", "consumer_stopped", "buffers_drained", "auxiliary_stopped").all { definition.boolean(it) } &&
                definition.text("semantics") == "owned_definition_perf_drained_partial_loss_snapshot",
            "definition owner not retired",
        )
        val loss = value.objectField("event_loss_observation")
        checkHotSpotProtocol(loss.ulong("observed") == reserveFailed && loss.text("semantics") == "quiescent_native_reserve_failures_partial_snapshot", "final loss mismatch")
        globalLoss(value, event, loss)
    }

    private fun globalLoss(
        value: JsonObject,
        event: JsonObject,
        loss: JsonObject,
    ) {
        checkHotSpotProtocol(
            !event.boolean("loss_complete") &&
                !loss.boolean("complete") &&
                value.text("collection_health") in setOf("partial", "unknown") &&
                !value.containsKey("event_loss") &&
                !value.containsKey("definition_loss"),
            "unsupported global loss claim",
        )
        if (value.containsKey("definition_reader_observation")) {
            val reader = value.objectField("definition_reader_observation")
            for (key in listOf("lost", "no_data", "read_error")) reader.ulong(key)
            checkHotSpotProtocol(reader.ulong("buffer_bytes_per_cpu") <= Long.MAX_VALUE.toULong(), "invalid definition buffer size")
            reader.text("semantics")
            checkHotSpotProtocol(!reader.boolean("complete"), "unsupported definition completeness")
        }
        val lanes = value.array("unproven_collection_sources").map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }
        checkHotSpotProtocol(lanes.size == UNKNOWN_LANE_COUNT && lanes.toSet() == setOf("definitions", "enrichment", "wire", "retention"), "unknown collection lanes omitted")
        checkHotSpotProtocol(!value.containsKey("errors") || value.array("errors").isEmpty(), "collector summary contains errors")
    }

    private fun checkedSum(
        a: ULong,
        b: ULong,
    ): ULong {
        checkHotSpotProtocol(b <= ULong.MAX_VALUE - a, "terminal accounting overflow")
        return a + b
    }

    private fun required(
        value: JsonObject,
        fields: String,
    ) {
        for (key in fields.split(',')) checkHotSpotProtocol(value[key] != null && value[key] != JsonNull, "missing/null $key")
    }
}

private fun JsonObject.signedInt(key: String): Int =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
    ?: throw HotSpotProtocolException("invalid int32 $key")
