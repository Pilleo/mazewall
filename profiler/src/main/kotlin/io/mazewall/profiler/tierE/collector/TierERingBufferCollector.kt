package io.mazewall.profiler.tierE.collector

/** Raw Tier E ring-buffer retention; it does not create profile observations. */
internal class TierERingBufferCollector {
    private val events = mutableListOf<AttributedEvent>()

    public var droppedCount: Int = 0
        private set

    public var drainComplete: Boolean = true
        private set

    @Synchronized
    public fun record(
        tid: Long,
        syscallNr: Int,
        contextId: UInt,
        ktimeNs: Long,
    ) {
        events += AttributedEvent(tid, syscallNr, contextId, ktimeNs)
    }

    @Synchronized
    public fun recordDrop() {
        droppedCount++
        drainComplete = false
    }

    @Synchronized
    public fun drain(): List<AttributedEvent> = java.util.List.copyOf(events)

    @Synchronized
    public fun snapshot(): Snapshot = Snapshot(events, droppedCount, drainComplete)

    @Synchronized
    public fun clear() = events.clear()

    internal class Snapshot(
        events: List<AttributedEvent>,
        public val droppedCount: Int,
        public val drainComplete: Boolean,
    ) {
        public val events: List<AttributedEvent> = java.util.List.copyOf(events)

        override fun equals(other: Any?): Boolean = other is Snapshot && events == other.events && droppedCount == other.droppedCount && drainComplete == other.drainComplete

        override fun hashCode(): Int = listOf(events, droppedCount, drainComplete).hashCode()
    }

    internal data class AttributedEvent(
        public val tid: Long,
        public val syscallNr: Int,
        public val contextId: UInt,
        public val ktimeNs: Long,
    )
}
