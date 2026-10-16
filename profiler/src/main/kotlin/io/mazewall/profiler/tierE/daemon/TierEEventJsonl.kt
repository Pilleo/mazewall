package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.ResolvedSyscall
import io.mazewall.profiler.internal.attribution.TierEProfile
import io.mazewall.profiler.internal.attribution.TierETerminalReason
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lossless, versioned, policy-ineligible wire rows for one registered target session. */
internal object TierEEventJsonl {
    fun header(target: TierERegisteredTarget): String =
        buildJsonObject {
            put("schema_version", 1)
            put("type", "header")
            put("registration_id", target.registrationId)
            put("session_epoch", target.sessionEpoch.value.toString())
            put("target_pid", target.pid)
            put("process_incarnation", target.processIncarnation.toString())
            put("clock_domain", "CLOCK_MONOTONIC")
            put("observation_scope", "SYS_ENTER_TRACEPOINT")
            put("marker_version", "v1")
            put("stack_transport", "session-bound-mzse-v2")
            put("policy_eligibility", false)
        }.toString()

    fun event(
        target: TierERegisteredTarget,
        ordinal: Int,
        resolved: ResolvedSyscall,
    ): String {
        require(ordinal > 0) { "event ordinal must be positive" }
        val observation = resolved.observation
        val timestamp = requireNotNull(observation.kernelTimestampNanos) { "ring event lacks its original kernel timestamp" }
        val tgid = requireNotNull(observation.hostTgid) { "ring event lacks its original host TGID" }
        val frames = resolved.stackDefinition?.frames.orEmpty()
        return buildJsonObject {
            put("schema_version", 1)
            put("type", "event")
            put("registration_id", target.registrationId)
            put("session_epoch", target.sessionEpoch.value.toString())
            put("ordinal", ordinal.toString())
            put("clock_domain", "CLOCK_MONOTONIC")
            put("kernel_timestamp_ns", timestamp.toString())
            put("host_tgid", tgid.toString())
            put(
                "host_tid",
                observation.task.tid
                .toUInt()
                .toString(),
            )
            put("syscall_number", observation.syscallNumber)
            put("arguments", JsonArray(observation.args.map { JsonPrimitive(it.toULong().toString()) }))
            put("capture_flags", observation.captureFlags)
            put("arguments_valid", observation.captureFlags and SyscallInvocationEvent.FLAG_ARGUMENT_READ_FAILED == 0)
            put("invocation_id", observation.invocationId?.let { JsonPrimitive(it.value.toString()) } ?: JsonNull)
            put("attribution_status", observation.attributionStatus.name)
            put("resolution_status", resolved.resolutionStatus.name)
            put("stack_id", resolved.stackDefinition?.let { JsonPrimitive(it.stackTraceId.value.toString()) } ?: JsonNull)
            put("stack_capture_quality", resolved.stackDefinition?.let { JsonPrimitive(it.captureQuality.name) } ?: JsonNull)
            put("execution_id", resolved.executionContext?.let { JsonPrimitive(it.executionId.value.toString()) } ?: JsonNull)
            put("execution_kind", resolved.executionContext?.let { JsonPrimitive(it.kind.name) } ?: JsonNull)
            put(
                "frames",
                JsonArray(
                    frames.map { frame ->
                        buildJsonObject {
                            put("class_loader_identity", frame.classLoaderIdentity)
                            put("class_name", frame.className)
                            put("method_name", frame.methodName)
                            put("descriptor", frame.descriptor)
                            put("bytecode_location", frame.bytecodeLocation.toString())
                            put("kind", frame.kind.name)
                        }
                    },
                ),
            )
            put("policy_eligibility", false)
        }.toString()
    }

    fun terminal(
        target: TierERegisteredTarget,
        profile: TierEProfile,
        reason: TierETerminalReason,
    ): String {
        val integrity = profile.integrity
        val dimensions = integrity.verdictDimensions
        return buildJsonObject {
            put("schema_version", 1)
            put("type", "terminal")
            put("registration_id", target.registrationId)
            put("session_epoch", target.sessionEpoch.value.toString())
            put("event_count", profile.syscalls.size.toString())
            put("attributed_count", profile.syscalls.count { it.resolutionStatus == ResolutionStatus.RESOLVED }.toString())
            put("unattributed_count", profile.syscalls.count { it.resolutionStatus != ResolutionStatus.RESOLVED }.toString())
            put("observed_loss_count", integrity.observedLosses.toString())
            put("ambient_inactive_count", integrity.ambientInactiveObservations.toString())
            put("marker_state_unavailable_count", integrity.markerStateUnavailableObservations.toString())
            put("capture_admitted_count", integrity.allTargetAdmittedCount?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("capture_submitted_count", integrity.allTargetSubmittedCount?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("terminal_reason", reason.name)
            put(
                "capture_complete",
                reason == TierETerminalReason.COMPLETE &&
                    integrity.observedLosses == 0L &&
                    dimensions.captureDomainValid &&
                    dimensions.lifecycleValid &&
                    integrity.allTargetAdmittedCount != null &&
                    integrity.allTargetAdmittedCount == integrity.allTargetSubmittedCount &&
                    integrity.allTargetSubmittedCount == profile.syscalls.size.toLong(),
            )
            put("transport_complete", dimensions.transportComplete)
            put("attribution_complete", dimensions.attributionCoverageValid)
            put("export_complete", true)
            put("policy_eligibility", false)
        }.toString()
    }
}
