package io.mazewall.profiler.collector

import io.mazewall.profiler.IoUringVisibility
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.TraceableWorkload
import io.mazewall.profiler.compiler.StraceLogParser
import java.nio.file.Files
import java.nio.file.Path

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
            val process = ProcessBuilder(cmd).start()
            val outFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val errFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().readText()
            }
            val finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("Child strace process timed out after 60s")
            }
            val exitCode = process.exitValue()
            val outText = outFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val errText = errFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
            if (exitCode != 0) {
                throw IllegalStateException(
                    "Child JVM failed with exit code $exitCode. Stdout: $outText, Stderr: $errText",
                )
            }
            return if (Files.exists(tempLog)) Files.readString(tempLog) else ""
        } finally {
            Files.deleteIfExists(tempLog)
        }
    }
}
