package io.mazewall.profiler.internal.attribution

import java.nio.file.Path
import java.util.concurrent.locks.LockSupport

/**
 * Owns collector cadence around one [TierEProfilerSession] without owning its producer lifecycle.
 *
 * An empty poll is only a scheduling signal. It never establishes producer quiescence: callers
 * must pass an externally proven [producerClosed] fact to [finish]. The loop does not create a
 * thread; an integration supplies its own lifecycle and repeatedly calls [pollOnce] or [runWhile].
 */
internal class TierECollectorLoop(
    private val session: TierEProfilerSession,
    private val policy: TierECollectorLoopPolicy = TierECollectorLoopPolicy.CONTINUOUS,
    private val parker: (Long) -> Unit = LockSupport::parkNanos,
    private val onResolved: (List<ResolvedSyscall>) -> Unit = {},
    private val receiptClock: TierECollectorClock? = null,
) : AutoCloseable {
    private var terminal = false
    private var closed = false
    private var nextIdleNanos = policy.initialIdleNanos
    private var polls = 0L
    private var activePolls = 0L
    private var idlePolls = 0L
    private var parks = 0L
    private var parkedNanos = 0L
    private var maxConsecutiveIdlePolls = 0L
    private var consecutiveIdlePolls = 0L
    private var readinessWaits = 0L
    private var readinessWakeups = 0L
    private var readinessTimeouts = 0L
    private var readinessSpuriousWakeups = 0L
    private var readinessWaitedNanos = 0L
    private var previousReadinessWakeup = false
    private val receiptAges = TierEReceiptAgeAccumulator()

    /** Drains once, delivers newly resolved observations, then applies bounded idle pacing. */
    fun pollOnce(): List<ResolvedSyscall> {
        val result = synchronized(this) {
            check(!terminal) { "Tier E collector loop is terminal" }
            val outcome = session.pollOutcome(receiptClock)
            receiptAges.recordAll(outcome.receiptAgesNanos)
            polls++
            if (outcome.drainedEventCount == 0) {
                if (previousReadinessWakeup) readinessSpuriousWakeups++
                previousReadinessWakeup = false
                idlePolls++
                consecutiveIdlePolls++
                maxConsecutiveIdlePolls = maxOf(maxConsecutiveIdlePolls, consecutiveIdlePolls)
                val delay = nextIdleNanos
                if (delay > 0) {
                    parks++
                    parkedNanos = Math.addExact(parkedNanos, delay)
                    nextIdleNanos = policy.nextDelay(delay)
                }
                PollResult(
                    resolved = outcome.resolved,
                    idleDelayNanos = delay,
                    awaitReadiness = delay == 0L && policy.readinessTimeoutMillis > 0,
                )
            } else {
                previousReadinessWakeup = false
                activePolls++
                consecutiveIdlePolls = 0
                nextIdleNanos = policy.initialIdleNanos
                PollResult(outcome.resolved, 0)
            }
        }
        if (result.resolved.isNotEmpty()) onResolved(result.resolved)
        if (result.idleDelayNanos > 0) {
            parker(result.idleDelayNanos)
        } else if (result.awaitReadiness) {
            val started = System.nanoTime()
            val readiness = session.awaitEvents(policy.readinessTimeoutMillis)
            val elapsed = System.nanoTime() - started
            synchronized(this) {
                readinessWaits++
                readinessWaitedNanos = Math.addExact(readinessWaitedNanos, elapsed)
                when (readiness) {
                    TierEEventReadiness.READY -> {
                        readinessWakeups++
                        previousReadinessWakeup = true
                    }
                    TierEEventReadiness.TIMEOUT -> readinessTimeouts++
                }
            }
        }
        return result.resolved
    }

    /** Runs synchronously until the integration's producer/lifecycle owner asks it to stop. */
    fun runWhile(keepCollecting: () -> Boolean) {
        while (keepCollecting()) pollOnce()
    }

    /**
     * Finishes only with the producer-closure fact supplied by the integration owner.
     * An empty final drain is deliberately not substituted for that fact.
     */
    @Synchronized
    fun finish(
        definitionFile: Path,
        producerClosed: Boolean,
    ): TierEProfile {
        check(!terminal) { "Tier E collector loop is terminal" }
        return session.finish(definitionFile, producerClosed).also { terminal = true }
    }

    @Synchronized
    fun finish(
        definitions: AgentDefinitionFile.Contents,
        producerClosed: Boolean,
    ): TierEProfile {
        check(!terminal) { "Tier E collector loop is terminal" }
        return session.finish(definitions, producerClosed).also { terminal = true }
    }

    @Synchronized
    fun metrics(): TierECollectorLoopMetrics =
        TierECollectorLoopMetrics(
            polls = polls,
            activePolls = activePolls,
            idlePolls = idlePolls,
            parks = parks,
            parkedNanos = parkedNanos,
            maxConsecutiveIdlePolls = maxConsecutiveIdlePolls,
            readinessWaits = readinessWaits,
            readinessWakeups = readinessWakeups,
            readinessTimeouts = readinessTimeouts,
            readinessSpuriousWakeups = readinessSpuriousWakeups,
            readinessWaitedNanos = readinessWaitedNanos,
            receiptAge = receiptAges.snapshot(),
        )

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            terminal = true
            session.close()
        }
    }

    private data class PollResult(
        val resolved: List<ResolvedSyscall>,
        val idleDelayNanos: Long,
        val awaitReadiness: Boolean = false,
    )
}

/** Bounded pacing policy; [CONTINUOUS] preserves the historical no-backoff behavior. */
internal data class TierECollectorLoopPolicy(
    val initialIdleNanos: Long,
    val maxIdleNanos: Long,
    val multiplier: Int = 2,
    val readinessTimeoutMillis: Int = 0,
) {
    init {
        require(initialIdleNanos >= 0) { "initial idle delay must be non-negative" }
        require(maxIdleNanos >= initialIdleNanos) { "max idle delay must cover the initial delay" }
        require(multiplier >= 1) { "idle multiplier must be positive" }
        require(initialIdleNanos != 0L || maxIdleNanos == 0L) {
            "a zero initial delay cannot grow into an implicit backoff"
        }
        require(readinessTimeoutMillis in 0..10_000) { "readiness timeout must be between zero and ten seconds" }
        require(readinessTimeoutMillis == 0 || initialIdleNanos == 0L) {
            "readiness waiting and idle parking are mutually exclusive"
        }
    }

    internal fun nextDelay(current: Long): Long =
        if (current == 0L || current >= maxIdleNanos || multiplier == 1) {
            current
        } else if (current > maxIdleNanos / multiplier) {
            maxIdleNanos
        } else {
            (current * multiplier).coerceAtMost(maxIdleNanos)
        }

    internal companion object {
        val CONTINUOUS = TierECollectorLoopPolicy(0, 0)
        val READINESS_DRIVEN = TierECollectorLoopPolicy(0, 0, readinessTimeoutMillis = 10)

        fun boundedAdaptive(
            initialIdleNanos: Long = 1_000L,
            maxIdleNanos: Long = 50_000L,
        ): TierECollectorLoopPolicy = TierECollectorLoopPolicy(initialIdleNanos, maxIdleNanos)
    }
}

internal data class TierECollectorLoopMetrics(
    val polls: Long,
    val activePolls: Long,
    val idlePolls: Long,
    val parks: Long,
    val parkedNanos: Long,
    val maxConsecutiveIdlePolls: Long,
    val readinessWaits: Long,
    val readinessWakeups: Long,
    val readinessTimeouts: Long,
    val readinessSpuriousWakeups: Long,
    val readinessWaitedNanos: Long,
    val receiptAge: TierEReceiptAgeMetrics,
)
