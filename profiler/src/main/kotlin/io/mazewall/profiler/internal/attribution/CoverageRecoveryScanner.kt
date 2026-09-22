package io.mazewall.profiler.internal.attribution

/**
 * Caller-driven retained-edge scanner. No threads: the session owner calls
 * [poll] alongside ring draining and [scanFinal] after proving producer
 * quiescence. All state stays behind the session's serialized boundary.
 */
internal class CoverageRecoveryScanner(
    private val reader: RecoveryMapReader,
    private val maxBatchEntries: Int = CoverageRecoveryMode.MAX_SCAN_BATCH,
    private val sweepIntervalNanos: Long = CoverageRecoveryMode.DEFAULT_SCAN_INTERVAL_MILLIS * 1_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(maxBatchEntries in 1..CoverageRecoveryMode.MAX_SCAN_BATCH) {
            "scan batch must be 1..${CoverageRecoveryMode.MAX_SCAN_BATCH}"
        }
        require(sweepIntervalNanos > 0) { "sweep interval must be positive" }
    }

    data class Sweep(
        val entries: List<RecoveryMapEntry>,
        val latencyNanos: Long,
        val overdue: Boolean,
        val sweepComplete: Boolean,
    )

    private var sweepStartNanos: Long? = null
    private var cursor: ByteArray? = null
    private var sweepEntries = 0
    var overdueSweeps: Long = 0
        private set
    var completedSweeps: Long = 0
        private set
    var lastLatencyNanos: Long = 0
        private set

    /**
     * Continues the live sweep when one is in progress, or starts a new sweep
     * when the interval has elapsed. Live sweeps are eventually consistent:
     * concurrent insertion may surface across batches or a later sweep.
     */
    fun poll(): Sweep {
        val now = nanoTime()
        val start = sweepStartNanos
        if (start == null) {
            sweepStartNanos = now
            return readBatch(startedAt = now, overdue = false)
        }
        if (cursor == null) {
            // Previous sweep finished; start a new one only when due.
            if (now - start < sweepIntervalNanos) {
                return Sweep(emptyList(), 0, overdue = false, sweepComplete = true)
            }
            if (now - start > 2 * sweepIntervalNanos) overdueSweeps++
            sweepStartNanos = now
            sweepEntries = 0
            return readBatch(startedAt = now, overdue = false)
        }
        return readBatch(startedAt = start, overdue = false)
    }

    /**
     * Runs the complete final scan. Call only after producer quiescence:
     * no new entries may appear, so bounded iteration always terminates.
     */
    fun scanFinal(maxCapacity: Int): Sweep {
        val startedAt = nanoTime()
        val entries = ArrayList<RecoveryMapEntry>()
        var active: ByteArray? = null
        // No eviction or deletion exists, so one pass over a quiescent map plus
        // a confirming empty page always terminates within this bound.
        val maxBatches = maxCapacity / maxBatchEntries + 2
        var batches = 0
        while (batches < maxBatches) {
            val page = reader.scanBatch(active, maxBatchEntries)
            entries.addAll(page.entries)
            batches++
            if (page.complete) {
                active = null
                break
            }
            active = requireNotNull(page.nextCursor) { "incomplete scan page must carry a cursor" }
        }
        check(active == null) { "final recovery scan did not terminate within its bound" }
        sweepStartNanos = null
        cursor = null
        completedSweeps++
        lastLatencyNanos = nanoTime() - startedAt
        return Sweep(entries, lastLatencyNanos, overdue = false, sweepComplete = true)
    }

    private fun readBatch(
        startedAt: Long,
        overdue: Boolean,
    ): Sweep {
        val page = reader.scanBatch(cursor, maxBatchEntries)
        sweepEntries += page.entries.size
        val latency = nanoTime() - startedAt
        lastLatencyNanos = latency
        return if (page.complete) {
            cursor = null
            completedSweeps++
            Sweep(page.entries, latency, overdue, sweepComplete = true)
        } else {
            cursor = requireNotNull(page.nextCursor) { "incomplete scan page must carry a cursor" }
            Sweep(page.entries, latency, overdue, sweepComplete = false)
        }
    }
}
