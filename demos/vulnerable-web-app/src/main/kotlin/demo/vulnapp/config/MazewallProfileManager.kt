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
import java.util.concurrent.CopyOnWriteArrayList
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

    fun register(wrapper: Profiler.ProfilerExecutorWrapper): OwnedServiceExecutor {
        collectors += wrapper::snapshot
        return executorOwner.own(wrapper)
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

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // Every recoverable profiler shutdown failure must enter integrity evidence.
    internal fun publish(path: Path): ExecutorDrain {
        check(!published.get()) { "profile evidence was already published" }
        var drain = executorOwner.stopAdmissionAndDrain()
        try {
            shutdownProfiler()
        } catch (failure: Exception) {
            drain = ExecutorDrain.combine(
                drain,
                ExecutorDrain.Incomplete.ProfilerCleanup(failure.message ?: failure.javaClass.name),
            )
        }
        val snapshots = ArrayList<ProfilingResult<Unit>>()
        collectors.forEach { snapshots.add(it()) }
        val evidence = snapshots.fold(BillOfBehavior()) { merged, snapshot -> merged + snapshot.behavior }
        val coverages = ArrayList<ProfilingCoverage>()
        snapshots.forEach { coverages.add(it.coverage) }
        ProfileArtifactPublisher.publish(path, evidence, drain, coverages)
        published.set(true)
        val coverageFailures = incompleteCollectorReasons(coverages)
        return if (coverageFailures.isEmpty()) drain else ExecutorDrain.combine(drain, ExecutorDrain.Incomplete.CollectorCoverage(coverageFailures.joinToString("; ")))
    }

    @PreDestroy
    @Suppress("TooGenericExceptionCaught") // Publication failures are logged with their target and propagated unchanged.
    fun onShutdown() {
        if (collectors.isEmpty() || published.get()) return
        val path = resolveSbobPath(System.getProperty("mazewall.sbob.path"))
        try {
            val drain = publish(path)
            if (drain is ExecutorDrain.Incomplete) {
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
