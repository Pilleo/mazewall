package io.mazewall.profiler.internal

import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionCause
import io.mazewall.profiler.api.PathResolutionFailure
import java.io.DataInputStream
import java.io.DataOutputStream

/** Private same-distribution profiler protocol. Portal frames are unrelated and unchanged. */
internal object PathResolutionWire {
    const val VERSION = 1
    private const val MAX_FAILURES = 8
    private const val MAX_DIAGNOSTIC_BYTES = 8192
    private const val MAX_CAUSE_TYPE_BYTES = 512
    private const val NOT_APPLICABLE = 0
    private const val RESOLVED = 1
    private const val PARTIAL = 2
    private const val FAILED = 3
    private const val VM_READ_DENIED = 0
    private const val MISSING = 1
    private const val DIRFD_UNRESOLVED = 2
    private const val TRUNCATED = 3

    fun write(
        output: DataOutputStream,
        resolution: PathResolution,
    ) {
        output.writeByte(
            when (resolution) {
            PathResolution.NotApplicable -> NOT_APPLICABLE
            is PathResolution.Resolved -> RESOLVED
            is PathResolution.Partial -> PARTIAL
            is PathResolution.Failed -> FAILED
        },
        )
        require(resolution.failures.size <= MAX_FAILURES)
        output.writeInt(resolution.failures.size)
        for (failure in resolution.failures) {
            output.writeInt(failure.operandIndex)
            output.writeByte(
                when (failure.reason) {
                PathResolutionFailure.Reason.VmReadDenied -> VM_READ_DENIED
                PathResolutionFailure.Reason.Missing -> MISSING
                PathResolutionFailure.Reason.DirFdUnresolved -> DIRFD_UNRESOLVED
                PathResolutionFailure.Reason.Truncated -> TRUNCATED
            },
            )
            writeString(output, failure.detail, MAX_DIAGNOSTIC_BYTES)
            output.writeBoolean(failure.cause != null)
            failure.cause?.let { cause ->
                writeString(output, cause.type, MAX_CAUSE_TYPE_BYTES)
                output.writeBoolean(cause.message != null)
                cause.message?.let { writeString(output, it, MAX_DIAGNOSTIC_BYTES) }
            }
        }
    }

    fun read(
        input: DataInputStream,
        paths: List<String>,
    ): PathResolution {
        val tag = input.readUnsignedByte()
        require(tag in NOT_APPLICABLE..FAILED) { "invalid path resolution tag: $tag" }
        val count = input.readInt()
        require(count in 0..MAX_FAILURES) { "invalid path failure count: $count" }
        var priorOperand = -1
        val failures = List(count) {
            val index = input.readInt()
            require(index in 0 until MAX_FAILURES && index > priorOperand) { "invalid path operand order: $index" }
            priorOperand = index
            val reason = when (val code = input.readUnsignedByte()) {
                VM_READ_DENIED -> PathResolutionFailure.Reason.VmReadDenied
                MISSING -> PathResolutionFailure.Reason.Missing
                DIRFD_UNRESOLVED -> PathResolutionFailure.Reason.DirFdUnresolved
                TRUNCATED -> PathResolutionFailure.Reason.Truncated
                else -> throw IllegalArgumentException("invalid path failure reason: $code")
            }
            val detail = readString(input, MAX_DIAGNOSTIC_BYTES)
            val cause = if (readBoolean(input)) {
                PathResolutionCause(readString(input, MAX_CAUSE_TYPE_BYTES), if (readBoolean(input)) readString(input, MAX_DIAGNOSTIC_BYTES) else null)
            } else {
                null
            }
            PathResolutionFailure(index, reason, detail, cause)
        }
        return when (tag) {
            NOT_APPLICABLE -> {
                require(paths.isEmpty() && failures.isEmpty()) { "NotApplicable has path evidence" }
                PathResolution.NotApplicable
            }
            RESOLVED -> {
                require(failures.isEmpty()) { "Resolved has failures" }
                PathResolution.Resolved(paths)
            }
            PARTIAL -> PathResolution.Partial(paths, failures)
            FAILED -> {
                require(paths.isEmpty()) { "Failed has resolved paths" }
                PathResolution.Failed(failures)
            }
            else -> throw IllegalArgumentException("invalid path resolution tag: $tag")
        }
    }

    private fun readBoolean(input: DataInputStream): Boolean =
        when (val value = input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("invalid presence tag: $value")
    }

    private fun writeString(
        output: DataOutputStream,
        value: String,
        limit: Int,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "path diagnostic exceeds $limit bytes" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(
        input: DataInputStream,
        limit: Int,
    ): String {
        val size = input.readInt()
        require(size in 0..limit) { "invalid path diagnostic size: $size" }
        return decodeTraceUtf8(ByteArray(size).also(input::readFully))
    }
}
