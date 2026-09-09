package io.mazewall.profiler

import io.mazewall.profiler.engine.PathResolution
import io.mazewall.profiler.engine.PathResolutionFailure

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
 * Completeness as a type, not a free Boolean. [toPolicy]/[BillOfBehavior.toDsl]
 * must switch on this; do not `copy(complete = true)` to skip evidence.
 */
public sealed interface ProfileEvidence {
    public data object Complete : ProfileEvidence

    public data class Incomplete(
        val reasons: List<String>,
    ) : ProfileEvidence
}

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
    /**
     * Operand diagnostics retained independently of the compatibility quality summary.
     * Generated copies retain them unless callers explicitly replace the warnings projection.
     */
    public val pathFailures: List<PathResolutionFailure>
        get() = (warnings as? PathDiagnostics)?.failures.orEmpty()

    private fun withPathEvidence(observations: List<ProfileObservation>): ProfilingCoverage = withPathFailures(observations.flatMap { it.pathResolution.failures })

    private fun withPathFailures(failures: List<PathResolutionFailure>): ProfilingCoverage = copy(warnings = PathDiagnostics(warnings, failures))

    public fun evidence(): ProfileEvidence =
        if (complete) {
            ProfileEvidence.Complete
        } else {
            ProfileEvidence.Incomplete(
                warnings.ifEmpty { listOf("profile is incomplete") },
            )
        }

    internal fun retainStricterPathResolution(prior: ProfilingCoverage): ProfilingCoverage {
        val kept = worsePath(pathResolution, prior.pathResolution)
        val failures = (pathFailures + prior.pathFailures).distinct()
        if (kept == pathResolution) return withPathFailures(failures)
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
            complete = complete &&
                kept != PathResolutionQuality.FAILED &&
                kept != PathResolutionQuality.MIXED &&
                kept != PathResolutionQuality.TRUNCATED,
            warnings = warnings + extra,
        ).withPathFailures(failures)
    }

    public companion object {
        private fun worsePath(
            a: PathResolutionQuality,
            b: PathResolutionQuality,
        ): PathResolutionQuality {
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
        @JvmStatic
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
            val ioUring = inferIoUring(observations, environment)
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
            val uringOpenUninspected =
                observations.any { obs ->
                    obs is ProfileObservation.IoUring &&
                        UringOp.parse(obs.opcode) is UringOp.Open
                }
            if (uringOpenUninspected) {
                warnings.add("io_uring open access mode was not observed")
            }
            val hybridUnproven =
                strategy == ProfileStrategy.HYBRID_NO_URING && environment.ioUringDisabled != true
            if (hybridUnproven) {
                warnings.add("HYBRID_NO_URING without evidence that io_uring was disabled")
            }
            val unparsedConnectCount =
                observations.count { obs ->
                    obs is ProfileObservation.Syscall && obs.name.uppercase() == "CONNECT"
                }
            val hasParsedConnect = observations.any { it is ProfileObservation.Connect }
            if (unparsedConnectCount > 0) {
                if (!hasParsedConnect) {
                    warnings.add("CONNECT was observed without a parsed destination")
                } else {
                    warnings.add("$unparsedConnectCount CONNECT syscall(s) were observed without a parsed destination")
                }
            }
            val unparsedConnect = unparsedConnectCount > 0
            val unmappedSyscalls =
                observations.filter { obs ->
                    obs is ProfileObservation.Syscall &&
                        io.mazewall.core.Syscall
                            .tryParse(obs.name) == null
                }
            if (unmappedSyscalls.isNotEmpty()) {
                val names =
                    unmappedSyscalls
                        .filterIsInstance<ProfileObservation.Syscall>()
                        .map { it.name.uppercase() }
                        .distinct()
                warnings.add("unmapped syscall observations cannot be compiled: ${names.joinToString(",")}")
            }
            val unenforceableUringOps =
                observations.filter { obs ->
                    obs is ProfileObservation.IoUring &&
                        FsEffect.ofUring(UringOp.parse(obs.opcode), obs.paths) is FsEffect.Unenforceable
                }
            if (unenforceableUringOps.isNotEmpty()) {
                val opcodes =
                    unenforceableUringOps
                        .filterIsInstance<ProfileObservation.IoUring>()
                        .map { it.opcode }
                        .distinct()
                warnings.add("unenforceable io_uring opcodes were observed: ${opcodes.joinToString(",")}")
            }
            val complete = drainComplete &&
                droppedEvents == 0 &&
                ioUring != IoUringVisibility.BLIND &&
                !(ioUring == IoUringVisibility.UNSEEN && strategy == ProfileStrategy.EBPF) &&
                pathQuality != PathResolutionQuality.FAILED &&
                pathQuality != PathResolutionQuality.MIXED &&
                pathQuality != PathResolutionQuality.TRUNCATED &&
                !openat2Uninspected &&
                !uringOpenUninspected &&
                !hybridUnproven &&
                !unparsedConnect &&
                unenforceableUringOps.isEmpty() &&
                unmappedSyscalls.isEmpty()
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
            ).withPathEvidence(observations)
        }

        internal fun inferIoUring(
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

        private fun inferPaths(observations: List<ProfileObservation>): PathResolutionQuality {
            var resolved = false
            var failed = false
            var truncated = false
            for (observation in observations) {
                when (observation.pathResolution) {
                    PathResolution.NotApplicable -> Unit
                    is PathResolution.Resolved -> resolved = true
                    is PathResolution.Partial -> resolved = true
                    is PathResolution.Failed -> Unit
                }
                for (failure in observation.pathResolution.failures) {
                    when (failure.reason) {
                        PathResolutionFailure.Reason.VmReadDenied,
                        PathResolutionFailure.Reason.Missing,
                        PathResolutionFailure.Reason.DirFdUnresolved,
                        -> failed = true
                        PathResolutionFailure.Reason.Truncated -> truncated = true
                    }
                }
            }
            return when {
                failed && truncated -> PathResolutionQuality.MIXED
                failed -> PathResolutionQuality.FAILED
                truncated -> PathResolutionQuality.TRUNCATED
                resolved -> PathResolutionQuality.RESOLVED
                else -> PathResolutionQuality.NONE
            }
        }
    }
}

/**
 * Internal owner of typed diagnostics with the existing warnings-list projection.
 * The generated coverage copy method retains this immutable record unless callers replace warnings.
 */
private class PathDiagnostics(
    warnings: List<String>,
    failures: List<PathResolutionFailure>,
) : java.util.AbstractList<String>() {
    val failures: List<PathResolutionFailure> = java.util.List.copyOf(failures)
    private val messages: List<String> =
        java.util.List.copyOf(
            (warnings + failures.map { "path operand ${it.operandIndex}: ${it.reason}; ${it.detail}" }).distinct(),
        )

    override val size: Int get() = messages.size

    override fun get(index: Int): String = messages[index]
}

public class IncompleteProfileException(
    public val coverage: ProfilingCoverage,
    message: String = "Profile is incomplete (${coverage.warnings.joinToString("; ")}). " +
        "Pass allowIncomplete=true only if you will not enforce this policy as a complete contract.",
) : IllegalStateException(message)
