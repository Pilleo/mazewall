package io.mazewall.profiler.triage

import java.io.IOException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Owns a diagnostic child process, its streams, and its bounded reaping protocol. */
internal class ManagedProcessCapture private constructor(
    internal val process: Process,
) : AutoCloseable {
    fun readStdout(timeout: Duration): String {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "process-capture").apply { isDaemon = true } }
        val capture = runCatching { captureOutput(executor, timeout) }
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
        executor: java.util.concurrent.ExecutorService,
        timeout: Duration,
    ): String {
        val output = executor.submit(Callable { process.inputStream.bufferedReader().use { it.readText() } })
        try {
            return output.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            output.cancel(true)
            terminateAndReap(timeout)
            throw IOException("Process output capture timed out after ${timeout.toSeconds()}s", failure)
        }
    }

    private fun stopReader(
        executor: java.util.concurrent.ExecutorService,
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
            throw IOException("Process did not exit within ${timeout.toSeconds()}s")
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

        fun start(builder: ProcessBuilder): ManagedProcessCapture = ManagedProcessCapture(builder.start())
    }
}
