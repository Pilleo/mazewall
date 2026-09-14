package io.mazewall.enforcer.internal.supervisor

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private val cleanupFailure = AtomicReference<Throwable?>()

    // At most one reap per active slot. Never wait for a child on the reactor.
    private val reaper = ThreadPoolExecutor(
        active,
        active,
        1,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(active),
        Thread
            .ofPlatform()
            .daemon()
            .name("supervisor-socket-reap-", 0)
            .factory(),
    ).also { it.allowCoreThreadTimeOut(true) }
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

    /** Startup-only: initialize every slot before any listener is admitted. */
    fun prewarm() {
        val owner = synchronized(gate) {
            check(executor.taskCount == 0L) { "Helper prewarm must precede operation admission" }
            session()
        }
        owner.use {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // ThreadPoolExecutor assigns each of its first corePoolSize jobs a new worker.
            val jobs = List(executor.corePoolSize) { owner.prewarmSlot() }
            jobs.forEach { job ->
                SocketCleanup.await(deadline - System.nanoTime()) { job.finished.await(it, TimeUnit.NANOSECONDS) }
                check(job.result.join() == SocketHelperResult.Connected) { "Authority helper startup failed" }
            }
        }
    }

    inner class Session internal constructor() : AutoCloseable {
        private var retired = false
        private val jobs = mutableSetOf<Job>()

        fun submit(
            operation: SocketOperation,
            descriptor: SupervisorOwnedFd,
        ): CompletableFuture<SocketHelperResult> = enqueue(SocketHelperRequest.Operation(operation, descriptor)).result

        fun submitAuthority(authority: TraceeAuthorityJob): CompletableFuture<SocketHelperResult> =
            enqueue(SocketHelperRequest.Authority(authority)).result.also { result ->
                result.whenComplete { value, failure ->
                    if (failure != null) authority.prepared.completeExceptionally(failure)
                    if (value is SocketHelperResult.Failed) {
                        authority.prepared.complete(
                        TraceeAuthoritySnapshot(0, null, null, SupervisorRoute.Abort(value.errno, "authority helper failed")),
                    )
                    }
                }
            }

        internal fun prewarmSlot(): Job = enqueue(SocketHelperRequest.Prewarm)

        private fun enqueue(request: SocketHelperRequest): Job {
            val job = Job(this, request)
            synchronized(gate) {
                if (retired || closed) {
                    request.close()
                    job.result.cancel(false)
                    return job
                }
                jobs += job
                try {
                    executor.execute(job)
                } catch (_: RejectedExecutionException) {
                    jobs -= job
                    request.close()
                    job.result.complete(SocketHelperResult.Failed(EAGAIN))
                }
            }
            return job
        }

        fun cancel() {
            val pending = synchronized(gate) {
                retired = true
                if (jobs.isEmpty()) sessions -= this
                jobs.toList()
            }
            val cleanup = SocketCleanup()
            pending.forEach { job -> cleanup.attempt { job.cancel() } }
            cleanup.rethrow()
        }

        override fun close() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLEANUP_SECONDS)
            val pending = synchronized(gate) {
                retired = true
                jobs.toList()
            }
            val cleanup = SocketCleanup()
            cleanup.attempt { cancel() }
            pending.forEach { job -> cleanup.attempt { SocketCleanup.await(deadline - System.nanoTime()) { job.finished.await(it, TimeUnit.NANOSECONDS) } } }
            cleanup.rethrow()
        }

        internal fun remove(job: Job) =
            synchronized(gate) {
            jobs -= job
            if (retired && jobs.isEmpty()) sessions -= this
        }

        internal fun isClosed(): Boolean = retired || closed
    }

    internal inner class Job(
        private val session: Session,
        private val request: SocketHelperRequest,
    ) : Runnable {
        val result = CompletableFuture<SocketHelperResult>()
        val finished = CountDownLatch(1)
        private var helper: SocketHelper? = null
        private var reaping: CountDownLatch? = null
        private var cancelled = false

        fun cancel() {
            val queued = synchronized(gate) {
                if (cancelled) return
                cancelled = true
                helper?.let { worker -> reaping = reap { worker.close() } }
                executor.remove(this)
            }
            request.cancel()
            result.cancel(false)
            if (queued) {
                try {
                    request.close()
                } finally {
                    session.remove(this)
                    finished.countDown()
                }
            }
        }

        override fun run() {
            val outcome = SocketJobOutcome()
            outcome.cleanup.attempt {
                request.use {
                    if (synchronized(gate) { cancelled || session.isClosed() }) return@use
                    val worker = helpers.computeIfAbsent(Thread.currentThread()) { SocketHelperSlot(factory()) }
                    val run = synchronized(gate) {
                        helper = worker
                        !cancelled && !session.isClosed()
                    }
                    if (run) outcome.output = request.execute(worker)
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
            // A worker may win retirement before its queued reaper runs. Drain that
            // task before reusing this slot, so stale cleanup cannot fill the queue.
            reaping?.let { retired ->
                outcome.cleanup.attempt { SocketCleanup.await(TimeUnit.SECONDS.toNanos(CLEANUP_SECONDS)) { retired.await(it, TimeUnit.NANOSECONDS) } }
            }
            try {
                outcome.publish(result)
            } finally {
                session.remove(this)
                finished.countDown()
            }
        }

        private fun retireHelper() {
            val thread = Thread.currentThread()
            val worker = helpers[thread] ?: return
            // Do not replenish a slot until its previous process has actually been reaped.
            worker.close()
            helpers.remove(thread, worker)
        }
    }

    @Synchronized
    override fun close() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLEANUP_SECONDS)
        val owners = synchronized(gate) {
            closed = true
            sessions.toList()
        }
        executor.shutdown()
        val cleanup = SocketCleanup()
        owners.forEach { owner -> cleanup.attempt { owner.cancel() } }
        cleanup.attempt { SocketCleanup.await(deadline - System.nanoTime()) { executor.awaitTermination(it, TimeUnit.NANOSECONDS) } }
        val retirements = mutableListOf<CountDownLatch>()
        helpers.entries.forEach { (thread, helper) ->
            cleanup.attempt {
                retirements += reap {
                    helper.close()
                    helpers.remove(thread, helper)
                }
            }
        }
        retirements.forEach { retired ->
            cleanup.attempt { SocketCleanup.await(deadline - System.nanoTime()) { retired.await(it, TimeUnit.NANOSECONDS) } }
        }
        // Failed release remains retryable; idle cleanup threads time out meanwhile.
        if (helpers.isEmpty() && executor.isTerminated) reaper.shutdown()
        if (reaper.isShutdown) cleanup.attempt { SocketCleanup.await(deadline - System.nanoTime()) { reaper.awaitTermination(it, TimeUnit.NANOSECONDS) } }
        cleanupFailure.getAndSet(null)?.let { failure -> cleanup.attempt { throw failure } }
        cleanup.rethrow()
    }

    private fun reap(action: () -> Unit): CountDownLatch {
        val finished = CountDownLatch(1)
        reaper.execute {
            val cleanup = SocketCleanup()
            cleanup.attempt(action)
            cleanup.failure?.let { cleanupFailure.compareAndSet(null, it) }
            try {
                cleanup.rethrow()
            } finally {
                finished.countDown()
            }
        }
        return finished
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
