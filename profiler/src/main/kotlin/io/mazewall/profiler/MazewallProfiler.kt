package io.mazewall.profiler

import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.strace.StraceProfiler
import java.nio.file.Files
import java.nio.file.Path

public data class ProfileOptions(
    val strategy: ProfileStrategy = ProfileStrategy.AUTO,
    val captureStacks: Boolean = true,
    val processWide: Boolean = false,
    val ioUringDisabled: Boolean? = null,
)

/**
 * Owned profiling session. [Profiler.profile] is a facade over a single session.
 * eBPF is not implemented yet: [ProfileStrategy.EBPF] fails closed.
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
            val coverage = failEbpf()
            throw IncompleteProfileException(coverage, "eBPF collector is not implemented; refuse silent fallback")
        }
        if (resolved == ProfileStrategy.STRACE) {
            throw IllegalArgumentException("STRACE requires profile(workloadClass); a lambda cannot be spawned under strace")
        }
        val raw = Profiler.profile(options.processWide, options.captureStacks, block)
        return attachCoverage(raw)
    }

    public fun <W : TraceableWorkload> profile(workloadClass: Class<W>): ProfilingResult<Unit> {
        check(!closed) { "MazewallProfiler is closed" }
        if (resolved != ProfileStrategy.STRACE && options.strategy != ProfileStrategy.STRACE) {
            throw IllegalArgumentException("Class workloads are collected via STRACE")
        }
        val bob = StraceProfiler.profile(workloadClass)
        val observations = bobToSynthetic(bob)
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.STRACE,
            strategyReason = reason,
            processWide = true,
            observations = observations,
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = environment,
        )
        val result = ProfilingResult(Unit, bob, emptyMap(), coverage)
        lastSnapshot = result
        return result
    }

    public fun snapshot(): ProfilingResult<Unit> =
        lastSnapshot ?: ProfilingResult(Unit, BillOfBehavior(), emptyMap(), emptyCoverage())

    override fun close() {
        closed = true
    }

    private fun <T> attachCoverage(raw: ProfilingResult<T>): ProfilingResult<T> {
        val observations = inferObservationsFromBob(raw.behavior, raw.stackProfile.keys)
        val coverage = ProfilingCoverage.infer(
            strategy = if (options.strategy == ProfileStrategy.HYBRID_NO_URING) {
                ProfileStrategy.HYBRID_NO_URING
            } else {
                ProfileStrategy.USER_NOTIF
            },
            strategyReason = reason,
            processWide = options.processWide,
            observations = observations,
            stacks = if (options.captureStacks) StackAttribution.CAPTURED else StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = environment,
        )
        val result = raw.copy(coverage = coverage)
        lastSnapshot = ProfilingResult(Unit, result.behavior, result.stackProfile, coverage)
        return result
    }

    private fun failEbpf(): ProfilingCoverage =
        ProfilingCoverage.infer(
            strategy = ProfileStrategy.EBPF,
            strategyReason = reason,
            processWide = options.processWide,
            observations = emptyList(),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = environment,
        )

    private fun emptyCoverage(): ProfilingCoverage =
        ProfilingCoverage.infer(
            strategy = resolved,
            strategyReason = reason,
            processWide = options.processWide,
            observations = emptyList(),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = environment,
        )

    public companion object {
        public fun open(options: ProfileOptions = ProfileOptions()): MazewallProfiler {
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
                            ProfileStrategy.EBPF to "CAP_BPF in init ns; collector not implemented"
                        is EbpfLoad.UserNamespaceRoot ->
                            ProfileStrategy.EBPF to "uid 0 in a user namespace cannot load tracing eBPF"
                        is EbpfLoad.Denied ->
                            ProfileStrategy.EBPF to "eBPF unavailable: ${load.reason}"
                    }
                ProfileStrategy.AUTO ->
                    ProfileStrategy.USER_NOTIF to when (load) {
                        is EbpfLoad.Available ->
                            "AUTO: eBPF capabilities present but collector not implemented; USER_NOTIF"
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
