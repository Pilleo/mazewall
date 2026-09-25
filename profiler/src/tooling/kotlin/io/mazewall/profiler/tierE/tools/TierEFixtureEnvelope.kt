package io.mazewall.profiler.tierE.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact benchmark-only MZSE v2 negative envelope; never a production emitter. */
public object TierEFixtureEnvelope {
    public fun create(scenario: String): ByteArray {
        require(scenario == "unclosed" || scenario == "session-mismatch") { "Unsupported negative envelope: $scenario" }
        val payload = byteArrayOf(77, 90, 83, 68, 2, 0)
        return ByteBuffer
            .allocate(40 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
            put("MZSE".toByteArray(Charsets.US_ASCII))
            putShort(2)
            putShort(if (scenario == "unclosed") 0 else 1)
            putLong(if (scenario == "unclosed") 0x4d5aL else 0x4d5bL)
            putLong(1)
            putLong(0)
            putLong(payload.size.toLong())
            put(payload)
        }.array()
    }
}
