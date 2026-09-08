package io.mazewall.profiler.tierE.collector

/**
 * Tier E attributed syscall event collector.
 *
 * Self-contained: no dependency on :profiler module. Events are recorded
 * as [AttributedEvent] instances and drained by the caller (WP-09 will
 * translate these into ProfileObservation objects for :profiler).
 *
 * Drop accounting: [droppedCount] tracks ring-buffer losses.
 */
public class LiveEbpfCollector {
    private val events = mutableListOf<AttributedEvent>()

    public var droppedCount: Int = 0
    private set

    public var drainComplete: Boolean = true
    private set

    /** Records an event from the BPF ring buffer. Called by the consumer thread. */
    @Synchronized public fun record(
        tid: Long,
        syscallNr: Int,
        contextId: UInt,
        ktimeNs: Long,
    ) {
        events.add(AttributedEvent(tid, syscallNr, contextId, ktimeNs))
    }

    @Synchronized public fun recordDrop() {
        droppedCount++
        drainComplete = false
    }

    /** Returns a snapshot of all collected events. */
    @Synchronized public fun drain(): List<AttributedEvent> = events.toList()

    /** Atomically captures events and the loss marker from one collection epoch. */
    @Synchronized public fun snapshot(): Snapshot = Snapshot(events.toList(), droppedCount, drainComplete)

    /** Clears accumulated events after drain. */
    @Synchronized public fun clear() {
        events.clear()
    }

    public data class Snapshot(
        public val events: List<AttributedEvent>,
        public val droppedCount: Int,
        public val drainComplete: Boolean,
    )

    public data class AttributedEvent(
        public val tid: Long,
        public val syscallNr: Int,
        public val contextId: UInt,
        public val ktimeNs: Long,
    )
}
