package io.mazewall.profiler

import io.mazewall.profiler.collector.CollectorDrain
import io.mazewall.profiler.collector.EbpfCollector
import io.mazewall.profiler.collector.ObservationMerger
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.engine.TraceEvent
import java.nio.file.Files
import java.nio.file.Path

public data class ProfileOptions(
    val strategy: ProfileStrategy = ProfileStrategy.AUTO,
    val captureStacks: Boolean = true,
    val processWide: Boolean = false,
    val ioUringDisabled: Boolean? = null,
    /** Recorded eBPF sidecar log (`kind=uring ...`). Live attach is not implemented. */
    val ebpfEventLog: Path? = null,
)

/**
 * Owned profiling session. The operator API is [profile] with a lambda.
 * [Profiler.profile] is a facade over one session. eBPF live attach fails closed.
 */
public class MazewallProfiler private constructor(
    private val options: ProfileOptions,
    private val environment: ProfileEnvironment,
    private val resolved: ProfileStrategy,
    private val reason: String,
) : AutoCloseable {
    private var closed = false
    private var lastSnapshot: ProfilingResult<Unit>? = null

    public fun <T> profile(block: () -> T): ProfilingResult<T> {
        check(!closed) { "MazewallProfiler is closed" }
        if (resolved == ProfileStrategy.EBPF) {
            return profileEbpfOnly(block)
        }
        val raw = Profiler.profile(options.processWide, options.captureStacks, block)
        return attachCoverage(raw)
    }

    public fun snapshot(): ProfilingResult<Unit> =
        lastSnapshot ?: ProfilingResult(Unit, BillOfBehavior(), emptyMap(), emptyCoverage())

    override fun close() {
        closed = true
    }

    private fun <T> profileEbpfOnly(block: () -> T): ProfilingResult<T> {
        val value = block()
        val drain = drainEbpf(liveIfMissing = true)
        val bob = BobCompiler.compileObservations(drain.observations)
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.EBPF,
            strategyReason = "$reason; recorded log is not contemporaneous with the profiled lambda",
            processWide = options.processWide,
            observations = drain.observations,
            stacks = StackAttribution.SKIPPED,
            droppedEvents = drain.droppedEvents,
            drainComplete = false,
            environment = environment,
        )
        val result = ProfilingResult(value, bob, emptyMap(), coverage, drain.observations)
        lastSnapshot = ProfilingResult(Unit, bob, emptyMap(), coverage, drain.observations)
        return result
    }

    private fun drainEbpf(liveIfMissing: Boolean): CollectorDrain {
        val collector = EbpfCollector(
            load = environment.ebpfLoad,
            recordedLog = options.ebpfEventLog,
            liveAttach = liveIfMissing && options.ebpfEventLog == null,
        )
        collector.start()
        return collector.use { it.drain() }
    }

    private fun <T> attachCoverage(raw: ProfilingResult<T>): ProfilingResult<T> {
        val fromUser =
            if (raw.observations.isNotEmpty()) {
                raw.observations
            } else {
                inferObservationsFromBob(raw.behavior, raw.stackProfile.keys)
            }
        val drains = mutableListOf(
            CollectorDrain(
                observations = fromUser,
                droppedEvents = raw.coverage.droppedEvents,
                drainComplete = raw.coverage.drainComplete,
                ioUring = raw.coverage.ioUring,
            ),
        )
        var recordedObservations = emptyList<ProfileObservation>()
        if (options.ebpfEventLog != null) {
            val recorded = drainEbpf(liveIfMissing = false)
            recordedObservations = recorded.observations
            drains.add(recorded.copy(drainComplete = false))
        }
        val merged = ObservationMerger.merge(drains)
        val extraBob = BobCompiler.compileObservations(
            recordedObservations.filter { it.source == ObservationSource.EBPF },
        )
        val behavior = raw.behavior + extraBob
        val liveObservations = fromUser
        val inferred = ProfilingCoverage.infer(
            strategy = if (options.strategy == ProfileStrategy.HYBRID_NO_URING) {
                ProfileStrategy.HYBRID_NO_URING
            } else {
                ProfileStrategy.USER_NOTIF
            },
            strategyReason = reason,
            processWide = options.processWide,
            observations = liveObservations,
            stacks = if (options.captureStacks) StackAttribution.CAPTURED else StackAttribution.SKIPPED,
            droppedEvents = merged.droppedEvents,
            drainComplete = if (options.ebpfEventLog != null) false else merged.drainComplete,
            environment = environment,
        )
        val coverage =
            inferred
                .retainStricterPathResolution(raw.coverage)
                .let { kept ->
                    if (options.ebpfEventLog == null) {
                        kept
                    } else {
                        kept.copy(
                            complete = false,
                            drainComplete = false,
                            warnings = kept.warnings +
                                "recorded eBPF log is not contemporaneous with this USER_NOTIF run",
                        )
                    }
                }
        val result = raw.copy(
            behavior = behavior,
            coverage = coverage,
            observations = liveObservations + recordedObservations,
        )
        lastSnapshot = ProfilingResult(
            Unit,
            result.behavior,
            result.stackProfile,
            coverage,
            result.observations,
        )
        return result
    }

    private fun emptyCoverage(): ProfilingCoverage =
        ProfilingCoverage.infer(
            strategy = resolved,
            strategyReason = reason,
            processWide = options.processWide,
            observations = emptyList(),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = false,
            environment = environment,
        )

    public companion object {
        public fun open(options: ProfileOptions = ProfileOptions()): MazewallProfiler {
            if (options.strategy == ProfileStrategy.STRACE) {
                throw IllegalArgumentException(
                    "STRACE is not an operator session strategy. Use profile { } (USER_NOTIF). " +
                        "Descendant strace is an internal floor/lab probe, not a workload API.",
                )
            }
            val env = ProfileEnvironment(
                kernelRelease = runCatching { Files.readString(Path.of("/proc/sys/kernel/osrelease")).trim() }
                    .getOrElse { System.getProperty("os.version") ?: "unknown" },
                ebpfLoad = EbpfCapability.probe(),
                ioUringDisabled = options.ioUringDisabled,
            )
            val (resolved, reason) = resolve(options.strategy, env.ebpfLoad)
            return MazewallProfiler(options, env, resolved, reason)
        }

        internal fun resolve(strategy: ProfileStrategy, load: EbpfLoad): Pair<ProfileStrategy, String> {
            return when (strategy) {
                ProfileStrategy.USER_NOTIF ->
                    ProfileStrategy.USER_NOTIF to "explicit USER_NOTIF"
                ProfileStrategy.STRACE ->
                    ProfileStrategy.STRACE to "explicit STRACE"
                ProfileStrategy.HYBRID_NO_URING ->
                    ProfileStrategy.HYBRID_NO_URING to "USER_NOTIF with io_uring disabled by the operator"
                ProfileStrategy.EBPF ->
                    when (load) {
                        is EbpfLoad.Available ->
                            ProfileStrategy.EBPF to "CAP_BPF in init ns; live attach not implemented, recorded log supported"
                        is EbpfLoad.UserNamespaceRoot ->
                            ProfileStrategy.EBPF to "uid 0 in a user namespace cannot load tracing eBPF"
                        is EbpfLoad.Denied ->
                            ProfileStrategy.EBPF to "eBPF unavailable: ${load.reason}"
                    }
                ProfileStrategy.AUTO ->
                    ProfileStrategy.USER_NOTIF to when (load) {
                        is EbpfLoad.Available ->
                            "AUTO: eBPF capabilities present but live attach is not implemented; USER_NOTIF"
                        is EbpfLoad.UserNamespaceRoot ->
                            "AUTO: user-namespace root cannot load eBPF; USER_NOTIF"
                        is EbpfLoad.Denied ->
                            "AUTO: eBPF unavailable (${load.reason}); USER_NOTIF"
                    }
            }
        }

        internal fun inferObservationsFromBob(
            behavior: BillOfBehavior,
            events: Set<TraceEvent>,
        ): List<ProfileObservation> {
            if (events.isNotEmpty()) {
                return events.map { ProfileObservation.fromTraceEvent(it) }
            }
            return bobToSynthetic(behavior)
        }

        private fun bobToSynthetic(behavior: BillOfBehavior): List<ProfileObservation> {
            val corr = ObservationCorrelation(0, io.mazewall.core.Tid(0))
            val out = mutableListOf<ProfileObservation>()
            for (sc in behavior.syscalls) {
                out.add(ProfileObservation.Syscall(corr, ObservationSource.USER_NOTIF, sc.name))
            }
            for (op in behavior.ioUringOps) {
                out.add(ProfileObservation.IoUring(corr, ObservationSource.EBPF, op))
            }
            for (c in behavior.connects) {
                out.add(ProfileObservation.Connect(corr, ObservationSource.USER_NOTIF, c))
            }
            return out
        }
    }
}
