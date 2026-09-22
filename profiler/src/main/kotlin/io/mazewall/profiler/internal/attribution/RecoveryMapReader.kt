package io.mazewall.profiler.internal.attribution

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One raw retained-edge map entry: a 16-byte key and an 88-byte value. */
internal data class RecoveryMapEntry(
    val key: ByteArray,
    val value: ByteArray,
) {
    init {
        require(key.size == 16) { "recovery key must be 16 bytes" }
        require(value.size == 88) { "recovery value must be 88 bytes" }
    }

    override fun equals(other: Any?): Boolean = other is RecoveryMapEntry && key.contentEquals(other.key) && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * key.contentHashCode() + value.contentHashCode()
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
    val sessionTag: Int,
    val stackTraceId: Long,
    val syscallNumber: Int,
    val captureFlags: Int,
)

/** Decodes the 16-byte little-endian key: session_tag u32, stack u32, syscall s32, flags u32. */
internal fun decodeRecoveryKey(key: ByteArray): RecoveryEdgeKey {
    require(key.size == 16) { "recovery key must be 16 bytes" }
    val buffer = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
    return RecoveryEdgeKey(
        sessionTag = buffer.int,
        stackTraceId = buffer.int.toLong() and 0xffff_ffffL,
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
    val args = List(6) { buffer.long }
    if (syscall != decodedKey.syscallNumber || flags != decodedKey.captureFlags) return null
    if (invocation <= 0 || sequence <= 0 || tgid <= 0 || tid <= 0) return null
    return CoverageCandidate(
        sessionEpoch = sessionEpoch,
        canonicalStackTraceId = StackTraceId(decodedKey.stackTraceId),
        syscallNumber = syscall,
        captureFlags = flags,
        observedKtimeNanos = ktime,
        tgid = tgid,
        tid = tid,
        invocationId = InvocationId(invocation),
        markerSequence = sequence,
        args = args,
        processIncarnation = processIncarnation,
    )
}
