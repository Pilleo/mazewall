package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KoogThreadSummaryTest {
    private val line =
        "KOOG_THREAD_V1_OK registry=upstream worker=platform fs=isolated socket=denied caller=unchanged virtual=true unconfined=true " +
            "validation=pass overload=rejected cancellation=cooperative ownership=checked retirement=complete fallback=false replay=false"

    @Test
    fun `accepts one complete thread summary amid build noise`() {
        assertEquals(line, requireKoogThreadSummary("build noise\n$line\n"))
    }

    @Test
    fun `rejects missing duplicate partial and contradictory thread summaries`() {
        val invalid = listOf(
            "",
            "KOOG_NATIVE_FRESH_OK",
            "KOOG_CONTAINMENT_V1_OK read=denied write=denied symlink=denied network=denied process=denied controls=pass retirement=pass",
            "$line\n$line",
            line.substringBefore(" retirement="),
            line.replace(" retirement=complete", ""),
            line.replace("KOOG_THREAD_V1_OK", "KOOG_THREAD_V1_FAIL"),
            "$line\nKOOG_THREAD_V1_FAIL",
        )
        for (output in invalid) {
            assertThrows<IllegalStateException> { requireKoogThreadSummary(output) }
        }
    }

    @Test
    fun `rejects altered thread contract fields`() {
        val alterations = listOf(
            "registry=upstream" to "registry=fake",
            "worker=platform" to "worker=virtual",
            "fs=isolated" to "fs=unrestricted",
            "socket=denied" to "socket=allowed",
            "caller=unchanged" to "caller=restricted",
            "virtual=true" to "virtual=false",
            "unconfined=true" to "unconfined=false",
            "validation=pass" to "validation=fail",
            "overload=rejected" to "overload=accepted",
            "cancellation=cooperative" to "cancellation=forced",
            "ownership=checked" to "ownership=unchecked",
            "retirement=complete" to "retirement=incomplete",
            "fallback=false" to "fallback=true",
            "replay=false" to "replay=true",
        )
        for ((expected, altered) in alterations) {
            assertThrows<IllegalStateException> { requireKoogThreadSummary(line.replace(expected, altered)) }
        }
    }
}
