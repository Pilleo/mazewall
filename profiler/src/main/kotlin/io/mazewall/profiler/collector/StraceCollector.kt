package io.mazewall.profiler.collector

import io.mazewall.profiler.IoUringVisibility
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.TraceableWorkload
import io.mazewall.profiler.compiler.StraceLogParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

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

    private fun spawnAndRead(workloadClass: Class<out TraceableWorkload>): String {
        val tempLog = Files.createTempFile("strace_prof_", ".log")
        val tempOut = Files.createTempFile("strace_prof_", ".out")
        val tempErr = Files.createTempFile("strace_prof_", ".err")
        var process: Process? = null
        try {
            val cmd = listOf(
                "strace",
                "-f",
                "-e",
                "trace=file,network",
                "-o",
                tempLog.toAbsolutePath().toString(),
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                "io.mazewall.profiler.strace.StraceWorkloadRunner",
                workloadClass.name,
            )
            process = ProcessBuilder(cmd)
                .redirectOutput(tempOut.toFile())
                .redirectError(tempErr.toFile())
                .start()
            StraceProcessTree.await(process, Duration.ofSeconds(60), "child strace process")
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw IllegalStateException(
                    "Child JVM failed with exit code $exitCode. Stdout: ${Files.readString(tempOut)}, Stderr: ${Files.readString(tempErr)}",
                )
            }
            return if (Files.exists(tempLog)) Files.readString(tempLog) else ""
        } finally {
            process?.takeIf(Process::isAlive)?.let(StraceProcessTree::terminate)
            Files.deleteIfExists(tempLog)
            Files.deleteIfExists(tempOut)
            Files.deleteIfExists(tempErr)
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
        var interrupted = Thread.interrupted()
        val descendants = process
            .toHandle()
            .descendants()
            .toList()
            .asReversed()
        descendants.forEach(ProcessHandle::destroy)
        process.destroy()
        try {
            if (!process.waitFor(reapTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
                process.destroyForcibly()
                process.waitFor(reapTimeout.toMillis(), TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            interrupted = true
            descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
