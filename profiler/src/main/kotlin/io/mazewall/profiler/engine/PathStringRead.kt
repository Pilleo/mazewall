package io.mazewall.profiler.engine

/** A bounded process-memory string read before path normalization. All fields are heap values. */
public sealed interface PathStringRead {
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
