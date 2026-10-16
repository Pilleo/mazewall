package demo.vulnapp.config

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.IncompleteDrainException
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.api.execution.ManagedRuntime
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

internal sealed interface ExecutorDrain {
    data object Complete : ExecutorDrain

    sealed interface Incomplete : ExecutorDrain {
        val reason: String

        data class Timeout(
            val waited: Duration,
        ) : Incomplete {
            override val reason: String = "timeout after ${waited.toMillis()}ms"
        }

        data object Interrupted : Incomplete {
            override val reason: String = "interrupted"
        }

        data class InProgress(
            val reasonDetail: String,
        ) : Incomplete {
            override val reason: String = reasonDetail
        }

        data class Runtime(
            val report: DrainReport.Incomplete,
        ) : Incomplete {
            override val reason: String = "owned runtime has unfinished execution or cleanup: $report"
        }

        data class ProfilerCleanup(
            val failure: Throwable,
        ) : Incomplete {
            override val reason: String = "profiler cleanup failed: ${failure.message ?: failure.javaClass.name}"
        }

        data class CollectorCoverage(
            val message: String,
        ) : Incomplete {
            override val reason: String = "profiler coverage incomplete: $message"
        }

        data class Multiple(
            val failures: List<Incomplete>,
        ) : Incomplete {
            init {
                require(failures.size >= 2) { "multiple failures requires at least two causes" }
            }

            override val reason: String = failures.joinToString(separator = "; ", transform = Incomplete::reason)
        }
    }

    companion object {
        fun failures(report: Incomplete): List<Throwable> {
            val collected = ArrayList<Throwable>()
            when (report) {
                is Incomplete.Runtime -> report.report.cleanupFailure?.let(collected::add)
                is Incomplete.ProfilerCleanup -> collected.add(report.failure)
                is Incomplete.Multiple -> for (part in report.failures) collected.addAll(failures(part))
                is Incomplete.Timeout, Incomplete.Interrupted, is Incomplete.CollectorCoverage, is Incomplete.InProgress -> Unit
            }
            return collected
        }

        fun combine(
            first: ExecutorDrain,
            second: Incomplete,
        ): Incomplete =
            when (first) {
                Complete -> second
                is Incomplete.Multiple -> Incomplete.Multiple(first.failures + second)
                is Incomplete -> Incomplete.Multiple(listOf(first, second))
            }
    }
}

internal class ServiceDrainException(
    val report: ExecutorDrain.Incomplete,
    omit: Throwable? = null,
) : IllegalStateException(report.reason, ExecutorDrain.failures(report).firstOrNull { it !== omit }) {
    init {
        for (failure in ExecutorDrain.failures(report)) if (failure !== cause && failure !== omit) addSuppressed(failure)
    }
}

internal fun mergeCleanupFailure(
    first: Throwable?,
    next: Throwable,
): Throwable {
    if (first == null || first === next) return next
    val primary = if (next is Error && first !is Error) next else first
    val secondary = if (primary === first) next else first
    if (secondary !in primary.suppressed) primary.addSuppressed(secondary)
    return primary
}

/** One absolute entry deadline also budgets contention and collector retirement. */
internal class DrainBudget(
    timeout: Duration,
) {
    private val entered = System.nanoTime()
    private val deadline = entered + timeout.toNanos()

    fun remaining(): Duration = Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0L))

    fun elapsed(): Duration = Duration.ofNanos((System.nanoTime() - entered).coerceAtLeast(0L))

    fun acquire(lock: ReentrantLock): Boolean {
        if (lock.tryLock()) return true
        return try {
            lock.tryLock(remaining().toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

/** Required calls expose the real owned handle; interruption never implies physical end. */
internal class OwnedServiceOperation(
    private val runtime: ManagedRuntime,
    private val domain: InProcessDomain,
    private val admitting: AtomicBoolean,
) {
    fun <T> submit(action: Callable<T>): InvocationHandle<T> {
        if (!admitting.get()) throw RejectedExecutionException("service owner is draining")
        return runtime.submit(domain, action)
    }

    fun <T> call(block: () -> T): T {
        val invocation = submit(Callable(block))
        return try {
            invocation.result().toCompletableFuture().get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (failure: InterruptedException) {
            invocation.requestCancellation()
            Thread.currentThread().interrupt()
            throw failure
        }
    }
}

/** Profiling is diagnostic execution, not a required-protection runtime. */
internal class ProfiledServiceExecutor(
    private val raw: ExecutorService,
) {
    private val accepting = AtomicBoolean(true)

    @Volatile private var delegate: ExecutorService = raw

    @Volatile private var collector: (() -> Unit)? = null
    private var closing: CollectorClose? = null

    @Suppress("TooGenericExceptionCaught") // Owned cleanup thread retains original ordinary/fatal failure before physical retirement.
    private class CollectorClose(
        val action: () -> Unit,
    ) {
        val ended = CountDownLatch(1)

        @Volatile var failure: Throwable? = null

        @Volatile var interrupted = false
        val thread: Thread = Thread.ofPlatform().name("web-profiler-collector-close").inheritInheritableThreadLocals(false).unstarted {
            try {
                action()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                interrupted = Thread.currentThread().isInterrupted
                ended.countDown()
            }
        }
    }

    internal fun attach(
        wrapper: ExecutorService,
        closeCollector: () -> Unit,
    ) {
        delegate = wrapper
        collector = closeCollector
    }

    fun <T> call(block: () -> T): T {
        if (!accepting.get()) throw RejectedExecutionException("service executor is draining")
        val accepted = delegate.submit(Callable(block))
        return try {
            accepted.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (failure: InterruptedException) {
            accepted.cancel(true)
            Thread.currentThread().interrupt()
            throw failure
        }
    }

    fun execute(block: () -> Unit) {
        if (!accepting.get()) throw RejectedExecutionException("service executor is draining")
        delegate.execute(block)
    }

    internal fun stopAdmission() {
        accepting.set(false)
        raw.shutdown()
    }

    internal fun rejectAdmission() {
        accepting.set(false)
    }

    @Suppress("TooGenericExceptionCaught") // A failed cleanup-thread start stays an owned, retryable original failure.
    internal fun awaitDrain(budget: DrainBudget): ExecutorDrain {
        val stopped = try {
            raw.awaitTermination(budget.remaining().toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!stopped) {
            forceStop()
            return if (Thread.currentThread().isInterrupted) ExecutorDrain.Incomplete.Interrupted else ExecutorDrain.Incomplete.Timeout(budget.elapsed())
        }
        val action = collector ?: return ExecutorDrain.Complete
        val attempt = closing ?: CollectorClose(action).also {
            closing = it
            try {
                it.thread.start()
            } catch (failure: Throwable) {
                it.failure = failure
                it.ended.countDown()
            }
        }
        try {
            val remaining = budget.remaining().toNanos()
            if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(attempt.thread, remaining)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (attempt.ended.count != 0L || attempt.thread.isAlive) return ExecutorDrain.Incomplete.InProgress("collector close still physically running")
        if (attempt.interrupted) Thread.currentThread().interrupt()
        val failure = attempt.failure
        if (failure != null) {
            closing = null // Retry the exact collector only after its failed close thread has ended.
            return ExecutorDrain.Incomplete.ProfilerCleanup(failure)
        }
        collector = null
        closing = null
        return ExecutorDrain.Complete
    }

    internal fun forceStop() {
        accepting.set(false)
        for (pending in raw.shutdownNow()) if (pending is java.util.concurrent.Future<*>) pending.cancel(false)
    }
}

internal class ServiceExecutorOwner(
    internal val drainTimeout: Duration,
    private val openRuntime: () -> ManagedRuntime = { ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, Duration.ofSeconds(SERVICE_PREPARATION_SECONDS), drainTimeout) },
) {
    private val stateLock = Any()
    private val cleanupLock = ReentrantLock()
    private val executors = ArrayList<ProfiledServiceExecutor>()
    private val admitting = AtomicBoolean(true)
    private var runtime: ManagedRuntime? = null
    private var closed = false
    private var opening = false
    private var acquisitions = 0
    private var revision = 0L

    @Volatile private var result: ExecutorDrain? = null

    fun ownProfiling(
        raw: ExecutorService,
        initialize: (ProfiledServiceExecutor) -> Unit = {},
    ): ProfiledServiceExecutor {
        val acquired = synchronized(stateLock) {
            check(!closed) { "service executor owner is closed" }
            retained.add(this)
            acquisitions++
            revision++
            ProfiledServiceExecutor(raw).also { executors += it }
        }
        try {
            initialize(acquired)
            synchronized(stateLock) { if (closed) throw RejectedExecutionException("profiling owner closed during acquisition") }
            return acquired
        } finally {
            synchronized(stateLock) {
                acquisitions--
                revision++
            }
        }
    }

    fun prepare(policy: Policy<*, Uncompiled>): OwnedServiceOperation {
        val existing = synchronized(stateLock) {
            if (closed || opening) throw RejectedExecutionException("service owner closed or bootstrap already in progress")
            retained.add(this)
            acquisitions++
            revision++
            if (runtime == null) opening = true
            runtime
        }
        try {
            val owned = existing ?: openRuntime().also { acquired ->
                synchronized(stateLock) {
                    runtime = acquired
                    opening = false
                    revision++
                }
            }
            synchronized(stateLock) { if (closed) throw RejectedExecutionException("runtime acquired after close; retained for retirement") }
            val domain = owned.prepare(policy, DomainLimits(SERVICE_WORKERS, SERVICE_ADMISSIONS))
            synchronized(stateLock) { if (closed) throw RejectedExecutionException("domain prepared after close; retained for retirement") }
            return OwnedServiceOperation(owned, domain, admitting)
        } finally {
            synchronized(stateLock) {
                acquisitions--
                opening = false
                revision++
            }
        }
    }

    fun stopAdmissionAndDrain(): ExecutorDrain = stopAdmissionAndDrain(DrainBudget(drainTimeout))

    @Suppress("TooGenericExceptionCaught") // All exact owners get a cleanup attempt before preserving original fatal identity.
    internal fun stopAdmissionAndDrain(budget: DrainBudget): ExecutorDrain {
        synchronized(stateLock) {
            if (result == ExecutorDrain.Complete) return ExecutorDrain.Complete
            retained.add(this) // Root before shutdown/native/collector effects, including exceptional exits.
            closed = true
            admitting.set(false)
            executors.forEach(ProfiledServiceExecutor::rejectAdmission)
        }
        if (!budget.acquire(cleanupLock)) {
            val previous = result as? ExecutorDrain.Incomplete
            val pending = ExecutorDrain.Incomplete.InProgress("cleanup already running or caller interrupted")
            return if (previous == null) pending else ExecutorDrain.combine(previous, pending)
        }
        try {
            val snapshot = synchronized(stateLock) { Triple(runtime, executors.toList(), revision) }
            var outcome: ExecutorDrain = ExecutorDrain.Complete

            fun attempt(action: () -> ExecutorDrain) {
                val drained = try {
                    action()
                } catch (failure: Throwable) {
                    ExecutorDrain.Incomplete.ProfilerCleanup(failure)
                }
                if (drained is ExecutorDrain.Incomplete) outcome = ExecutorDrain.combine(outcome, drained)
            }
            snapshot.second.forEach { executor ->
                attempt {
                    executor.stopAdmission()
                    ExecutorDrain.Complete
                }
            }
            snapshot.first?.let { owned ->
                attempt {
                    when (val report = owned.drain(budget.remaining())) {
                        DrainReport.Complete -> ExecutorDrain.Complete
                        is DrainReport.Incomplete -> ExecutorDrain.Incomplete.Runtime(report)
                    }
                }
            }
            snapshot.second.forEach { executor -> attempt { executor.awaitDrain(budget) } }
            if (Thread.currentThread().isInterrupted && outcome == ExecutorDrain.Complete) outcome = ExecutorDrain.Incomplete.Interrupted
            synchronized(stateLock) {
                if (acquisitions != 0 || revision != snapshot.third) outcome = ExecutorDrain.combine(outcome, ExecutorDrain.Incomplete.InProgress("acquisition/publication still requires retirement"))
                result = outcome
                if (outcome == ExecutorDrain.Complete) retained.remove(this)
            }
            if (outcome is ExecutorDrain.Incomplete) {
                val failures = ExecutorDrain.failures(outcome)
                failures.firstOrNull { it is Error }?.let { fatal ->
                    for (failure in failures) if (failure !== fatal && failure !in fatal.suppressed) fatal.addSuppressed(failure)
                    fatal.addSuppressed(ServiceDrainException(outcome, fatal))
                    throw fatal
                }
            }
            return outcome
        } finally {
            cleanupLock.unlock()
        }
    }

    @PreDestroy
    fun close() {
        when (val report = stopAdmissionAndDrain()) {
            ExecutorDrain.Complete -> Unit
            is ExecutorDrain.Incomplete.Runtime -> throw IncompleteDrainException(report.report)
            is ExecutorDrain.Incomplete -> throw ServiceDrainException(report)
        }
    }

    companion object {
        private const val SERVICE_WORKERS = 4
        private const val SERVICE_ADMISSIONS = 64
        private const val SERVICE_PREPARATION_SECONDS = 30L

        // Spring discards destroyed singletons even when their destruction callback fails.
        private val retained = ConcurrentHashMap.newKeySet<ServiceExecutorOwner>()

        @Suppress("TooGenericExceptionCaught") // Attempt all retained owners before propagating original fatal/secondary failures.
        fun drainRetained(): List<ExecutorDrain.Incomplete> {
            val owners = retained.toList()
            val budget = DrainBudget(owners.minOfOrNull { it.drainTimeout } ?: Duration.ZERO)
            val reports = ArrayList<ExecutorDrain.Incomplete>()
            var failure: Throwable? = null
            owners.forEach { owner ->
                try {
                    (owner.stopAdmissionAndDrain(budget) as? ExecutorDrain.Incomplete)?.let(reports::add)
                } catch (caught: Throwable) {
                    failure = mergeCleanupFailure(failure, caught)
                }
            }
            failure?.let { throw it }
            return reports
        }
    }
}

@Configuration
internal class ServiceExecutionConfig {
    @Bean
    fun serviceExecutorOwner(): ServiceExecutorOwner {
        val seconds = System.getProperty("mazewall.executor-drain-seconds", "10").toLongOrNull()
        require(seconds != null && seconds in 1..300) { "mazewall.executor-drain-seconds must be in 1..300" }
        return ServiceExecutorOwner(Duration.ofSeconds(seconds))
    }
}
