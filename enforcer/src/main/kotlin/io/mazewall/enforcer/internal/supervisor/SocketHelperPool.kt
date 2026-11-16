package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan
import io.mazewall.ffi.networking.ConfinedDeadline
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class PoolProvisioning { Legacy, PrestartedConfined }

internal sealed interface ConfinedSessionState {
    data object Unreserved : ConfinedSessionState

    data class SettingUp(
        val binding: ConfinedBinding,
        val slot: SocketHelper,
    ) : ConfinedSessionState

    data class ConfinedReady(
        val binding: ConfinedBinding,
        val slot: SocketHelper,
    ) : ConfinedSessionState

    data class Retiring(
        val binding: ConfinedBinding,
        val slot: SocketHelper,
    ) : ConfinedSessionState

    data class Retired(
        val binding: ConfinedBinding,
    ) : ConfinedSessionState
}

/** A daemon owns the capacity; sessions own only their queued/active request leases. */
internal class SocketHelperPool(
    active: Int = setting("mazewall.supervisor.socketHelpers", DEFAULT_ACTIVE, 1..MAX_ACTIVE),
    queued: Int = setting("mazewall.supervisor.socketQueue", DEFAULT_QUEUED, 1..MAX_QUEUED),
    private val factory: () -> SocketHelper,
) : AutoCloseable {
    private val prewarmTimeoutMillis = SupervisorPrewarmBudget.parse(System.getProperty(SupervisorPrewarmBudget.PROPERTY))

    constructor(provisioning: PoolProvisioning, factory: () -> SocketHelper) : this(1, 1, factory) {
        this.provisioning = provisioning
    }

    private var provisioning = PoolProvisioning.Legacy
    private var prewarmed = false
    private var spent = false

    fun confinedProcessIdentity(): ProcessIdentity =
        synchronized(gate) {
            check(provisioning == PoolProvisioning.PrestartedConfined && prewarmed && !spent && !closed) { "Confined identity requires its unused prestarted slot" }
            requireNotNull(helpers.values.single().confinedIdentity) { "Prestarted helper has no acquired process identity" }
        }

    internal sealed interface Work {
        data class Legacy(
            val request: SocketHelperRequest,
        ) : Work

        data class Confined(
            val exchange: ConfinedHelperJob,
            val slot: SocketHelper,
            val plan: PreparedHelperReadPlan?,
        ) : Work
    }

    fun reserveConfined(
        manifest: ConfinedManifest,
        deadline: ConfinedDeadline,
    ): Session = reserve(manifest, deadline, null)

    /** Only a successful return transfers the support originals; rejection leaves them with caller. */
    fun reserveConfined(
        plan: PreparedHelperReadPlan,
        deadline: ConfinedDeadline,
    ): Session {
        plan.validate()
        return reserve(plan.manifest, deadline, plan)
    }

    private fun reserve(
        manifest: ConfinedManifest,
        deadline: ConfinedDeadline,
        plan: PreparedHelperReadPlan?,
    ): Session =
        synchronized(gate) {
        check(provisioning == PoolProvisioning.PrestartedConfined && prewarmed && !spent && !closed) { "Confined helper needs its unused prestarted slot" }
        val slot = helpers.values.single()
        val exchange = ConfinedHelperJob(manifest, if (plan == null) ConfinedDelivery.Relay else ConfinedDelivery.Direct, deadline)
        val work = Work.Confined(exchange, slot, plan)
        val session = Session(work)
        session.reserve(work)
        spent = true
        sessions += session
        session
    }

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
        check(provisioning == PoolProvisioning.Legacy) { "Confined pool cannot admit legacy sessions" }
        Session().also { sessions += it }
    }

    /** Startup-only: initialize every slot before any listener is admitted. */
    fun prewarm() {
        val owner = synchronized(gate) {
            check(!closed && !prewarmed) { "Socket helper startup already spent or closed" }
            check(executor.taskCount == 0L) { "Helper prewarm must precede operation admission" }
            Session().also { sessions += it }
        }
        owner.use {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(prewarmTimeoutMillis)
            // ThreadPoolExecutor assigns each of its first corePoolSize jobs a new worker.
            val jobs = List(executor.corePoolSize) { owner.prewarmSlot() }
            jobs.forEach { job ->
                SocketCleanup.await(deadline - System.nanoTime()) { job.finished.await(it, TimeUnit.NANOSECONDS) }
                check(job.result.join() == SocketHelperResult.Connected) { "Authority helper startup failed" }
            }
        }
        synchronized(gate) { prewarmed = true }
    }

    inner class Session internal constructor(
        private val confined: Work.Confined? = null,
    ) : AutoCloseable {
        private var confinedState: ConfinedSessionState = confined?.let { ConfinedSessionState.SettingUp(it.exchange.manifest.binding, it.slot) } ?: ConfinedSessionState.Unreserved
        val confinedRetired: Boolean get() = synchronized(gate) { confinedState is ConfinedSessionState.Retired }
        private var directSetupStarted = false

        init {
            confined?.exchange?.setup?.thenAccept { result ->
                synchronized(gate) { confinedState = afterSetup(confinedState, result) }
            }
        }

        fun beginSetup(
            manifest: ConfinedManifest,
            deadline: ConfinedDeadline,
        ): CompletableFuture<ConfinedStage> = exchange().submit(ConfinedHelperJob.Command.BeginSetup(manifest, deadline))

        fun checkConfinedActive(now: Long) = exchange().checkActive(now)

        fun setupConfined(deadline: ConfinedDeadline): CompletableFuture<ConfinedSetupResult> {
            val plan = requireNotNull(confined?.plan) { "Relay session has no local resource plan" }
            synchronized(gate) {
                check(!directSetupStarted && !retired && !closed) { "Confined setup already spent or retired" }
                directSetupStarted = true
            }
            beginSetup(plan.manifest, deadline).thenAccept(java.util.function.Consumer { stage -> advanceDirect(stage, deadline) })
            return exchange().setup
        }

        fun readConfined(
            request: ConfinedReadRequest,
            deadline: ConfinedDeadline,
        ): CompletableFuture<SupervisorDescriptorResult> {
            requireNotNull(confined?.plan) { "Relay session cannot publish a local result" }.validate()
            return beginRead(request, deadline).result
        }

        /** Successful stage callbacks run synchronously on the retained helper I/O owner. */
        private fun advanceDirect(
            stage: ConfinedStage,
            deadline: ConfinedDeadline,
        ) {
            val cleanup = SocketCleanup()
            cleanup.attempt {
                val index = when (stage) {
                    is ConfinedStage.SetupAccepted -> 0
                    is ConfinedStage.RootAdopted -> stage.index + 1
                    is ConfinedStage.Failed -> throw stage.cause
                    is ConfinedStage.Quarantined, is ConfinedStage.Forwarded, is ConfinedStage.UpstreamAccepted,
                    is ConfinedStage.IntermediateCommitted, is ConfinedStage.Done, is ConfinedStage.Denied,
                    -> error("Unexpected direct setup stage")
                }
                if (exchange().cancelled) return@attempt
                val plan = requireNotNull(confined?.plan)
                if (index == plan.manifest.capabilities.size) {
                    plan.validate()
                    finishSetup(index, deadline)
                } else {
                    offerRoot(index, plan.duplicateForTransfer(index), deadline).thenAccept { advanceDirect(it, deadline) }
                }
            }
            cleanup.failure?.let { exchange().cancel(it) }
        }

        fun offerRoot(
            index: Int,
            owner: SupervisorOwnedFd,
            deadline: ConfinedDeadline,
        ): CompletableFuture<ConfinedStage> = exchange().submit(ConfinedHelperJob.Command.OfferRoot(index, owner, deadline))

        fun finishSetup(
            count: Int,
            deadline: ConfinedDeadline,
        ): CompletableFuture<ConfinedSetupResult> {
            val exchange = exchange()
            exchange.submit(ConfinedHelperJob.Command.FinishSetup(count, deadline)).thenAccept { stage ->
                if (stage is ConfinedStage.Failed) exchange.cancel(stage.cause)
            }
            return exchange.setup
        }

        fun beginRead(
            request: ConfinedReadRequest,
            deadline: ConfinedDeadline,
        ): ConfinedReadExchange = exchange().beginRead(request, deadline)

        fun claimQuarantined(id: Long): SupervisorOwnedFd? = exchange().claimQuarantined(id)

        fun resultForwarded(
            id: Long,
            deadline: ConfinedDeadline,
        ): CompletableFuture<ConfinedStage> = exchange().submit(ConfinedHelperJob.Command.ResultForwarded(id, deadline))

        fun upstreamReceived(
            id: Long,
            deadline: ConfinedDeadline,
        ): CompletableFuture<ConfinedStage> = exchange().submit(ConfinedHelperJob.Command.UpstreamReceived(id, deadline))

        fun intermediateClosed(
            id: Long,
            deadline: ConfinedDeadline,
        ): CompletableFuture<ConfinedStage> = exchange().submit(ConfinedHelperJob.Command.IntermediateClosed(id, deadline))

        fun releaseClaim(
            id: Long,
            failure: Throwable? = null,
        ) = exchange().releaseClaim(id, failure)

        private fun exchange(): ConfinedHelperJob = requireNotNull(confined) { "Legacy session has no confined stages" }.exchange

        internal fun reserve(work: Work.Confined) {
            val job = Job(this, work)
            executor.execute(job)
            jobs += job
        }

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
            if (confined != null) {
                request.close()
                error("Confined session cannot admit legacy requests")
            }
            val job = Job(this, Work.Legacy(request))
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
                confinedState = retiring(confinedState)
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
            pending.forEach { job ->
                cleanup.attempt {
                if (confined == null) {
                    SocketCleanup.await(deadline - System.nanoTime()) { job.finished.await(it, TimeUnit.NANOSECONDS) }
                } else {
                    job.awaitConfinedRetirement(deadline)
                }
            }
            }
            cleanup.rethrow()
        }

        internal fun remove(job: Job) =
            synchronized(gate) {
            jobs -= job
            if (retired && jobs.isEmpty()) sessions -= this
        }

        internal fun physicallyRetired(binding: ConfinedBinding) =
            synchronized(gate) {
            check(confined?.exchange?.manifest?.binding == binding)
            confinedState = ConfinedSessionState.Retired(binding)
            retired = true
        }

        internal fun isClosed(): Boolean = retired || closed
    }

    internal inner class Job(
        private val session: Session,
        private val work: Work,
    ) : Runnable {
        val result = CompletableFuture<SocketHelperResult>()
        val finished = CountDownLatch(1)
        private var helper: SocketHelper? = null
        private var reaping: CountDownLatch? = null
        private var cancelled = false
        private var retirementFailure: Throwable? = null
        private var retirementFailureObserved = false

        fun cancel() {
            when (val selected = work) {
                is Work.Confined -> {
                    selected.exchange.cancel(CancellationException("Confined session retired"))
                    requestConfinedRetirement(false)
                }
                is Work.Legacy -> cancelLegacy(selected.request)
            }
        }

        private fun cancelLegacy(request: SocketHelperRequest) {
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
            when (val selected = work) {
                is Work.Confined -> runConfined(selected)
                is Work.Legacy -> runLegacy(selected.request)
            }
        }

        private fun runLegacy(request: SocketHelperRequest) {
            val outcome = SocketJobOutcome()
            outcome.cleanup.attempt {
                request.use {
                    if (synchronized(gate) { cancelled || session.isClosed() }) return@use
                    val worker = acquireHelper()
                    val run = synchronized(gate) {
                        helper = worker
                        !cancelled && !session.isClosed()
                    }
                    if (run) outcome.output = request.execute(worker)
                }
            }
            val retainedStartup = outcome.cleanup.failure
                ?.suppressed
                ?.any { it is SocketHelperStartupRetirement } == true
            if (outcome.cleanup.failure != null) {
                outcome.dispose()
                if (!retainedStartup) outcome.cleanup.attempt { retireHelper() }
            }
            val discard = synchronized(gate) {
                helper = null
                cancelled || session.isClosed()
            }
            if (discard) {
                if (!retainedStartup) outcome.cleanup.attempt { retireHelper() }
                outcome.dispose()
            }
            // A worker may win retirement before its queued reaper runs. Drain that
            // task before reusing this slot, so stale cleanup cannot fill the queue.
            reaping?.let { retired ->
                outcome.cleanup.attempt { SocketCleanup.await(TimeUnit.SECONDS.toNanos(CLEANUP_SECONDS)) { retired.await(it, TimeUnit.NANOSECONDS) } }
            }
            try {
                val failure = outcome.cleanup.failure
                if (provisioning == PoolProvisioning.PrestartedConfined && failure != null) {
                    result.completeExceptionally(failure)
                    if (failure is Error) throw failure
                } else {
                    outcome.publish(result)
                }
            } finally {
                session.remove(this)
                finished.countDown()
            }
        }

        private fun acquireHelper(): SocketHelper {
            val thread = Thread.currentThread()
            helpers[thread]?.let { return it }
            try {
                return SocketHelperSlot(factory()).also { helpers[thread] = it }
            } catch (expectedStartupFailure: Throwable) {
                expectedStartupFailure.suppressed.filterIsInstance<SocketHelperStartupRetirement>().singleOrNull()?.let { retained ->
                    helpers[thread] = SocketHelperSlot(retained)
                }
                throw expectedStartupFailure
            }
        }

        private fun runConfined(selected: Work.Confined) {
            val cleanup = SocketCleanup()
            cleanup.attempt {
                if (!selected.exchange.cancelled) selected.slot.runConfined(selected.exchange)
            }
            selected.exchange.cancel(cleanup.failure ?: CancellationException("Confined I/O loop ended"))
            cleanup.attempt(selected.exchange::quiesce)
            cleanup.attempt { requestConfinedRetirement(false) }
            cleanup.failure?.let { if (it is Error) throw it }
        }

        private fun requestConfinedRetirement(retry: Boolean) {
            val selected = work as? Work.Confined ?: error("Not a confined slot")
            val attempt = synchronized(gate) {
                val current = reaping
                if (finished.count == 0L || current != null && (!retry || current.count != 0L)) return
                CountDownLatch(1).also {
                    reaping = it
                    retirementFailure = null
                    retirementFailureObserved = false
                }
            }
            val enqueue = SocketCleanup()
            enqueue.attempt {
                reaper.execute {
                    val cleanup = SocketCleanup()
                    cleanup.attempt {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        selected.exchange.awaitQuiescence { deadline - System.nanoTime() }
                        selected.slot.close()
                        helpers.entries.removeIf { it.value === selected.slot }
                        selected.plan?.close()
                        session.physicallyRetired(selected.exchange.manifest.binding)
                        session.remove(this)
                        finished.countDown()
                    }
                    synchronized(gate) { retirementFailure = cleanup.failure }
                    attempt.countDown()
                }
            }
            enqueue.failure?.let {
                synchronized(gate) { retirementFailure = it }
                attempt.countDown()
            }
        }

        internal fun awaitConfinedRetirement(deadline: Long) {
            val retry = synchronized(gate) { retirementFailureObserved }
            requestConfinedRetirement(retry)
            val attempt = synchronized(gate) { reaping }
            if (attempt != null) SocketCleanup.await(deadline - System.nanoTime()) { attempt.await(it, TimeUnit.NANOSECONDS) }
            val failure = synchronized(gate) { retirementFailure?.also { retirementFailureObserved = true } }
            failure?.let { throw it }
            check(finished.count == 0L) { "Confined retirement has not completed" }
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
        if (provisioning == PoolProvisioning.PrestartedConfined) {
            closeConfined()
            return
        }
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

    private fun closeConfined() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLEANUP_SECONDS)
        val owners = synchronized(gate) {
            closed = true
            sessions.toList()
        }
        executor.shutdown()
        val cleanup = SocketCleanup()
        owners.forEach { cleanup.attempt(it::close) }
        cleanup.attempt { SocketCleanup.await(deadline - System.nanoTime()) { executor.awaitTermination(it, TimeUnit.NANOSECONDS) } }
        if (!spent) {
            helpers.entries.forEach { (thread, helper) ->
                cleanup.attempt {
            helper.close()
            helpers.remove(thread, helper)
        }
            }
        }
        if (synchronized(gate) { sessions.isEmpty() } && helpers.isEmpty() && executor.isTerminated) reaper.shutdown()
        if (reaper.isShutdown) cleanup.attempt { SocketCleanup.await(deadline - System.nanoTime()) { reaper.awaitTermination(it, TimeUnit.NANOSECONDS) } }
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
        private fun retiring(state: ConfinedSessionState): ConfinedSessionState =
            when (state) {
            is ConfinedSessionState.SettingUp -> ConfinedSessionState.Retiring(state.binding, state.slot)
            is ConfinedSessionState.ConfinedReady -> ConfinedSessionState.Retiring(state.binding, state.slot)
            ConfinedSessionState.Unreserved, is ConfinedSessionState.Retiring, is ConfinedSessionState.Retired -> state
        }

        private fun afterSetup(
            state: ConfinedSessionState,
            result: ConfinedSetupResult,
        ): ConfinedSessionState =
            when (state) {
            is ConfinedSessionState.SettingUp -> when (result) {
                is ConfinedSetupResult.Ready -> {
                    check(result.binding == state.binding)
                    ConfinedSessionState.ConfinedReady(state.binding, state.slot)
                }
                is ConfinedSetupResult.Rejected -> ConfinedSessionState.Retiring(state.binding, state.slot)
            }
            ConfinedSessionState.Unreserved, is ConfinedSessionState.ConfinedReady, is ConfinedSessionState.Retiring, is ConfinedSessionState.Retired -> state
        }

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
