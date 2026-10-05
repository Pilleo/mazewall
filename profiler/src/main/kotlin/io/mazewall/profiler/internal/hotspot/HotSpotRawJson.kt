package io.mazewall.profiler.internal.hotspot

import kotlinx.serialization.json.JsonElement

/** Parser-owned immutable tree; canonical diagnostics are materialized only on demand. */
internal class HotSpotRawJson(
    value: JsonElement,
    render: (JsonElement) -> String = { it.toString() },
) {
    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) { render(value) }
}
