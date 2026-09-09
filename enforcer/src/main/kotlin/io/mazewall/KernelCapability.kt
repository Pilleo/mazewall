package io.mazewall

/** Typed result of probing a Linux kernel capability. */
public sealed interface KernelCapability<out T> {
    public data class Available<out T>(
        public val value: T,
    ) : KernelCapability<T>

    public data object Unsupported : KernelCapability<Nothing>

    public data class Rejected(
        public val errno: Int,
    ) : KernelCapability<Nothing>

    public data class ProbeFailed(
        public val cause: Throwable,
    ) : KernelCapability<Nothing>
}

internal fun KernelCapability<Int>.availableValueOrZero(): Int =
    when (this) {
        is KernelCapability.Available -> value
        KernelCapability.Unsupported,
        is KernelCapability.Rejected,
        is KernelCapability.ProbeFailed,
        -> 0
    }
