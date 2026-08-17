package io.mazewall.profiler

/**
 * How the profile was collected. [AUTO] records the concrete choice in [ProfilingCoverage.strategyReason].
 */
public enum class ProfileStrategy {
    USER_NOTIF,
    STRACE,
    EBPF,
    HYBRID_NO_URING,
    AUTO,
}

public enum class IoUringVisibility {
    /** eBPF (or equivalent) saw io_uring submission paths. */
    OBSERVED,
    /** Caller disabled io_uring so USER_NOTIF saw POSIX fallbacks. */
    DISABLED_FOR_HYBRID,
    /** io_uring syscalls were seen; destinations were not. */
    BLIND,
    /** Kernel or OCI profile rejects io_uring_setup. */
    BLOCKED,
    /** No io_uring syscalls in this run; destinations remain unobservable to this collector. */
    UNSEEN,
}

public enum class StackAttribution {
    CAPTURED,
    SKIPPED,
}

public enum class PathResolutionQuality {
    RESOLVED,
    TRUNCATED,
    FAILED,
    MIXED,
    NONE,
}

public data class ProfileEnvironment(
    val kernelRelease: String,
    val ebpfLoad: EbpfLoad,
    val landlockAbi: Int? = null,
    val ioUringDisabled: Boolean? = null,
)

/**
 * Evidence about what a profile can honestly claim. Incomplete results must not
 * become enforcement policies unless the operator overrides.
 */
public data class ProfilingCoverage(
    val strategy: ProfileStrategy,
    val strategyReason: String,
    val processWide: Boolean,
    val ioUring: IoUringVisibility,
    val pathResolution: PathResolutionQuality,
    val stacks: StackAttribution,
    val droppedEvents: Int,
    val drainComplete: Boolean,
    val environment: ProfileEnvironment,
    val complete: Boolean,
    val warnings: List<String> = emptyList(),
) {
    public companion object {
        public fun infer(
            strategy: ProfileStrategy,
            strategyReason: String,
            processWide: Boolean,
            observations: List<ProfileObservation>,
            stacks: StackAttribution,
            droppedEvents: Int,
            drainComplete: Boolean,
            environment: ProfileEnvironment,
        ): ProfilingCoverage {
            val ioUring = inferIoUring(strategy, observations, environment)
            val pathQuality = inferPaths(observations)
            val warnings = mutableListOf<String>()
            if (ioUring == IoUringVisibility.BLIND) {
                warnings.add("io_uring destinations were not observed; do not treat this BoB as an async path profile")
            }

            if (!drainComplete) {
                warnings.add("event drain did not complete")
            }
            if (droppedEvents > 0) {
                warnings.add("dropped $droppedEvents events")
            }
            val complete = drainComplete && droppedEvents == 0 &&
                ioUring != IoUringVisibility.BLIND &&
                pathQuality != PathResolutionQuality.FAILED
            return ProfilingCoverage(
                strategy = strategy,
                strategyReason = strategyReason,
                processWide = processWide,
                ioUring = ioUring,
                pathResolution = pathQuality,
                stacks = stacks,
                droppedEvents = droppedEvents,
                drainComplete = drainComplete,
                environment = environment,
                complete = complete,
                warnings = warnings,
            )
        }

        internal fun inferIoUring(
            strategy: ProfileStrategy,
            observations: List<ProfileObservation>,
            environment: ProfileEnvironment,
        ): IoUringVisibility {
            if (observations.any { it is ProfileObservation.IoUring }) {
                return IoUringVisibility.OBSERVED
            }
            if (strategy == ProfileStrategy.HYBRID_NO_URING || environment.ioUringDisabled == true) {
                val stillUsedUring = hasUringSyscall(observations)
                return if (stillUsedUring) IoUringVisibility.BLIND else IoUringVisibility.DISABLED_FOR_HYBRID
            }
            if (hasUringSyscall(observations)) return IoUringVisibility.BLIND
            return when (strategy) {
                ProfileStrategy.EBPF -> IoUringVisibility.BLOCKED
                else -> IoUringVisibility.UNSEEN
            }
        }

        private fun hasUringSyscall(observations: List<ProfileObservation>): Boolean =
            observations.any { obs ->
                obs is ProfileObservation.Syscall &&
                    obs.name in setOf("IO_URING_SETUP", "IO_URING_ENTER", "IO_URING_REGISTER")
            }

        private fun inferPaths(observations: List<ProfileObservation>): PathResolutionQuality {
            val withPaths = observations.filter { it.paths.isNotEmpty() }
            if (observations.isEmpty()) return PathResolutionQuality.NONE
            if (withPaths.isEmpty()) return PathResolutionQuality.NONE
            return PathResolutionQuality.RESOLVED
        }
    }
}

public class IncompleteProfileException(
    public val coverage: ProfilingCoverage,
    message: String = "Profile is incomplete (${coverage.warnings.joinToString("; ")}). " +
        "Pass allowIncomplete=true only if you will not enforce this policy as a complete contract.",
) : IllegalStateException(message)
