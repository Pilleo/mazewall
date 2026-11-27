package io.mazewall.profiler.triage

import java.io.IOException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns a diagnostic child process, its streams, and its bounded reaping protocol. */
internal class ManagedProcessCapture private constructor(
    internal val process: Process,
) : AutoCloseable {
    data class Output(
        val stdout: String,
        val stderr: String,
    )

    fun readStdout(timeout: Duration): String = readStreams(timeout, Callable(::readStdoutToEnd)).single()

    fun readOutput(timeout: Duration): Output {
        val (stdout, stderr) = readStreams(timeout, Callable(::readStdoutToEnd), Callable(::readStderr))
        return Output(stdout, stderr)
    }

    private fun readStreams(
        timeout: Duration,
        vararg readers: Callable<String>,
    ): List<String> {
        val executor = Executors.newFixedThreadPool(readers.size) { runnable -> Thread(runnable, "process-capture").apply { isDaemon = true } }
        val capture = runCatching { captureOutput(executor, timeout, readers) }
        val cleanupFailure = stopReader(executor, timeout)
        return capture.fold(
            onSuccess = { output ->
                cleanupFailure?.let { throw it }
                output
            },
            onFailure = { failure ->
                cleanupFailure?.let(failure::addSuppressed)
                throw failure
            },
        )
    }

    private fun captureOutput(
        executor: ExecutorService,
        timeout: Duration,
        readers: Array<out Callable<String>>,
    ): List<String> {
        val start = System.nanoTime()
        val completed = ExecutorCompletionService<IndexedValue<String>>(executor)
        readers.forEachIndexed { index, read -> completed.submit { IndexedValue(index, read.call()) } }
        val outputs = Array(readers.size) { "" }
        try {
            repeat(readers.size) {
                val remaining = timeout.toNanos() - (System.nanoTime() - start)
                val future = completed.poll(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                if (future == null) {
                    terminateAndReap(timeout)
                    throw IOException("Process output capture timed out after ${timeout.toMillis()} ms")
                }
                val (index, output) = future.get()
                outputs[index] = output
            }
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
        return outputs.asList()
    }

    private fun readStdoutToEnd(): String = process.inputStream.bufferedReader().use { it.readText() }

    private fun readStderr(): String =
        process.errorStream.bufferedReader().use { reader ->
            val prefix = StringBuilder()
            val buffer = CharArray(MAX_STDERR_CHARACTERS)
            var truncated = false
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                val keep = minOf(read, MAX_STDERR_CHARACTERS - prefix.length)
                prefix.append(buffer, 0, keep)
                if (keep < read) truncated = true
            }
            if (truncated) prefix.append(" [truncated]")
            prefix.toString()
        }

    private fun stopReader(
        executor: ExecutorService,
        timeout: Duration,
    ): IOException? {
        executor.shutdownNow()
        return try {
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                null
            } else {
                IOException("Process output reader did not stop after ${timeout.toSeconds()}s")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            IOException("Interrupted while stopping process output reader", interrupted)
        }
    }

    fun awaitExit(timeout: Duration): Int {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            terminateAndReap(timeout)
            throw IOException("Process did not exit within ${timeout.toMillis()} ms")
        }
        return process.exitValue()
    }

    override fun close() {
        var failure: IOException? = null
        try {
            closeStreams()
        } catch (closeFailure: IOException) {
            failure = closeFailure
        }
        try {
            if (process.isAlive) terminateAndReap(Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS))
        } catch (reapFailure: IOException) {
            if (failure == null) failure = reapFailure else failure.addSuppressed(reapFailure)
        }
        failure?.let { throw it }
    }

    private fun terminateAndReap(timeout: Duration) {
        process.destroyForcibly()
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw IOException("Process did not terminate within ${timeout.toSeconds()}s")
        }
    }

    private fun closeStreams() {
        var failure: IOException? = null
        listOf(process.outputStream, process.inputStream, process.errorStream).forEach { stream ->
            try {
                stream.close()
            } catch (closeFailure: IOException) {
                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
    }

    internal companion object {
        private const val CLOSE_TIMEOUT_SECONDS = 5L
        private const val MAX_STDERR_CHARACTERS = 2048

        fun start(builder: ProcessBuilder): ManagedProcessCapture = ManagedProcessCapture(builder.start())
    }
}
