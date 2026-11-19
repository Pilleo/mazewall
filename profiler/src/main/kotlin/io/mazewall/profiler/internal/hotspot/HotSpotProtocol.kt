package io.mazewall.profiler.internal.hotspot

import io.mazewall.ffi.NativeConstants
import io.mazewall.profiler.api.HotSpotObservationTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val GADGET_REVISION = "7097db144ccd8dab15373f27ec98d2c3d22e4588"
private const val PROFILER_REVISION = "ef6cf29492c8a980f889052903fa2dd0c0765cc0"
private const val PATCH_SHA256 = "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035"
private const val JVM_BUILD_ID = "e4dad22744e32e4d71755f4a58c5cf1f1d336f87"
private const val JVM_SHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"
private const val MAX_LINUX_ERRNO = 4095u
private const val MAX_ERRNO_CAUSES = 3

internal class HotSpotCollectorFailure(
    message: String,
    val nativeErrno: UInt?,
    nativeErrnos: List<UInt> = listOfNotNull(nativeErrno),
) : IllegalStateException(message) {
    val nativeErrnos: List<UInt> = java.util.List.copyOf(nativeErrnos)
}

/** Negotiated decoding. Lifecycle order/ownership is decided by HotSpotSessionMachine. */
internal class HotSpotProtocol(
    private val expectedPid: Int,
    private val expectedStartTicks: ULong,
    private val expectation: HotSpotCaptureExpectation = HotSpotCaptureExpectation.Strict,
) {
    init {
        require(expectedPid > 0 && expectedStartTicks > 0uL)
        if (expectation is HotSpotCaptureExpectation.Observed) {
            require(expectation.target.pid.value == expectedPid && expectation.target.processStartTicks == expectedStartTicks)
        }
    }

    fun controlEvent(row: HotSpotWireRecord): HotSpotSessionEvent? {
        checkHotSpotProtocol(row.objectValue.ulong("schema_version") == expectation.schema, "production protocol requires negotiated schema")
        if (expectation is HotSpotCaptureExpectation.Observed) observed(row, expectation.target)
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
                val primary = value.optionalUint("errno")
                HotSpotSessionEvent.Failure(HotSpotCollectorFailure(value.text("message"), primary, errnoCauses(value, primary)))
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

    private fun observed(
        row: HotSpotWireRecord,
        target: HotSpotObservationTarget,
    ) {
        HotSpotObservedWire.record(row)
        when (row.kind) {
            "collector_ready" -> HotSpotObservedWire.lifecycle(row.objectValue.objectField("lifecycle"), target, ready = true)
            "event" -> HotSpotObservedWire.event(row, target)
            "enrichment" -> validateProvider(row.objectValue.objectField("evidence").objectField("provider"))
            "session_summary" -> HotSpotObservedWire.summary(row, target)
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
        checkHotSpotProtocol(value.text("clock_domain") == "CLOCK_BOOTTIME" && value.text("runtime_profile") == expectation.runtimeProfile, "unsupported lifecycle clock/profile")
        value.ulong("boot_ns")
        validateProvider(value.objectField("provider"))
        if (expectation is HotSpotCaptureExpectation.Observed) {
            HotSpotObservedWire.lifecycle(value, expectation.target, ready = false)
        } else {
            checkHotSpotProtocol(!value.containsKey("errno_causes"), "schema2 cannot carry compound observation causes")
        }
        return value
    }

    private fun validateProvider(provider: JsonObject) {
        checkHotSpotProtocol(
            provider.text("name") == "otel-ebpf-profiler-via-gadget" &&
            provider.text("gadget_revision") == GADGET_REVISION &&
            provider.text("profiler_revision") == PROFILER_REVISION &&
            provider.text("patch_sha256") == PATCH_SHA256,
                "unsupported production provider provenance",
        )
    }

    private fun errnoCauses(
        value: JsonObject,
        primary: UInt?,
    ): List<UInt> {
        if (!value.containsKey("errno_causes")) return listOfNotNull(primary)
        val causes = value.array("errno_causes").map { element ->
            val raw = element as? JsonPrimitive
            raw
                ?.takeUnless { it.isString }
                ?.content
                ?.toUIntOrNull()
                ?.takeIf { it in 1u..MAX_LINUX_ERRNO }
                ?: throw HotSpotProtocolException("invalid errno cause")
        }
        checkHotSpotProtocol(
            causes.size in 1..MAX_ERRNO_CAUSES &&
                causes.distinct().size == causes.size &&
                causes.first() == primary &&
                causes.drop(1).all { it == NativeConstants.EPERM.toUInt() || it == NativeConstants.EACCES.toUInt() },
            "invalid compound permission causes",
        )
        return causes
    }
}
