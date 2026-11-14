package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.seccomp.SeccompNotification
import java.nio.ByteBuffer

/** Fixed Live-only correlation frame. Session authority is the authenticated socket owner. */
internal object LiveObservationProtocol {
    const val PREFIX_BYTES = 40
    const val REQUEST_BYTES = 104
    const val RESPONSE_BYTES = REQUEST_BYTES + Int.SIZE_BYTES
    private const val MAGIC = 0x4d574f42
    private const val VERSION = 1
    private const val REQUEST = 1
    private const val DEADLINE_OFFSET = 32
    private const val TID_OFFSET = 40

    fun request(
        notification: SeccompNotification,
        sequence: Long,
        deadline: ConfinedDeadline,
    ): ByteArray =
        ByteBuffer
            .allocate(REQUEST_BYTES)
            .apply {
                putInt(MAGIC)
                putInt(VERSION)
                putInt(REQUEST)
                putInt(REQUEST_BYTES)
                putLong(sequence)
                putLong(notification.id)
                putLong(deadline.nanos)
                putInt(notification.pid)
                putInt(notification.nr)
                putInt(notification.arch)
                putInt(0)
                notification.args.forEach(::putLong)
            }.array()

    fun matched(
        request: ByteArray,
        response: ByteArray,
    ): Boolean {
        require(response.size == RESPONSE_BYTES && response.copyOfRange(0, REQUEST_BYTES).contentEquals(request))
        return when (ByteBuffer.wrap(response).getInt(REQUEST_BYTES)) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("Unknown Live observation outcome")
        }
    }

    fun deadline(prefix: ByteArray): ConfinedDeadline {
        require(prefix.size == PREFIX_BYTES)
        return ConfinedDeadline(ByteBuffer.wrap(prefix).getLong(DEADLINE_OFFSET))
    }

    fun validate(
        request: ByteArray,
        previous: Long,
    ): Long {
        require(request.size == REQUEST_BYTES)
        val bytes = ByteBuffer.wrap(request)
        require(bytes.int == MAGIC && bytes.int == VERSION && bytes.int == REQUEST && bytes.int == REQUEST_BYTES)
        val sequence = bytes.long
        require(sequence > 0 && sequence > previous) { "Invalid Live observation sequence" }
        bytes.long // Notification identity is correlation only.
        require(bytes.long > 0 && bytes.int > 0) { "Invalid Live observation deadline or TID" }
        bytes.int // Raw syscall number is correlation only.
        bytes.int // Raw audit architecture is correlation only.
        require(bytes.int == 0) { "Invalid Live observation reserved field" }
        return sequence
    }

    fun tid(request: ByteArray): Int = ByteBuffer.wrap(request).getInt(TID_OFFSET)

    fun response(
        request: ByteArray,
        matched: Boolean,
    ): ByteArray =
        ByteBuffer
            .allocate(RESPONSE_BYTES)
            .put(request)
            .putInt(if (matched) 1 else 0)
            .array()
}
