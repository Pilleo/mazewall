package io.mazewall.profiler.internal.engine
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionCause
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.combinePathResolution

/** A bounded process-memory string read before path normalization. All fields are heap values. */
internal sealed interface PathStringRead {
    public data class Text(
        val value: String,
    ) : PathStringRead

    public data object Missing : PathStringRead

    public data class Denied(
        val cause: PathResolutionCause,
    ) : PathStringRead

    public data class Truncated(
        val preview: String,
        val bytesRead: Int,
    ) : PathStringRead
}
