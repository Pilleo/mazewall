package io.mazewall.profiler.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Benchmark-only, bounded observation of raw fields before [TraceEvent] normalizes them.
 *
 * This is deliberately not part of [io.mazewall.profiler.api.ProfilingResult]: raw syscall
 * arguments can include tracee addresses and are not application-facing profiler evidence.
 */
internal object BenchmarkRawSyscallDiagnostics {
    /** Enough for the 13,000-operation standard matrix while remaining strictly bounded. */
    private const val MAX_RECORDS = 16_384
    private val active = AtomicReference<Capture?>(null)

    fun begin(
        awaitFirstArgumentAdmission: Boolean = false,
        accepts: (String) -> Boolean = { true },
    ): Capture {
        val capture = Capture(accepts, awaitFirstArgumentAdmission)
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
        private val awaitFirstArgumentAdmission: Boolean,
    ) {
        private val records = ConcurrentLinkedQueue<Record>()
        private val reservedRecords = AtomicInteger()
        private val droppedRecords = AtomicLong()
        private val admittedFirstArguments = ConcurrentHashMap.newKeySet<Long>()
        private val firstArgumentAdmissionReady = AtomicBoolean(!awaitFirstArgumentAdmission)
        private val operationStarts = ConcurrentHashMap<Long, Long>()
        private val operationReturns = ConcurrentHashMap<Long, Long>()

        internal fun record(
            tid: Int,
            syscallName: String,
            arguments: List<Long>,
        ): Record? {
            if (!accepts(syscallName)) return null
            if (
                awaitFirstArgumentAdmission &&
                (!firstArgumentAdmissionReady.get() || arguments.firstOrNull() !in admittedFirstArguments)
            ) {
                return null
            }
            if (reservedRecords.getAndIncrement() >= MAX_RECORDS) {
                droppedRecords.incrementAndGet()
                return null
            }
            return Record(tid, syscallName, arguments.take(6), System.nanoTime()).also(records::add)
        }

        val droppedCount: Long get() = droppedRecords.get()

        /** Starts raw argument capture only for the caller's declared benchmark operation IDs. */
        fun admitFirstArguments(keys: Iterable<Long>) {
            check(awaitFirstArgumentAdmission) { "first-argument admission was not requested" }
            keys.forEach(admittedFirstArguments::add)
            firstArgumentAdmissionReady.set(true)
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
