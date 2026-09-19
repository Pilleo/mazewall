package io.mazewall.profiler.internal.attribution

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle owner for one [TierECollectorLoop] and its dedicated collector thread. */
internal class TierECollectorService(
    session: TierEProfilerSession,
    private val policy: TierECollectorLoopPolicy = TierECollectorLoopPolicy.CONTINUOUS,
    private val onResolved: (List<ResolvedSyscall>) -> Unit = {},
    private val stopJoinMillis: Long = DEFAULT_STOP_JOIN_MILLIS,
    private val threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "mazewall-tier-e-collector").apply { isDaemon = true }
    },
) : AutoCloseable {
    init {
        require(stopJoinMillis > 0) { "collector stop join timeout must be positive" }
    }

    private val loop = TierECollectorLoop(session, policy, onResolved = ::deliver)
    private val failure = AtomicReference<TierECollectorServiceFailure?>(null)

    @Volatile
    private var stopRequested = false
    private var state = TierECollectorServiceState.NEW
    private var worker: Thread? = null
    private var terminalProfile: TierEProfile? = null
    private var finishAttempts = 0L
    private var closeAttempts = 0L
    private var declaredProducerClosed: Boolean? = null

    @Synchronized
    fun start() {
        check(state == TierECollectorServiceState.NEW) { "Tier E collector service can only start once" }
        state = TierECollectorServiceState.RUNNING
        worker = threadFactory(Runnable(::collect)).also { it.start() }
    }

    /**
     * Stops collection and finalizes one profile.
     *
     * [producerClosed] remains a caller-owned proof. Service-local delivery and collection failures
     * deliberately downgrade it before the loop delegates final evidence to the session.
     */
    @Synchronized
    fun finish(
        definitionFile: Path,
        producerClosed: Boolean,
    ): TierEProfile {
        terminalProfile?.let { return it }
        check(state == TierECollectorServiceState.RUNNING) {
            "Tier E collector service must be running before finish"
        }
        finishAttempts++
        declaredProducerClosed = producerClosed
        state = TierECollectorServiceState.STOPPING
        stopRequested = true
        val activeWorker = checkNotNull(worker)
        activeWorker.join(stopJoinMillis)
        if (activeWorker.isAlive) {
            failure.compareAndSet(null, TierECollectorServiceFailure.JOIN_TIMEOUT)
            loop.close()
            state = TierECollectorServiceState.TERMINAL
            throw IllegalStateException("Tier E collector service did not stop within $stopJoinMillis ms")
        }
        val profile = loop.finish(definitionFile, producerClosed && failure.get() == null)
        loop.close()
        terminalProfile = profile
        state = TierECollectorServiceState.TERMINAL
        return profile
    }

    @Synchronized
    fun metrics(): TierECollectorServiceMetrics =
        TierECollectorServiceMetrics(
            state = state,
            policy = policy,
            finishAttempts = finishAttempts,
            closeAttempts = closeAttempts,
            declaredProducerClosed = declaredProducerClosed,
            failure = failure.get(),
            workerAlive = worker?.isAlive == true,
            loop = loop.metrics(),
        )

    @Synchronized
    override fun close() {
        closeAttempts++
        if (state == TierECollectorServiceState.TERMINAL) return
        stopRequested = true
        state = TierECollectorServiceState.STOPPING
        worker?.let { activeWorker ->
            activeWorker.join(stopJoinMillis)
            if (activeWorker.isAlive) {
                failure.compareAndSet(null, TierECollectorServiceFailure.JOIN_TIMEOUT)
                activeWorker.interrupt()
            }
        }
        loop.close()
        state = TierECollectorServiceState.TERMINAL
    }

    private fun collect() {
        try {
            while (!stopRequested) loop.pollOnce()
        } catch (_: Exception) {
            failure.compareAndSet(null, TierECollectorServiceFailure.COLLECTION)
            stopRequested = true
        }
    }

    private fun deliver(resolved: List<ResolvedSyscall>) {
        try {
            onResolved(resolved)
        } catch (_: Exception) {
            failure.compareAndSet(null, TierECollectorServiceFailure.DELIVERY)
            stopRequested = true
        }
    }

    private companion object {
        const val DEFAULT_STOP_JOIN_MILLIS = 1_000L
    }
}

internal enum class TierECollectorServiceState {
    NEW,
    RUNNING,
    STOPPING,
    TERMINAL,
}

internal enum class TierECollectorServiceFailure {
    COLLECTION,
    DELIVERY,
    JOIN_TIMEOUT,
}

internal data class TierECollectorServiceMetrics(
    val state: TierECollectorServiceState,
    val policy: TierECollectorLoopPolicy,
    val finishAttempts: Long,
    val closeAttempts: Long,
    val declaredProducerClosed: Boolean?,
    val failure: TierECollectorServiceFailure?,
    val workerAlive: Boolean,
    val loop: TierECollectorLoopMetrics,
)
