package io.mazewall.profiler.internal.attribution

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Independent live map reader. It owns map scanning only: immutable pages move
 * through a bounded queue to the session's serialized dictionary/ack owner.
 * No candidate validation or coverage mutation runs on this thread.
 */
internal class TierERecoveryReader(
    private val scanner: CoverageRecoveryScanner,
    private val maxQueuedPages: Int = 16,
    private val intervalMillis: Long = CoverageRecoveryMode.DEFAULT_SCAN_INTERVAL_MILLIS,
) {
    private val pages = ArrayBlockingQueue<List<RecoveryMapEntry>>(maxQueuedPages)
    private val enqueuedKeys = HashSet<RecoveryEdgeKey>()

    @Volatile
    private var stopped = false

    @Volatile
    var failed: Boolean = false
        private set

    private val thread = Thread(::run, "mazewall-tier-e-recovery-map-reader").apply { isDaemon = true }

    fun start() = thread.start()

    fun takeReady(): List<List<RecoveryMapEntry>> =
        buildList {
        while (true) {
            add(pages.poll() ?: break)
        }
    }

    /** Stop and join before the caller performs the exclusive final scan. */
    fun stopAndJoin(timeoutMillis: Long = 1_000): Boolean {
        stopped = true
        thread.interrupt()
        thread.join(timeoutMillis)
        return !thread.isAlive && !failed
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run() {
        try {
            while (!stopped) {
                val sweep = scanner.poll()
                // A non-deleting map returns the same representatives on each
                // live sweep. Transfer each key only once; otherwise the
                // bounded queue can overflow while the lifecycle owner waits
                // for a terminal artifact even though no new edge arrived.
                val fresh = sweep.entries.filter { enqueuedKeys.add(decodeRecoveryKey(it.key)) }
                if (fresh.isNotEmpty() && !pages.offer(fresh)) {
                    failed = true
                    break
                }
                // Complete sweeps wait for the declared interval. An ongoing
                // sweep yields instead of sleeping between 256-entry batches.
                if (sweep.sweepComplete) {
                    TimeUnit.MILLISECONDS.sleep(intervalMillis)
                } else {
                    Thread.yield()
                }
            }
        } catch (_: InterruptedException) {
            if (!stopped) failed = true
            Thread.currentThread().interrupt()
        } catch (cause: Exception) {
            System.err.println("tier-e-recovery-reader-failure: ${cause.javaClass.simpleName}: ${cause.message}")
            failed = true
        }
    }
}
