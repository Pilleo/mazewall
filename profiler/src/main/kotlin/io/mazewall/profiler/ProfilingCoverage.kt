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
    internal fun retainStricterPathResolution(prior: ProfilingCoverage): ProfilingCoverage {
        val kept = worsePath(pathResolution, prior.pathResolution)
        if (kept == pathResolution) return this
        val extra =
            if (kept == PathResolutionQuality.FAILED ||
                kept == PathResolutionQuality.MIXED ||
                kept == PathResolutionQuality.TRUNCATED
            ) {
                listOf("prior USER_NOTIF path resolution was $kept")
            } else {
                emptyList()
            }
        return copy(
            pathResolution = kept,
            complete = complete && kept != PathResolutionQuality.FAILED &&
                kept != PathResolutionQuality.MIXED &&
                kept != PathResolutionQuality.TRUNCATED,
            warnings = warnings + extra,
        )
    }

    public companion object {
        private fun worsePath(a: PathResolutionQuality, b: PathResolutionQuality): PathResolutionQuality {
            val order =
                listOf(
                    PathResolutionQuality.NONE,
                    PathResolutionQuality.RESOLVED,
                    PathResolutionQuality.TRUNCATED,
                    PathResolutionQuality.MIXED,
                    PathResolutionQuality.FAILED,
                )
            return if (order.indexOf(b) > order.indexOf(a)) b else a
        }
        /** Used when [io.mazewall.profiler.BillOfBehavior.toPolicy] is called without coverage. */
        public fun absent(): ProfilingCoverage =
            ProfilingCoverage(
                strategy = ProfileStrategy.USER_NOTIF,
                strategyReason = "toPolicy() invoked without ProfilingCoverage",
                processWide = false,
                ioUring = IoUringVisibility.UNSEEN,
                pathResolution = PathResolutionQuality.NONE,
                stacks = StackAttribution.SKIPPED,
                droppedEvents = 0,
                drainComplete = false,
                environment = ProfileEnvironment("unknown", EbpfLoad.Denied("unprobed")),
                complete = false,
                warnings = listOf("coverage was not supplied"),
            )

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
            if (ioUring == IoUringVisibility.UNSEEN && strategy == ProfileStrategy.EBPF) {
                warnings.add("eBPF did not observe io_uring; destinations are unproven")
            }
            if (pathQuality == PathResolutionQuality.FAILED ||
                pathQuality == PathResolutionQuality.MIXED ||
                pathQuality == PathResolutionQuality.TRUNCATED
            ) {
                warnings.add("one or more path-bearing events did not fully resolve a path")
            }
            val openat2Uninspected =
                observations.any { obs ->
                    obs is ProfileObservation.Syscall &&
                        obs.name.uppercase() == "OPENAT2" &&
                        obs.paths.isNotEmpty() &&
                        obs.openFlags == null
                }
            if (openat2Uninspected) {
                warnings.add("OPENAT2 flags were not observed; do not treat paths as a complete write profile")
            }
            val complete = drainComplete && droppedEvents == 0 &&
                ioUring != IoUringVisibility.BLIND &&
                !(ioUring == IoUringVisibility.UNSEEN && strategy == ProfileStrategy.EBPF) &&
                pathQuality != PathResolutionQuality.FAILED &&
                pathQuality != PathResolutionQuality.MIXED &&
                pathQuality != PathResolutionQuality.TRUNCATED &&
                !openat2Uninspected
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
            if (environment.ioUringDisabled == true) {
                val stillUsedUring = hasUringSyscall(observations)
                return if (stillUsedUring) IoUringVisibility.BLIND else IoUringVisibility.DISABLED_FOR_HYBRID
            }
            if (hasUringSyscall(observations)) return IoUringVisibility.BLIND
            return IoUringVisibility.UNSEEN
        }

        private fun hasUringSyscall(observations: List<ProfileObservation>): Boolean =
            observations.any { obs ->
                obs is ProfileObservation.Syscall &&
                    obs.name.uppercase() in setOf("IO_URING_SETUP", "IO_URING_ENTER", "IO_URING_REGISTER")
            }

        private val pathBearingNames =
            setOf(
                "OPEN", "OPENAT", "OPENAT2", "EXECVE", "EXECVEAT",
                "UNLINK", "UNLINKAT", "RENAME", "RENAMEAT", "RENAMEAT2",
                "MKDIR", "MKDIRAT", "RMDIR", "LINK", "LINKAT",
                "SYMLINK", "SYMLINKAT", "CHDIR", "TRUNCATE",
                "ACCESS", "FACCESSAT", "FACCESSAT2", "STAT", "NEWFSTATAT",
                "CREAT", "CHMOD", "FCHMODAT", "CHOWN", "LCHOWN", "FCHOWNAT",
            )

        private fun isUringPathBearing(opcode: String): Boolean {
            val op = opcode.uppercase()
            return op.contains("OPEN") ||
                op.contains("UNLINK") ||
                op.contains("RENAME") ||
                op.contains("WRITE") ||
                op.contains("MKDIR") ||
                op.contains("RMDIR") ||
                op.contains("TRUNCATE")
        }

        private const val TRUNCATION_THRESHOLD = 4096

        private fun inferPaths(observations: List<ProfileObservation>): PathResolutionQuality {
            if (observations.isEmpty()) return PathResolutionQuality.NONE
            val pathBearing =
                observations.filter { obs ->
                    when (obs) {
                        is ProfileObservation.Syscall -> obs.name.uppercase() in pathBearingNames
                        is ProfileObservation.IoUring -> isUringPathBearing(obs.opcode)
                        is ProfileObservation.Connect -> false
                    }
                }
            if (pathBearing.isEmpty()) return PathResolutionQuality.NONE
            val truncated = pathBearing.any { obs -> obs.paths.any { it.length >= TRUNCATION_THRESHOLD } }
            val resolved = pathBearing.count { it.paths.isNotEmpty() }
            val failed = pathBearing.size - resolved
            return when {
                failed == 0 && !truncated -> PathResolutionQuality.RESOLVED
                failed == pathBearing.size && !truncated -> PathResolutionQuality.FAILED
                failed == 0 && truncated -> PathResolutionQuality.TRUNCATED
                else -> PathResolutionQuality.MIXED
            }
        }
    }
}

public class IncompleteProfileException(
    public val coverage: ProfilingCoverage,
    message: String = "Profile is incomplete (${coverage.warnings.joinToString("; ")}). " +
        "Pass allowIncomplete=true only if you will not enforce this policy as a complete contract.",
) : IllegalStateException(message)
