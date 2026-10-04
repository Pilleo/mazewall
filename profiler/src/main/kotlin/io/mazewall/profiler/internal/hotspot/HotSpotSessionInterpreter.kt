package io.mazewall.profiler.internal.hotspot

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

private const val STDERR_BYTES = 65536

/** Synchronized domain store: commits only pure machine decisions; performs no process/stream I/O. */
internal class HotSpotSessionInterpreter(
    expectedPid: Int,
    expectedStartTicks: ULong,
    limits: HotSpotReplayLimits,
    timing: HotSpotCollectorTiming,
) {
    private val machine = HotSpotSessionMachine(timing.startupNanos, timing.sessionNanos, timing.stopNanos)
    private val current = AtomicReference<HotSpotSessionState>(HotSpotSessionState.Created)
    private val protocol = HotSpotProtocol(expectedPid, expectedStartTicks)
    private val accumulator = HotSpotEvidenceAssembler(limits).begin()
    private val stderr = ByteArrayOutputStream()
    private var discarded = 0L
    private var session: String? = null
    private var finalEvidence: StructuredProfileEvidence? = null
    val state: HotSpotSessionState get() = current.get()

    @Synchronized
    fun event(event: HotSpotSessionEvent): List<HotSpotSessionEffect> {
        val transition = machine.evaluate(current.get(), event, System.nanoTime())
        current.set(transition.state)
        return transition.effects
    }

    @Synchronized
    fun record(row: HotSpotWireRecord): List<HotSpotSessionEffect> {
        checkHotSpotProtocol(session == null || session == row.sessionId, "collector session changed")
        val control = protocol.controlEvent(row)
        return if (control != null) {
            if (control is HotSpotSessionEvent.Started) {
                session = row.sessionId
                accumulator.accept(row.copy(kind = "collector_attached"))
            }
            val effects = event(control)
            if (control is HotSpotSessionEvent.Ready && state is HotSpotSessionState.Ready) effects + event(HotSpotSessionEvent.BeginWork) else effects
        } else {
            checkHotSpotProtocol(session != null, "payload preceded session start")
            accumulator.accept(row)
            if (row.kind == "session_summary") {
                val proof = accumulator.finish()
                finalEvidence = proof
                event(protocol.summaryEvent(proof))
            } else {
                emptyList()
            }
        }
    }

    @Synchronized
    fun diagnostics(
        bytes: ByteArray,
        count: Int,
    ) {
        val retained = count.coerceAtMost(STDERR_BYTES - stderr.size())
        stderr.write(bytes, 0, retained)
        discarded += count - retained
    }

    @Synchronized
    fun snapshot(pendingOwnership: Boolean): HotSpotCollectorSnapshot =
        HotSpotCollectorSnapshot(
        state,
        if (state is HotSpotSessionState.Completed) {
            finalEvidence
        } else if (accumulator.hasSession) {
            accumulator.snapshot()
        } else {
            null
        },
        stderr.toString(Charsets.UTF_8),
            discarded,
            pendingOwnership,
    )
}
