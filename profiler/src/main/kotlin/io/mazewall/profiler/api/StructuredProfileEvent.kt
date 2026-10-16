package io.mazewall.profiler.api

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.internal.hotspot.HotSpotEvent
import io.mazewall.profiler.internal.hotspot.optionalText
import io.mazewall.profiler.internal.hotspot.optionalUlong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/** Raw handler-entry arguments are diagnostic, not resolved Landlock operands or successful operation results. */
public class StructuredProfileEvent internal constructor(
    value: HotSpotEvent,
) {
    private val arguments = Json.parseToJsonElement(value.rawJson) as JsonObject
    private val bytes = arguments.optionalText("filename_bytes")?.let { Base64.getDecoder().decode(it) }
    public val pid: Pid = value.pid
    public val tid: Tid = value.tid
    public val processStartTicks: ULong = value.processStartTicks
    public val captureBootNanos: ULong = value.captureBootNanos
    public val correlationId: ULong = value.correlationId
    public val filename: String = value.filename
    public val filenameBytes: ByteArray? get() = bytes?.copyOf()
    public val pathTruncated: Boolean = value.pathTruncated
    public val dirfd: Int? = optionalInt("dirfd")
    public val flags: ULong? = arguments.optionalUlong("flags")
    public val mode: ULong? = arguments.optionalUlong("mode")
    public val argumentRead: Int? = optionalInt("argument_read")
    public val registerRead: Int? = optionalInt("register_read")
    public val rawJson: String = value.rawJson

    private fun optionalInt(key: String): Int? = (arguments[key] as? JsonPrimitive)?.content?.toIntOrNull()
}
