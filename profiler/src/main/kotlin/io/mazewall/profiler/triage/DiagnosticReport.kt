package io.mazewall.profiler.triage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Serialize arbitrary probe text through the same JSON model as profile evidence. */
internal fun diagnosticReportJson(
    timestamp: Long,
    diagnostics: Map<String, String>,
): String =
    JsonObject(
        mapOf(
            "timestamp" to JsonPrimitive(timestamp),
            "diagnostics" to JsonObject(diagnostics.mapValues { JsonPrimitive(it.value) }),
        ),
    ).toString()
