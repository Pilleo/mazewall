package io.mazewall.profiler.api

import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionFailure

/**
 * How the profile was collected. [AUTO] records the concrete choice in [ProfilingCoverage.strategyReason].
 */
public enum class ProfileStrategy {
    USER_NOTIF,
    STRACE,
    EBPF,
    HYBRID_NO_URING,
    AUTO,

    /** Explicit external collector; pinned interpreter/Serial runtime, process-wide selected openat entries only. */
    HOTSPOT_AGENTLESS,
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
 * must switch on this; do not replace [ProfilingCoverage.integrity] with a fabricated complete value.
 */
public sealed interface ProfileEvidence {
    public data object Complete : ProfileEvidence

    public data class Incomplete(
        val reasons: List<String>,
    ) : ProfileEvidence
}

/** Immutable completeness decision and its operator-facing diagnostics. */
internal class ProfileIntegrity internal constructor(
    public val complete: Boolean,
    warnings: List<String> = emptyList(),
) {
    public val warnings: List<String> = if (warnings is PathDiagnostics) warnings else java.util.List.copyOf(warnings)

    internal fun copy(
        complete: Boolean = this.complete,
        warnings: List<String> = this.warnings,
    ): ProfileIntegrity = ProfileIntegrity(complete, warnings)

    override fun equals(other: Any?): Boolean = other is ProfileIntegrity && complete == other.complete && warnings == other.warnings

    override fun hashCode(): Int = 31 * complete.hashCode() + warnings.hashCode()
}

/**
 * Evidence about what a profile can honestly claim. Incomplete results must not
 * become enforcement policies unless the operator overrides.
 *
 * This compatibility type is an intentionally flat public record. Grouping its fields would
 * make the immutable evidence less cohesive, so completeness and diagnostics are grouped in
 * [ProfileIntegrity] while the remaining observation dimensions stay directly addressable.
 */
public class ProfilingCoverage internal constructor(
    public val strategy: ProfileStrategy,
    public val strategyReason: String,
    public val processWide: Boolean,
    public val ioUring: IoUringVisibility,
    public val pathResolution: PathResolutionQuality,
    public val stacks: StackAttribution,
    public val droppedEvents: Int,
    public val drainComplete: Boolean,
    public val environment: ProfileEnvironment,
    internal val integrity: ProfileIntegrity,
) {
    public val complete: Boolean get() = integrity.complete && strategy != ProfileStrategy.HOTSPOT_AGENTLESS
    public val warnings: List<String> get() = integrity.warnings

    internal fun copy(
        strategy: ProfileStrategy = this.strategy,
        strategyReason: String = this.strategyReason,
        processWide: Boolean = this.processWide,
        ioUring: IoUringVisibility = this.ioUring,
        pathResolution: PathResolutionQuality = this.pathResolution,
        stacks: StackAttribution = this.stacks,
        droppedEvents: Int = this.droppedEvents,
        drainComplete: Boolean = this.drainComplete,
        environment: ProfileEnvironment = this.environment,
        integrity: ProfileIntegrity = this.integrity,
    ): ProfilingCoverage =
        ProfilingCoverage(
            strategy,
            strategyReason,
            processWide,
            ioUring,
            pathResolution,
            stacks,
            droppedEvents,
            drainComplete,
            environment,
            integrity,
        )

    override fun equals(other: Any?): Boolean =
        other is ProfilingCoverage &&
            strategy == other.strategy &&
            strategyReason == other.strategyReason &&
            processWide == other.processWide &&
            ioUring == other.ioUring &&
            pathResolution == other.pathResolution &&
            stacks == other.stacks &&
            droppedEvents == other.droppedEvents &&
            drainComplete == other.drainComplete &&
            environment == other.environment &&
            integrity == other.integrity &&
            pathFailures == other.pathFailures

    override fun hashCode(): Int =
        listOf(
            strategy,
            strategyReason,
            processWide,
            ioUring,
            pathResolution,
            stacks,
            droppedEvents,
            drainComplete,
            environment,
            integrity,
            pathFailures,
        ).hashCode()

    /**
     * Operand diagnostics retained independently of the compatibility quality summary.
     * Generated copies retain them unless callers explicitly replace the warnings projection.
     */
    public val pathFailures: List<PathResolutionFailure>
        get() = (warnings as? PathDiagnostics)?.failures.orEmpty()

    private fun withPathEvidence(observations: List<ProfileObservation>): ProfilingCoverage = withPathFailures(observations.flatMap { it.pathResolution.failures })

    internal fun withPathFailures(failures: List<PathResolutionFailure>): ProfilingCoverage = copy(integrity = integrity.copy(warnings = PathDiagnostics(warnings, failures)))

    /** Internal warning augmentation preserves evidence independently of the new list projection. */
    internal fun withWarnings(
        warnings: List<String>,
        complete: Boolean = this.complete,
        drainComplete: Boolean = this.drainComplete,
    ): ProfilingCoverage =
        copy(
        drainComplete = drainComplete,
        integrity = integrity.copy(complete = complete, warnings = PathDiagnostics(warnings, pathFailures)),
    )

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
            integrity = integrity.copy(
                complete = complete && kept !in incompletePathQualities,
                warnings = warnings + extra,
            ),
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

        /** Used when [io.mazewall.profiler.api.BillOfBehavior.toPolicy] is called without coverage. */
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
                integrity = ProfileIntegrity(false, listOf("coverage was not supplied")),
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
            val facts = analyzeObservations(strategy, observations, environment)
            val warnings = inferWarnings(strategy, ioUring, pathQuality, droppedEvents, drainComplete, facts)
            val complete = isComplete(strategy, ioUring, pathQuality, droppedEvents, drainComplete, facts)
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
                integrity = ProfileIntegrity(complete, warnings),
            ).withPathEvidence(observations)
        }

        private fun analyzeObservations(
            strategy: ProfileStrategy,
            observations: List<ProfileObservation>,
            environment: ProfileEnvironment,
        ): InferenceFacts {
            val syscalls = observations.filterIsInstance<ProfileObservation.Syscall>()
            val uring = observations.filterIsInstance<ProfileObservation.IoUring>()
            return InferenceFacts(
                openat2Uninspected = syscalls.any {
                    it.name.uppercase() in setOf("OPEN", "OPENAT", "OPENAT2") &&
                        it.paths.isNotEmpty() &&
                        it.openFlagsEvidence !is OpenFlagsEvidence.Complete
                },
                unknownOpenFlags = syscalls.mapNotNull { it.openFlagsEvidence as? OpenFlagsEvidence.Incomplete }.flatMap { it.unknownSymbols }.distinct(),
                uringOpenUninspected = uring.any { UringOp.parse(it.opcode) is UringOp.Open },
                hybridUnproven = strategy == ProfileStrategy.HYBRID_NO_URING && environment.ioUringDisabled != true,
                unparsedConnectCount = syscalls.count { it.name.uppercase() == "CONNECT" },
                hasParsedConnect = observations.any { it is ProfileObservation.Connect },
                unmappedSyscalls = syscalls
                    .map { it.name.uppercase() }
                    .filter {
                        io.mazewall.platform.syscall.Syscall
                        .tryParse(it) == null
                    }.distinct(),
                unenforceableUringOps = uring.filter { FsEffect.ofUring(UringOp.parse(it.opcode), it.paths) is FsEffect.Unenforceable }.map { it.opcode }.distinct(),
            )
        }

        private fun inferWarnings(
            strategy: ProfileStrategy,
            ioUring: IoUringVisibility,
            pathQuality: PathResolutionQuality,
            droppedEvents: Int,
            drainComplete: Boolean,
            facts: InferenceFacts,
        ): List<String> =
            buildList {
                if (ioUring == IoUringVisibility.BLIND) add("io_uring destinations were not observed; do not treat this BoB as an async path profile")
                if (!drainComplete) add("event drain did not complete")
                if (droppedEvents > 0) add("dropped $droppedEvents events")
                if (ioUring == IoUringVisibility.UNSEEN && strategy == ProfileStrategy.EBPF) add("eBPF did not observe io_uring; destinations are unproven")
                if (pathQuality in incompletePathQualities) add("one or more path-bearing events did not fully resolve a path")
                if (facts.unknownOpenFlags.isNotEmpty()) add("unrecognized open flags: ${facts.unknownOpenFlags.joinToString(",")}")
                if (facts.openat2Uninspected) add("open flags were not observed completely; do not treat paths as a complete write profile")
                if (facts.uringOpenUninspected) add("io_uring open access mode was not observed")
                if (facts.hybridUnproven) add("HYBRID_NO_URING without evidence that io_uring was disabled")
                if (facts.unparsedConnectCount > 0) {
                    add(
                        if (facts.hasParsedConnect) {
                            "${facts.unparsedConnectCount} CONNECT syscall(s) were observed without a parsed destination"
                        } else {
                            "CONNECT was observed without a parsed destination"
                        },
                    )
                }
                if (facts.unmappedSyscalls.isNotEmpty()) {
                    add("unmapped syscall observations cannot be compiled: ${facts.unmappedSyscalls.joinToString(",")}")
                }
                if (facts.unenforceableUringOps.isNotEmpty()) {
                    add("unenforceable io_uring opcodes were observed: ${facts.unenforceableUringOps.joinToString(",")}")
                }
            }

        private fun isComplete(
            strategy: ProfileStrategy,
            ioUring: IoUringVisibility,
            pathQuality: PathResolutionQuality,
            droppedEvents: Int,
            drainComplete: Boolean,
            facts: InferenceFacts,
        ): Boolean =
            listOf(
                drainComplete,
                droppedEvents == 0,
                ioUring != IoUringVisibility.BLIND,
                ioUring != IoUringVisibility.UNSEEN || strategy != ProfileStrategy.EBPF,
                pathQuality !in incompletePathQualities,
                !facts.openat2Uninspected,
                facts.unknownOpenFlags.isEmpty(),
                !facts.uringOpenUninspected,
                !facts.hybridUnproven,
                facts.unparsedConnectCount == 0,
                facts.unenforceableUringOps.isEmpty(),
                facts.unmappedSyscalls.isEmpty(),
            ).all { it }

        private data class InferenceFacts(
            val openat2Uninspected: Boolean,
            val unknownOpenFlags: List<String>,
            val uringOpenUninspected: Boolean,
            val hybridUnproven: Boolean,
            val unparsedConnectCount: Int,
            val hasParsedConnect: Boolean,
            val unmappedSyscalls: List<String>,
            val unenforceableUringOps: List<String>,
        )

        private val incompletePathQualities = setOf(
            PathResolutionQuality.FAILED,
            PathResolutionQuality.MIXED,
            PathResolutionQuality.TRUNCATED,
        )

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
 * Coverage copies retain this immutable record unless callers replace warnings.
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
