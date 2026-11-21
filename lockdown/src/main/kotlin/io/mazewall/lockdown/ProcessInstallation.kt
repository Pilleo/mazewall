package io.mazewall.lockdown

import java.util.Objects

/**
 * Result describing an active process-wide seccomp installation.
 *
 * [architecture] reports the canonical Linux kernel ABI name (`"amd64"` or `"aarch64"`).
 */
public class ProcessInstallation private constructor(
    @get:JvmName("filterDepth")
    public val filterDepth: Int,
    @get:JvmName("architecture")
    public val architecture: String,
) {
    init {
        require(filterDepth >= 1) { "filterDepth must be >= 1, was $filterDepth" }
        require(architecture.isNotBlank()) { "architecture must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ProcessInstallation && filterDepth == other.filterDepth && architecture == other.architecture)

    override fun hashCode(): Int = Objects.hash(filterDepth, architecture)

    override fun toString(): String = "ProcessInstallation(filterDepth=$filterDepth, architecture=$architecture)"

    internal companion object {
        @JvmSynthetic
        internal fun create(
            filterDepth: Int,
            architecture: String,
        ): ProcessInstallation = ProcessInstallation(filterDepth = filterDepth, architecture = architecture)
    }
}
