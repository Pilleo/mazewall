package io.mazewall.enforcer.supervisor

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A daemon owns the capacity; sessions own only their queued/active request leases. */
internal class SocketHelperPool(
    active: Int = setting("mazewall.supervisor.socketHelpers", DEFAULT_ACTIVE, 1..MAX_ACTIVE),
    queued: Int = setting("mazewall.supervisor.socketQueue", DEFAULT_QUEUED, 1..MAX_QUEUED),
    private val factory: () -> SocketHelper,
) : AutoCloseable {
    init {
        require(active in 1..MAX_ACTIVE)
        require(queued in 1..MAX_QUEUED)
    }

    private val gate = Any()
    private var closed = false
    private val sessions = mutableSetOf<Session>()
    private val helpers = ConcurrentHashMap<Thread, SocketHelper>()
    private val executor = ThreadPoolExecutor(
        active,
        active,
        0,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queued),
        Thread
            .ofPlatform()
            .daemon()
            .name("supervisor-socket-slot-", 0)
            .factory(),
    )

    fun session(): Session =
        synchronized(gate) {
        check(!closed) { "Socket helper pool is closed" }
        Session().also { sessions += it }
    }

    inner class Session internal constructor() : AutoCloseable {
        private var retired = false
        private val jobs = mutableSetOf<Job>()

        fun submit(
            operation: SocketOperation,
            descriptor: SupervisorOwnedFd,
        ): CompletableFuture<SocketHelperResult> {
            val job = Job(this, operation, descriptor)
            synchronized(gate) {
                if (retired || closed) {
                    descriptor.close()
                    job.result.cancel(false)
                    return job.result
                }
                jobs += job
                try {
                    executor.execute(job)
                } catch (_: RejectedExecutionException) {
                    jobs -= job
                    descriptor.close()
                    job.result.complete(SocketHelperResult.Failed(EAGAIN))
                }
            }
            return job.result
        }

        override fun close() {
            val pending = synchronized(gate) {
                retired = true
                jobs.toList()
            }
            val cleanup = SocketCleanup()
            pending.forEach { job -> cleanup.attempt { job.cancel() } }
            pending.forEach { job -> cleanup.attempt { SocketCleanup.await(TimeUnit.SECONDS.toNanos(CLEANUP_SECONDS)) { job.finished.await(it, TimeUnit.NANOSECONDS) } } }
            cleanup.rethrow()
            synchronized(gate) { sessions -= this }
        }

        internal fun remove(job: Job) = synchronized(gate) { jobs -= job }

        internal fun isClosed(): Boolean = retired || closed
    }

    internal inner class Job(
        private val session: Session,
        private val operation: SocketOperation,
        private val descriptor: SupervisorOwnedFd,
    ) : Runnable {
        val result = CompletableFuture<SocketHelperResult>()
        val finished = CountDownLatch(1)
        private var helper: SocketHelper? = null
        private var cancelled = false

        fun cancel() {
            val active = synchronized(gate) {
                cancelled = true
                if (executor.remove(this)) {
                    try {
                        descriptor.close()
                    } finally {
                        session.remove(this)
                        finished.countDown()
                    }
                }
                helper
            }
            result.cancel(false)
            active?.close()
        }

        override fun run() {
            val outcome = SocketJobOutcome()
            outcome.cleanup.attempt {
                descriptor.use {
                    if (synchronized(gate) { cancelled || session.isClosed() }) return@use
                    val worker = helpers.computeIfAbsent(Thread.currentThread()) { factory() }
                    val run = synchronized(gate) {
                        helper = worker
                        !cancelled && !session.isClosed()
                    }
                    if (run) outcome.output = worker.execute(operation, descriptor)
                }
            }
            if (outcome.cleanup.failure != null) {
                outcome.dispose()
                outcome.cleanup.attempt { retireHelper() }
            }
            val discard = synchronized(gate) {
                helper = null
                cancelled || session.isClosed()
            }
            if (discard) {
                outcome.cleanup.attempt { retireHelper() }
                outcome.dispose()
            }
            session.remove(this)
            finished.countDown()
            outcome.publish(result)
        }

        private fun retireHelper() {
            val thread = Thread.currentThread()
            val worker = helpers[thread] ?: return
            // Do not replenish a slot until its previous process has actually been reaped.
            worker.close()
            helpers.remove(thread, worker)
        }
    }

    override fun close() {
        val owners = synchronized(gate) {
            closed = true
            sessions.toList()
        }
        executor.shutdown()
        val cleanup = SocketCleanup()
        owners.forEach { owner -> cleanup.attempt { owner.close() } }
        cleanup.attempt { SocketCleanup.await(TimeUnit.SECONDS.toNanos(CLEANUP_SECONDS)) { executor.awaitTermination(it, TimeUnit.NANOSECONDS) } }
        helpers.entries.forEach { (thread, helper) ->
            cleanup.attempt {
                helper.close()
                helpers.remove(thread, helper)
            }
        }
        cleanup.rethrow()
    }

    companion object {
        private const val EAGAIN = 11
        private const val DEFAULT_ACTIVE = 4
        private const val DEFAULT_QUEUED = 64
        private const val MAX_ACTIVE = 32
        private const val MAX_QUEUED = 4096
        private const val CLEANUP_SECONDS = 10L

        private fun setting(
            name: String,
            fallback: Int,
            range: IntRange,
        ): Int {
            val raw = System.getProperty(name) ?: return fallback
            return requireNotNull(raw.toIntOrNull()?.takeIf { it in range }) { "$name must be in $range" }
        }
    }
}
