package io.mazewall.profiler.internal.attribution

import io.mazewall.platform.fd.CleanupFailures
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle owner for one [TierECollectorLoop] and its dedicated collector thread. */
internal class TierECollectorService(
    session: TierEProfilerSession,
    private val policy: TierECollectorLoopPolicy = TierECollectorLoopPolicy.CONTINUOUS,
    private val onResolved: (List<ResolvedSyscall>) -> Unit = {},
    private val stopJoinMillis: Long = DEFAULT_STOP_JOIN_MILLIS,
    private val receiptClock: TierECollectorClock? = null,
    private val threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "mazewall-tier-e-collector").apply { isDaemon = true }
    },
) : AutoCloseable {
    init {
        require(stopJoinMillis > 0) { "collector stop join timeout must be positive" }
    }

    private val loop = TierECollectorLoop(session, policy, onResolved = ::deliver, receiptClock = receiptClock)
    private val failure = AtomicReference<TierECollectorServiceFailure?>(null)

    @Volatile
    private var stopRequested = false
    private var state = TierECollectorServiceState.NEW
    private var worker: Thread? = null
    private var terminalProfile: TierEProfile? = null
    private var terminalFailure: Throwable? = null
    private var retired = false
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
    ): TierEProfile = finishInternal(producerClosed) { loop.finish(definitionFile, it) }

    @Synchronized
    fun finish(
        definitions: AgentDefinitionFile.Contents,
        producerClosed: Boolean,
    ): TierEProfile = finishInternal(producerClosed) { loop.finish(definitions, it) }

    @Suppress("TooGenericExceptionCaught") // A failed finalization must never be retried as collection success.
    private fun finishInternal(
        producerClosed: Boolean,
        finishLoop: (Boolean) -> TierEProfile,
    ): TierEProfile {
        terminalFailure?.let { throw it }
        return try {
            finishOnce(producerClosed, finishLoop)
        } catch (cause: Throwable) {
            failFinalization(cause)
        }
    }

    private fun failFinalization(cause: Throwable): Nothing {
        terminalFailure = cause
        if (cause is InterruptedException) Thread.currentThread().interrupt()
        throw cause
    }

    private fun finishOnce(
        producerClosed: Boolean,
        finishLoop: (Boolean) -> TierEProfile,
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
            activeWorker.interrupt()
            val profile = incompleteJoinTimeoutProfile()
            terminalProfile = profile
            state = TierECollectorServiceState.TERMINAL
            return profile
        }
        val profile = finishLoop(producerClosed && failure.get() == null)
        close()
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
    @Suppress("TooGenericExceptionCaught") // Retire independent owners without losing fatal or interrupted failures.
    override fun close() {
        closeAttempts++
        if (retired) return
        stopRequested = true
        state = TierECollectorServiceState.STOPPING
        var interrupted = Thread.interrupted()
        try {
            worker?.let { activeWorker ->
                activeWorker.join(stopJoinMillis)
                if (activeWorker.isAlive) {
                    failure.compareAndSet(null, TierECollectorServiceFailure.JOIN_TIMEOUT)
                    activeWorker.interrupt()
                    error("Tier E collector worker has not retired")
                }
            }
            var cleanupFailure: Throwable? = null
            try {
                loop.close()
            } catch (cause: InterruptedException) {
                cleanupFailure = cause
                interrupted = true
            } catch (cause: Throwable) {
                cleanupFailure = cause
            }
            interrupted = Thread.interrupted() || interrupted
            try {
                closeReceiptClock()
            } catch (cause: InterruptedException) {
                cleanupFailure = CleanupFailures.merge(cleanupFailure, cause)
                interrupted = true
            } catch (cause: Throwable) {
                cleanupFailure = CleanupFailures.merge(cleanupFailure, cause)
            }
            cleanupFailure?.let { throw it }
            retired = true
            state = TierECollectorServiceState.TERMINAL
        } catch (cause: InterruptedException) {
            Thread.currentThread().interrupt()
            throw cause
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private var receiptClockClosed = false

    private fun closeReceiptClock() {
        if (receiptClockClosed) return
        (receiptClock as? AutoCloseable)?.close()
        receiptClockClosed = true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collect() {
        try {
            while (!stopRequested) loop.pollOnce()
        } catch (cause: Exception) {
            System.err.println("tier-e-collector-failure: ${cause.javaClass.simpleName}: ${cause.message}")
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

    /**
     * A collector worker that missed its bounded join cannot safely be drained or joined further.
     * Returning no records with explicit loss and lifecycle invalidation is the only honest result.
     */
    private fun incompleteJoinTimeoutProfile(): TierEProfile =
        TierEProfile(
            emptyList(),
            SessionIntegrity(
                observedLosses = 1,
                lifecycleInvalidations = 1,
            ),
        )

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
