package io.mazewall.profiler.internal.attribution

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One raw retained-edge map entry: a 24-byte key and a 96-byte value. */
internal class RecoveryMapEntry(
    key: ByteArray,
    value: ByteArray,
) {
    private val keyBytes = key.copyOf()
    private val valueBytes = value.copyOf()
    val key: ByteArray get() = keyBytes.copyOf()
    val value: ByteArray get() = valueBytes.copyOf()

    init {
        require(keyBytes.size == RECOVERY_KEY_BYTES) { "recovery key must be 24 bytes" }
        require(valueBytes.size == RECOVERY_VALUE_BYTES) { "recovery value must be 96 bytes" }
    }

    override fun equals(other: Any?): Boolean = other is RecoveryMapEntry && keyBytes.contentEquals(other.keyBytes) && valueBytes.contentEquals(other.valueBytes)

    override fun hashCode(): Int = 31 * keyBytes.contentHashCode() + valueBytes.contentHashCode()

    private companion object {
        const val RECOVERY_KEY_BYTES = 24
        const val RECOVERY_VALUE_BYTES = 96
    }
}

/** One bounded page of a map scan. */
internal data class RecoveryScanPage(
    val entries: List<RecoveryMapEntry>,
    /** Opaque cursor resuming after the last returned entry, or null when complete. */
    val nextCursor: ByteArray?,
    val complete: Boolean,
)

/** Reads the session-bound retained-edge map in bounded batches. */
internal fun interface RecoveryMapReader {
    fun scanBatch(
        cursor: ByteArray?,
        maxEntries: Int,
    ): RecoveryScanPage
}

/** Decoded key fields of a retained-edge entry. */
internal data class RecoveryEdgeKey(
    val sessionEpoch: Long,
    val stackTraceId: Long,
    val syscallNumber: Int,
    val captureFlags: Int,
)

/** Decodes the 24-byte little-endian key: epoch u64, stack u64, syscall s32, flags u32. */
internal fun decodeRecoveryKey(key: ByteArray): RecoveryEdgeKey {
    require(key.size == RECOVERY_MAP_KEY_BYTES) { "recovery key must be 24 bytes" }
    val buffer = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
    return RecoveryEdgeKey(
        sessionEpoch = buffer.long,
        stackTraceId = buffer.long,
        syscallNumber = buffer.int,
        captureFlags = buffer.int,
    )
}

/**
 * Converts a raw entry into a session-bound candidate.
 * Returns null when the key and value disagree (syscall or flags mismatch),
 * so a corrupt entry can never become recovered coverage.
 */
internal fun RecoveryMapEntry.toCandidate(
    sessionEpoch: SessionEpoch,
    processIncarnation: ProcessIncarnation,
): CoverageCandidate? {
    val decodedKey = decodeRecoveryKey(key)
    val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
    val ktime = buffer.long
    val tgid = buffer.int
    val tid = buffer.int
    val syscall = buffer.int
    val flags = buffer.int
    val invocation = buffer.long
    val sequence = buffer.long
    val args = List(RECOVERY_ARGUMENT_COUNT) { buffer.long }
    val generation = buffer.int
    val reserved = buffer.int
    val valid =
        syscall == decodedKey.syscallNumber &&
            flags == decodedKey.captureFlags &&
            invocation > 0 &&
            sequence > 0 &&
            tgid > 0 &&
            tid > 0 &&
            generation > 0 &&
            reserved == 0 &&
            decodedKey.sessionEpoch == sessionEpoch.value &&
        decodedKey.stackTraceId in 1..StackTraceId.MAX_VALUE
    return if (valid) {
        CoverageCandidate(
            sessionEpoch = sessionEpoch,
            canonicalStackTraceId = StackTraceId(decodedKey.stackTraceId),
            syscallNumber = syscall,
            captureFlags = flags,
            representative = CoverageRepresentative(ktime, tgid, tid, InvocationId(invocation), sequence, args),
            processIncarnation = processIncarnation,
            attachmentGeneration = generation,
        )
    } else {
        null
    }
}

private const val RECOVERY_MAP_KEY_BYTES = 24
private const val RECOVERY_ARGUMENT_COUNT = 6
