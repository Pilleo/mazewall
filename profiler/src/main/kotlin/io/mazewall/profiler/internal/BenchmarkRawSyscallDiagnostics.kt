package io.mazewall.profiler.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Benchmark-only, bounded observation of raw fields before [TraceEvent] normalizes them.
 *
 * This is deliberately not part of [io.mazewall.profiler.api.ProfilingResult]: raw syscall
 * arguments can include tracee addresses and are not application-facing profiler evidence.
 */
internal object BenchmarkRawSyscallDiagnostics {
    private const val MAX_RECORDS = 4_096
    private val active = AtomicReference<Capture?>(null)

    fun begin(accepts: (String) -> Boolean = { true }): Capture {
        val capture = Capture(accepts)
        check(active.compareAndSet(null, capture)) { "a benchmark raw diagnostic capture is already active" }
        return capture
    }

    internal fun activeCapture(): Capture? = active.get()

    internal fun record(
        tid: Int,
        syscallName: String,
        arguments: List<Long>,
    ): Record? = active.get()?.record(tid, syscallName, arguments)

    internal class Capture internal constructor(
        private val accepts: (String) -> Boolean,
    ) {
        private val records = CopyOnWriteArrayList<Record>()
        private val operationStarts = ConcurrentHashMap<Long, Long>()
        private val operationReturns = ConcurrentHashMap<Long, Long>()

        internal fun record(
            tid: Int,
            syscallName: String,
            arguments: List<Long>,
        ): Record? {
            if (!accepts(syscallName) || records.size >= MAX_RECORDS) return null
            return Record(tid, syscallName, arguments.take(6), System.nanoTime()).also(records::add)
        }

        /** Benchmark-only timestamps keyed by the stable first raw argument of the shared oracle. */
        fun operationStarted(key: Long) {
            operationStarts[key] = System.nanoTime()
        }

        fun operationReturned(key: Long) {
            operationReturns[key] = System.nanoTime()
        }

        internal fun listenerProcessed(
            record: Record?,
            stackCaptureNanos: Long,
            queueNanos: Long,
        ) {
            record ?: return
            record.stackCaptureNanos = stackCaptureNanos
            record.queueNanos = queueNanos
        }

        internal fun listenerAcknowledged(record: Record?) {
            record?.acknowledgedNanos = System.nanoTime()
        }

        fun timing(record: Record): Timing {
            val key = record.arguments.firstOrNull()
            return Timing(
                operationStartedNanos = key?.let(operationStarts::get) ?: 0L,
                listenerReceivedNanos = record.receivedNanos,
                stackCaptureNanos = record.stackCaptureNanos,
                queueNanos = record.queueNanos,
                acknowledgedNanos = record.acknowledgedNanos,
                operationReturnedNanos = key?.let(operationReturns::get) ?: 0L,
            )
        }

        fun finish(): List<Record> {
            active.compareAndSet(this, null)
            return records.toList()
        }
    }

    internal class Record(
        val tid: Int,
        val syscallName: String,
        val arguments: List<Long>,
        val receivedNanos: Long,
    ) {
        @Volatile var stackCaptureNanos: Long = 0L

        @Volatile var queueNanos: Long = 0L

        @Volatile var acknowledgedNanos: Long = 0L
    }

    internal data class Timing(
        val operationStartedNanos: Long,
        val listenerReceivedNanos: Long,
        val stackCaptureNanos: Long,
        val queueNanos: Long,
        val acknowledgedNanos: Long,
        val operationReturnedNanos: Long,
    )
}
