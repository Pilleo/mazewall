package io.mazewall.profiler.api

import io.mazewall.profiler.internal.hotspot.HotSpotCollectorProcess
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorSnapshot
import io.mazewall.profiler.internal.hotspot.HotSpotCoverageAdapter
import io.mazewall.profiler.internal.hotspot.HotSpotSessionFailure
import io.mazewall.profiler.internal.hotspot.HotSpotSessionState
import java.util.concurrent.atomic.AtomicReference
import io.mazewall.profiler.internal.hotspot.HotSpotProfilingSession as OwnedSession

/** Independently owned process-wide observation window. Keep the target alive through [finish]. */
public class HotSpotProfilingSession internal constructor(
    private val owner: OwnedSession,
) : AutoCloseable {
    private val completed = AtomicReference<ProfilingResult<Unit>?>()
    public val diagnostics: String get() = owner.snapshot().stderr
    public val discardedDiagnosticBytes: Long get() = owner.snapshot().discardedStderrBytes
    public val pendingOwnership: Boolean get() = owner.snapshot().pendingOwnership

    public fun snapshot(): ProfilingResult<Unit> = completed.get() ?: convert(owner.snapshot())

    public fun finish(): ProfilingResult<Unit> =
        try {
        val result = convert(owner.finish())
        completed.compareAndSet(null, result)
        checkNotNull(completed.get())
    } catch (failure: HotSpotSessionFailure) {
        throw HotSpotProfilingException.from(failure)
    }

    override fun close() {
        try {
            owner.close()
        } catch (failure: HotSpotSessionFailure) {
            throw HotSpotProfilingException.from(failure)
        }
    }

    internal companion object {
        fun convert(snapshot: HotSpotCollectorSnapshot): ProfilingResult<Unit> {
            val evidence = checkNotNull(snapshot.evidence) { "Collector did not establish session identity" }
            return HotSpotCoverageAdapter.toLiveResult(evidence, !snapshot.pendingOwnership && snapshot.state is HotSpotSessionState.Completed)
        }
    }
}

/** Failed admission/collection/cleanup preserves partial evidence and an inspectable real cleanup owner. */
public class HotSpotProfilingException internal constructor(
    message: String,
    cause: Throwable,
    public val partialResult: ProfilingResult<Unit>?,
    private val owner: HotSpotCollectorProcess?,
    nativeErrnos: List<UInt>,
    public val diagnostics: String,
    public val discardedDiagnosticBytes: Long,
) : IllegalStateException(message, cause) {
    public val nativeErrnos: List<UInt> = java.util.List.copyOf(nativeErrnos)
    public val pendingOwnership: Boolean get() = owner?.snapshot()?.pendingOwnership == true

    /** Explicit retry of resource retirement; the original failed collection never becomes policy-complete. */
    public fun retryCleanup() {
        try {
            owner?.close()
        } catch (failure: HotSpotSessionFailure) {
            if (failure.owner.snapshot().pendingOwnership) throw from(failure)
        }
    }

    internal companion object {
        fun from(failure: HotSpotSessionFailure): HotSpotProfilingException {
            val causes = (failure.snapshot.state as? HotSpotSessionState.Failed)?.failures.orEmpty()
            val result = failure.snapshot.evidence?.let { HotSpotCoverageAdapter.toLiveResult(it, false) }
            return HotSpotProfilingException(
                "HotSpot profiling failed",
                failure,
                result,
                failure.owner,
                causes.filterIsInstance<io.mazewall.profiler.internal.hotspot.HotSpotCollectorFailure>().mapNotNull { it.nativeErrno },
                failure.snapshot.stderr,
                failure.snapshot.discardedStderrBytes,
            )
        }
    }
}
