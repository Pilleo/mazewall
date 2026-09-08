package io.mazewall.profiler.attribution

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed-width little-endian transport for agent markers and collector replay.
 *
 * It is deliberately independent of JVM object serialization so a native agent can implement
 * the same contract. A decoder rejects a different version or payload shape rather than making
 * a best-effort interpretation of an event.
 */
public object InvocationProtocol {
    private const val MAGIC: Int = 0x4D_5A_41_54 // "MZAT"
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE: Int = 8
    private const val COMMON_SIZE: Int = 28
    private const val ACTIVATE_SIZE: Int = 8
    private const val END_RESTORE_SIZE: Int = 16
    private const val SYSCALL_SIZE: Int = 4 + SyscallAttribution.ARGUMENT_COUNT * Long.SIZE_BYTES

    private const val ACTIVATE: Byte = 1
    private const val END_RESTORE: Byte = 2
    private const val SYSCALL: Byte = 3

    public fun encode(event: InvocationEvent): ByteArray {
        val type = event.type()
        val payloadSize = event.payloadSize()
        return ByteBuffer
            .allocate(HEADER_SIZE + COMMON_SIZE + payloadSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION)
            .put(type)
            .putShort((COMMON_SIZE + payloadSize).toShort())
            .putLong(event.task.processEpoch)
            .putInt(event.task.tid)
            .putLong(event.task.taskEpoch)
            .putLong(event.sequence)
            .apply { putPayload(event) }
            .array()
    }

    public fun decode(bytes: ByteArray): InvocationEvent {
        require(bytes.size >= HEADER_SIZE + COMMON_SIZE) { "marker is shorter than its mandatory header" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC) { "unexpected marker magic" }
        require(buffer.get() == VERSION) { "unsupported marker protocol version" }
        val type = buffer.get()
        val payloadSize = buffer.short.toInt() and 0xFFFF
        require(payloadSize == buffer.remaining()) { "marker payload length does not match record size" }
        val task = TaskIdentity(buffer.long, buffer.int, buffer.long)
        val sequence = buffer.long
        val event = when (type) {
            ACTIVATE -> {
                requireRemaining(buffer, ACTIVATE_SIZE)
                InvocationEvent.Activate(task, sequence, InvocationId(buffer.long))
            }

            END_RESTORE -> {
                requireRemaining(buffer, END_RESTORE_SIZE)
                val ended = InvocationId(buffer.long)
                val restored = buffer.long.takeIf { it != 0L }?.let(::InvocationId)
                InvocationEvent.EndRestore(task, sequence, ended, restored)
            }

            SYSCALL -> {
                requireRemaining(buffer, SYSCALL_SIZE)
                val syscallNumber = buffer.int
                InvocationEvent.Syscall(task, sequence, syscallNumber, List(SyscallAttribution.ARGUMENT_COUNT) { buffer.long })
            }

            else -> throw IllegalArgumentException("unknown marker event type $type")
        }
        require(!buffer.hasRemaining()) { "marker has trailing bytes" }
        return event
    }

    private fun InvocationEvent.type(): Byte =
        when (this) {
        is InvocationEvent.Activate -> ACTIVATE
        is InvocationEvent.EndRestore -> END_RESTORE
        is InvocationEvent.Syscall -> SYSCALL
    }

    private fun InvocationEvent.payloadSize(): Int =
        when (this) {
        is InvocationEvent.Activate -> ACTIVATE_SIZE
        is InvocationEvent.EndRestore -> END_RESTORE_SIZE
        is InvocationEvent.Syscall -> SYSCALL_SIZE
    }

    private fun ByteBuffer.putPayload(event: InvocationEvent) {
        when (event) {
            is InvocationEvent.Activate -> putLong(event.invocationId.value)
            is InvocationEvent.EndRestore -> {
                putLong(event.endedInvocationId.value)
                putLong(event.restoredInvocationId?.value ?: 0L)
            }

            is InvocationEvent.Syscall -> {
                require(event.args.size == SyscallAttribution.ARGUMENT_COUNT) {
                    "syscall markers need exactly ${SyscallAttribution.ARGUMENT_COUNT} arguments"
                }
                putInt(event.syscallNumber)
                event.args.forEach(::putLong)
            }
        }
    }

    private fun requireRemaining(
        buffer: ByteBuffer,
        expected: Int,
    ) {
        require(buffer.remaining() == expected) { "marker event payload has an invalid size" }
    }
}
