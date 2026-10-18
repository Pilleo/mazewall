package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotCollectorFailure
import io.mazewall.profiler.internal.hotspot.HotSpotProtocol
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotSessionEvent
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import io.mazewall.profiler.internal.hotspot.HotSpotWireRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class HotSpotProtocolTest {
    private val protocol = HotSpotProtocol(123, 456uL)

    @Test
    fun `started and native ready have distinct lifecycle meanings`() {
        assertIs<HotSpotSessionEvent.Started>(protocol.controlEvent(row("session_started")))
        assertIs<HotSpotSessionEvent.Ready>(protocol.controlEvent(row("collector_ready")))
    }

    @Test
    fun `wrong target generation profile and provider pins are refused`() {
        for ((old, replacement) in listOf(
            "\"pid\":123" to "\"pid\":124",
                "456" to "457",
                "CLOCK_BOOTTIME" to "CLOCK_REALTIME",
                "temurin25_xint_serial" to "jit",
                "e4dad227" to "ffffffff",
            "7097db14" to "ffffffff",
        )) {
            assertFailsWith<IllegalArgumentException> { protocol.controlEvent(row("collector_ready", old, replacement)) }
        }
    }

    @Test
    fun `permission errno is preserved as a typed collector failure`() {
        for (errno in listOf(1u, 13u)) {
            val record = row("collector_error", "\"pid\":123", "\"pid\":123,\"message\":\"native failure\",\"errno\":$errno")
            val event = assertIs<HotSpotSessionEvent.Failure>(protocol.controlEvent(record))
            assertEquals(errno, assertIs<HotSpotCollectorFailure>(event.cause).nativeErrno)
        }
    }

    @Test
    fun `legacy schema requires explicit replay rather than production admission`() {
        val old = text("collector_attached").replace("\"schema_version\":2", "\"schema_version\":1")
        assertFailsWith<IllegalArgumentException> { HotSpotWireReader(HotSpotReplayLimits(), 2uL).read((old + "\n").byteInputStream()) {} }
        assertFailsWith<IllegalArgumentException> { protocol.controlEvent(row("collector_attached")) }
    }

    @Test
    fun `historical measured lifecycle retains its original now unsupported provider identity`() {
        val measured = HotSpotProtocol(1113254, 33129030uL)
        var records = 0
        requireNotNull(javaClass.getResourceAsStream("/hotspot/production-lifecycle-projection.jsonl")).use { input ->
            HotSpotWireReader(HotSpotReplayLimits(), 2uL).read(input) {
                val refusal = assertFailsWith<IllegalArgumentException> { measured.controlEvent(it) }
                assertEquals("unsupported production provider provenance", refusal.message)
                records++
            }
        }
        assertEquals(2, records)
    }

    @Test
    fun `event payload checks actual host PID wire field`() {
        var record: HotSpotWireRecord? = null
        val text = text("event").replace("\"lifecycle\":", "\"event\":").replace("\"pid\":", "\"host_pid\":")
        HotSpotWireReader(HotSpotReplayLimits(), 2uL).read((text + "\n").byteInputStream()) { record = it }
        assertNull(protocol.controlEvent(requireNotNull(record)))
    }

    private fun row(
        kind: String,
        old: String = "never present",
        replacement: String = "",
    ): HotSpotWireRecord {
        var record: HotSpotWireRecord? = null
        HotSpotWireReader(HotSpotReplayLimits(), 2uL).read((text(kind).replace(old, replacement) + "\n").byteInputStream()) { record = it }
        return requireNotNull(record)
    }

    private fun text(kind: String): String =
        """
        {"schema_version":2,"session_id":"session","kind":"$kind","lifecycle":{
        "pid":123,"process_start_ticks":456,"clock_domain":"CLOCK_BOOTTIME","boot_ns":999,
        "runtime_profile":"temurin25_xint_serial","jvm_build_id":"e4dad22744e32e4d71755f4a58c5cf1f1d336f87",
        "jvm_sha256":"5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c",
        "provider":{"name":"otel-ebpf-profiler-via-gadget","gadget_revision":"7097db144ccd8dab15373f27ec98d2c3d22e4588",
        "profiler_revision":"ef6cf29492c8a980f889052903fa2dd0c0765cc0",
        "patch_sha256":"473acb5ab31168cdba2ad390c105ca83a163e91d9443e53ce45cfecd6f30c58b"}}}
    """.trimIndent().replace("\n", "")
}
