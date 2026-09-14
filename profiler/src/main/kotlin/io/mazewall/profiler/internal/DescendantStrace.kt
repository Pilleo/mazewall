package io.mazewall.profiler.internal

import io.mazewall.profiler.api.BillOfBehavior
import io.mazewall.profiler.api.EbpfCapability
import io.mazewall.profiler.api.ProfileEnvironment
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.api.ProfilingCoverage
import io.mazewall.profiler.api.ProfilingResult
import io.mazewall.profiler.api.StackAttribution
import io.mazewall.profiler.api.TraceableWorkload
import io.mazewall.profiler.collector.StraceCollector
import io.mazewall.profiler.compiler.BobCompiler

/**
 * Lab/floor path: descendant `strace -f` of a fresh JVM.
 * Not operator API. Application profiling uses [io.mazewall.profiler.api.MazewallProfiler.profile].
 */
internal object DescendantStrace {
    fun profile(workloadClass: Class<out TraceableWorkload>): ProfilingResult<Unit> {
        val collector = StraceCollector(workloadClass = workloadClass)
        collector.start()
        val drain = collector.use { it.drain() }
        val bob = BobCompiler.compileObservations(drain.observations)
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.STRACE,
            strategyReason = "internal descendant strace (JVM floor / USER_NOTIF-unavailable lab)",
            processWide = true,
            observations = drain.observations,
            stacks = StackAttribution.SKIPPED,
            droppedEvents = drain.droppedEvents,
            drainComplete = drain.drainComplete,
            environment = ProfileEnvironment(
                kernelRelease = System.getProperty("os.version") ?: "unknown",
                ebpfLoad = EbpfCapability.probe(),
            ),
        )
        return ProfilingResult(Unit, bob, emptyMap(), coverage)
    }

    fun compile(workloadClass: Class<out TraceableWorkload>): BillOfBehavior = profile(workloadClass).behavior
}
