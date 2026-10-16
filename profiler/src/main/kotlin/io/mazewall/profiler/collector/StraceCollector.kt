package io.mazewall.profiler.collector

import io.mazewall.platform.process.snapshotDescendants
import io.mazewall.profiler.api.IoUringVisibility
import io.mazewall.profiler.api.ObservationSource
import io.mazewall.profiler.api.ProfileObservation
import io.mazewall.profiler.api.TraceableWorkload
import io.mazewall.profiler.compiler.StraceLogParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Descendant `strace -f` collector. Emits [ProfileObservation]s only.
 *
 * Parent-child ptrace is allowed under Yama `ptrace_scope=1`. This is not an
 * `io_uring` tracer.
 */
internal class StraceCollector(
    private val workloadClass: Class<out TraceableWorkload>? = null,
    private val recordedLog: Path? = null,
) : ProfileCollector {
    private companion object {
        const val WORKLOAD_TIMEOUT_SECONDS = 60L
        val workloadTimeout: Duration = Duration.ofSeconds(WORKLOAD_TIMEOUT_SECONDS)
    }

    override val source: ObservationSource = ObservationSource.STRACE
    private var logText: String? = null

    override fun start() {
        logText = when {
            recordedLog != null -> Files.readString(recordedLog)
            workloadClass != null -> spawnAndRead(workloadClass)
            else -> throw IllegalArgumentException("StraceCollector needs a workload class or a recorded log")
        }
    }

    override fun drain(): CollectorDrain {
        val text = checkNotNull(logText) { "StraceCollector.start() was not called" }
        val parseResult = StraceLogParser.parseWithStats(text)
        val observations = parseResult.observations
        val ioUring = if (observations.any { hasUring(it) }) {
            IoUringVisibility.BLIND
        } else {
            IoUringVisibility.UNSEEN
        }
        return CollectorDrain(
            observations = observations,
            droppedEvents = parseResult.droppedRecords,
            drainComplete = true,
            ioUring = ioUring,
        )
    }

    override fun close() {
        logText = null
    }

    private fun hasUring(obs: ProfileObservation): Boolean =
        obs is ProfileObservation.Syscall &&
            obs.name in setOf("IO_URING_SETUP", "IO_URING_ENTER", "IO_URING_REGISTER")

    @Suppress("ThrowingExceptionFromFinally", "TooGenericExceptionCaught")
    private fun spawnAndRead(workloadClass: Class<out TraceableWorkload>): String {
        var tempLog: Path? = null
        var tempOut: Path? = null
        var tempErr: Path? = null
        var process: Process? = null
        var primary: Throwable? = null
        try {
            tempLog = Files.createTempFile("strace_prof_", ".log")
            tempOut = Files.createTempFile("strace_prof_", ".out")
            tempErr = Files.createTempFile("strace_prof_", ".err")
            val cmd = listOf(
                "strace",
                "-f",
                "-e",
                "trace=file,network",
                "-o",
                checkNotNull(tempLog).toAbsolutePath().toString(),
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                "io.mazewall.profiler.strace.StraceWorkloadRunner",
                workloadClass.name,
            )
            process = ProcessBuilder(cmd)
                .redirectOutput(checkNotNull(tempOut).toFile())
                .redirectError(checkNotNull(tempErr).toFile())
                .start()
            StraceProcessTree.await(process, workloadTimeout, "child strace process")
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw IllegalStateException(
                    "Child JVM failed with exit code $exitCode. Stdout: ${Files.readString(checkNotNull(tempOut))}, Stderr: ${Files.readString(checkNotNull(tempErr))}",
                )
            }
            return checkNotNull(tempLog).let { log -> if (Files.exists(log)) Files.readString(log) else "" }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            runCatching { process?.takeIf(Process::isAlive)?.let(StraceProcessTree::terminate) }
                .onFailure { cleanup -> primary?.addSuppressed(cleanup) ?: throw cleanup }
            listOfNotNull(tempLog, tempOut, tempErr).forEach { file ->
                runCatching { Files.deleteIfExists(file) }
                    .onFailure { cleanup -> primary?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }
}

/** Owns cancellation and bounded reaping for a spawned strace process tree. */
internal object StraceProcessTree {
    private val reapTimeout = Duration.ofSeconds(2)

    @Throws(InterruptedException::class)
    fun await(
        process: Process,
        timeout: Duration,
        description: String,
    ) {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process)
                throw IllegalStateException("$description timed out after ${timeout.toSeconds()}s")
            }
        } catch (interrupted: InterruptedException) {
            terminate(process)
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    fun terminate(process: Process) {
        val interruption = CleanupInterruption(Thread.interrupted())
        val handles = process.toHandle().snapshotDescendants().asReversed() + process.toHandle()

        handles.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy)
        awaitEach(handles, reapTimeout, interruption)

        val survivors = handles.filter(ProcessHandle::isAlive)
        survivors.forEach(ProcessHandle::destroyForcibly)
        awaitEach(survivors, reapTimeout, interruption)
        awaitProcess(process, reapTimeout, interruption)

        // Descendants can fork while the first wave is being reaped. Rescan
        // once after forcible termination so grandchildren are not orphaned.
        val lateDescendants = process.toHandle().snapshotDescendants().asReversed()
        lateDescendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        awaitEach(lateDescendants, reapTimeout, interruption)

        if (interruption.seen) {
            Thread.currentThread().interrupt()
        }
    }

    private fun awaitEach(
        handles: List<ProcessHandle>,
        timeout: Duration,
        interruption: CleanupInterruption,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        handles.forEach { handle ->
            while (handle.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return@forEach
                try {
                    handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interruption.seen = true
                } catch (_: TimeoutException) {
                    return@forEach
                } catch (_: ExecutionException) {
                    return@forEach
                }
            }
        }
    }

    private fun awaitProcess(
        process: Process,
        timeout: Duration,
        interruption: CleanupInterruption,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (process.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            try {
                if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) return
            } catch (_: InterruptedException) {
                interruption.seen = true
            }
        }
    }

    private class CleanupInterruption(
        var seen: Boolean,
    )
}
