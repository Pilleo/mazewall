package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals

class TierELifecycleScopeTest {
    @Test
    fun `lifecycle scope runs only dictionary modes across both marker transports`() {
        assertEquals(
            setOf(
                "dictionary_full_stream_marker_uprobe",
                "dictionary_full_stream_marker_usdt",
                "dictionary_unique_edge_marker_uprobe",
                "dictionary_unique_edge_marker_usdt",
            ),
            TierEMatchedBenchmark.lifecycleVariantNames(0).toSet(),
        )
    }
}
