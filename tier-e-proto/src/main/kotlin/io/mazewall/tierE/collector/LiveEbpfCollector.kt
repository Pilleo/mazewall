package io.mazewall.tierE.collector



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
    private val events = java.util.concurrent.CopyOnWriteArrayList<AttributedEvent>()
    @Volatile public var droppedCount: Int = 0; private set
    @Volatile public var drainComplete: Boolean = true; private set

    /** Records an event from the BPF ring buffer. Called by the consumer thread. */
    public fun record(tid: Long, syscallNr: Int, contextId: UInt, ktimeNs: Long) {
        events.add(AttributedEvent(tid, syscallNr, contextId, ktimeNs))
    }

    public fun recordDrop() {
        droppedCount++
        drainComplete = false
    }

    /** Returns a snapshot of all collected events. */
    public fun drain(): List<AttributedEvent> = events.toList()

    /** Clears accumulated events after drain. */
    public fun clear() { events.clear() }

    public data class AttributedEvent(
        public val tid: Long,
        public val syscallNr: Int,
        public val contextId: UInt,
        public val ktimeNs: Long,
    )
}
