package io.mazewall.profiler.internal

import io.mazewall.platform.seccomp.daemon.SeccompStreamEnd
import java.io.DataInputStream
import java.nio.ByteBuffer

/** The complete record ends the logical stream; transport EOF/reset is not completion evidence. */
internal object TraceStreamEndWire {
    const val MARKER: Int = Int.MIN_VALUE
    private const val VERSION = 1
    private const val RECORD_BYTES = 9

    fun encode(reason: SeccompStreamEnd): ByteArray =
        ByteBuffer
            .allocate(RECORD_BYTES)
            .putInt(MARKER)
            .putInt(VERSION)
            .put(
            when (reason) {
                SeccompStreamEnd.PRODUCER_EXIT -> 1
                SeccompStreamEnd.PASS_THROUGH -> 2
            }.toByte(),
        ).array()

    fun read(input: DataInputStream): SeccompStreamEnd {
        require(input.readInt() == VERSION) { "Unknown trace terminal version" }
        val reason = when (input.readUnsignedByte()) {
            1 -> SeccompStreamEnd.PRODUCER_EXIT
            2 -> SeccompStreamEnd.PASS_THROUGH
            else -> throw IllegalArgumentException("Unknown trace terminal reason")
        }
        return reason
    }
}
