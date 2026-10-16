package demo.vulnapp.config

import io.mazewall.profiler.api.BillOfBehavior
import io.mazewall.profiler.api.Profiler
import io.mazewall.profiler.api.ProfilingCoverage
import io.mazewall.profiler.api.ProfilingResult
import jakarta.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/** Stops admission and drains profiled work before publishing one immutable snapshot. */
@Component
@ConditionalOnProperty(name = ["mazewall.profile"], havingValue = "true")
internal class MazewallProfileManager private constructor(
    private val executorOwner: ServiceExecutorOwner,
    private val shutdownProfiler: () -> Unit,
) {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(executorOwner: ServiceExecutorOwner) : this(executorOwner, Profiler::shutdown)

    private val logger = Logger.getLogger(MazewallProfileManager::class.java.name)
    private val collectors = CopyOnWriteArrayList<() -> ProfilingResult<Unit>>()
    private val published = AtomicBoolean()
    private val stateLock = Any()

    @Volatile private var publicationPath: Path? = null
    private var publication: Publication? = null

    /** The IO/cleanup attempt remains owned when its caller's entry budget expires. */
    @Suppress("TooGenericExceptionCaught") // Retained IO owner delivers original ordinary/fatal failure after physical thread end.
    private class Publication(
        action: () -> ExecutorDrain,
    ) {
        val result = CompletableFuture<ExecutorDrain>()
        val started = AtomicBoolean()
        val reported = AtomicBoolean()

        @Volatile var interrupted = false
        val thread: Thread = Thread.ofPlatform().name("web-profile-publication").inheritInheritableThreadLocals(false).unstarted {
            try {
                result.complete(action())
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            } finally {
                interrupted = Thread.currentThread().isInterrupted
            }
        }
    }

    fun register(
        raw: ExecutorService,
        createWrapper: () -> Profiler.ProfilerExecutorWrapper,
    ): ProfiledServiceExecutor {
        synchronized(stateLock) { check(publicationPath == null) { "profile publication already started" } }
        return executorOwner.ownProfiling(raw) { acquired ->
            val wrapper = createWrapper()
            acquired.attach(wrapper, wrapper::shutdown)
            collectors += wrapper::snapshot
        }
    }

    fun generateSbob(): BillOfBehavior =
        collectors.fold(BillOfBehavior()) { merged, snapshot ->
        merged + snapshot().behavior
    }

    fun resolveSbobPath(configuredPath: String?): Path {
        if (!configuredPath.isNullOrBlank()) return Paths.get(configuredPath)
        var current = java.io.File(".").absoluteFile
        while (current.parentFile != null) {
            if (java.io.File(current, "settings.gradle.kts").exists()) {
                return java.io
                    .File(current, "demos/output/sbob.json")
                    .toPath()
                    .toAbsolutePath()
            }
            current = current.parentFile
        }
        return Paths.get("sbob.json").toAbsolutePath()
    }

    @Suppress("TooGenericExceptionCaught") // Every recoverable profiler shutdown failure must enter integrity evidence.
    internal fun publish(path: Path, budget: DrainBudget = DrainBudget(executorOwner.drainTimeout)): ExecutorDrain {
        val attempt = synchronized(stateLock) {
            val current = publication
            check(!published.get() || current?.reported?.get() == false) { "profile evidence was already published" }
            check(publicationPath == null || publicationPath == path) { "retry must retain the original profile publication path" }
            publicationPath = path
            retained.add(this)
            val pending = current != null && (current.thread.isAlive || !current.result.isDone)
            val undeliveredFailure = current != null &&
                !current.reported.get() &&
                current.result.isDone &&
                (current.result.isCompletedExceptionally || (current.result.getNow(null) as? ExecutorDrain.Incomplete)?.let { ExecutorDrain.failures(it).isNotEmpty() } == true)
            if (pending || undeliveredFailure || published.get()) checkNotNull(current) else Publication { publishEffects(path, budget) }.also { publication = it }
        }
        if (attempt.started.compareAndSet(false, true)) {
            try {
                attempt.thread.start()
            } catch (failure: Throwable) {
                attempt.result.completeExceptionally(failure)
            }
        }
        try {
            val remaining = budget.remaining().toNanos()
            if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(attempt.thread, remaining)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (attempt.thread.isAlive || !attempt.result.isDone) return ExecutorDrain.Incomplete.InProgress("profile IO/cleanup attempt still physically running")
        if (attempt.interrupted) Thread.currentThread().interrupt()
        attempt.reported.set(true)
        val outcome = try {
            attempt.result.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
        if (published.get()) retained.remove(this)
        return outcome
    }

    @Suppress("TooGenericExceptionCaught") // Collector/artifact boundary retains original native/fatal failures and typed drain context.
    private fun publishEffects(
        path: Path,
        budget: DrainBudget,
    ): ExecutorDrain {
        var drain = executorOwner.stopAdmissionAndDrain(budget)
        if (drain == ExecutorDrain.Complete) {
            try {
                shutdownProfiler()
            } catch (failure: Exception) {
                drain = ExecutorDrain.combine(
                    drain,
                    ExecutorDrain.Incomplete.ProfilerCleanup(failure),
                )
            }
        }
        val interrupted = Thread.interrupted()
        try {
            val snapshots = ArrayList<ProfilingResult<Unit>>()
            collectors.forEach { snapshots.add(it()) }
            val evidence = snapshots.fold(BillOfBehavior()) { merged, snapshot -> merged + snapshot.behavior }
            val coverages = ArrayList<ProfilingCoverage>()
            snapshots.forEach { coverages.add(it.coverage) }
            ProfileArtifactPublisher.publish(path, evidence, drain, coverages)
            // Incomplete publication is diagnostic-only; retain collectors for a later drain retry.
            published.set(drain == ExecutorDrain.Complete)
            val coverageFailures = incompleteCollectorReasons(coverages)
            return if (coverageFailures.isEmpty()) drain else ExecutorDrain.combine(drain, ExecutorDrain.Incomplete.CollectorCoverage(coverageFailures.joinToString("; ")))
        } catch (ioFailure: Throwable) {
            if (drain is ExecutorDrain.Incomplete) {
                var primary: Throwable? = null
                for (failure in ExecutorDrain.failures(drain)) primary = mergeCleanupFailure(primary, failure)
                val delivered = mergeCleanupFailure(primary, ioFailure)
                delivered.addSuppressed(ServiceDrainException(drain, delivered))
                throw delivered
            }
            throw ioFailure
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @PreDestroy
    @Suppress("TooGenericExceptionCaught") // Publication failures are logged with their target and propagated unchanged.
    fun onShutdown() {
        if (collectors.isEmpty() || published.get()) return
        val path = resolveSbobPath(System.getProperty("mazewall.sbob.path"))
        try {
            val drain = publish(path)
            if (drain is ExecutorDrain.Incomplete.InProgress) {
                logger.warning("Retained pending profile publication at $path: ${drain.reason}")
            } else if (drain is ExecutorDrain.Incomplete) {
                logger.warning("Published incomplete profiling evidence: ${drain.reason}")
            } else {
                logger.info("Published profiling evidence to $path")
            }
        } catch (failure: Exception) {
            logger.severe("Failed to publish profiling evidence to $path: ${failure.message}")
            throw failure
        }
    }

    companion object {
        private val retained = ConcurrentHashMap.newKeySet<MazewallProfileManager>()

        @Suppress("TooGenericExceptionCaught") // Attempt all retained publications before preserving original fatal/secondary failures.
        fun retryRetained(): List<ExecutorDrain> {
            val managers = retained.toList()
            val budget = DrainBudget(managers.minOfOrNull { it.executorOwner.drainTimeout } ?: java.time.Duration.ZERO)
            val reports = ArrayList<ExecutorDrain>()
            var failure: Throwable? = null
            managers.forEach { manager ->
                try {
                    reports.add(manager.publish(checkNotNull(manager.publicationPath), budget))
                } catch (caught: Throwable) {
                    failure = mergeCleanupFailure(failure, caught)
                }
            }
            failure?.let { throw it }
            return reports
        }

        internal fun forTesting(
            executorOwner: ServiceExecutorOwner,
            snapshots: List<ProfilingResult<Unit>> = emptyList(),
            shutdownProfiler: () -> Unit,
        ): MazewallProfileManager =
            MazewallProfileManager(executorOwner, shutdownProfiler).apply {
            snapshots.forEach { snapshot -> collectors.add { snapshot } }
        }
    }
}
