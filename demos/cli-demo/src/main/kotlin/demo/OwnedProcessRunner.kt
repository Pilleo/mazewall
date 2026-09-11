package demo

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal sealed interface ProcessOutcome {
    val output: String

    data class Completed(val exitCode: Int, override val output: String) : ProcessOutcome

    data class TimedOut(override val output: String) : ProcessOutcome

    data class Interrupted(val cause: InterruptedException, override val output: String) : ProcessOutcome

    data class ExitedNonZero(val exitCode: Int, override val output: String) : ProcessOutcome

    data class InfrastructureFailure(val cause: Throwable, override val output: String) : ProcessOutcome
}

/** Owns a child and its merged output stream until both are terminal. */
internal object OwnedProcessRunner {
    private const val OUTPUT_LIMIT = 64 * 1024
    private val retirementTimeout = Duration.ofSeconds(3)

    fun run(command: List<String>, timeout: Duration): ProcessOutcome {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        val operationDeadline = deadlineAfter(timeout)
        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (failure: Throwable) {
                rethrowFatal(failure)
                return ProcessOutcome.InfrastructureFailure(failure, "")
            }
        var outputReader: ExecutorService? = null
        val output: Future<String>
        try {
            outputReader = Executors.newSingleThreadExecutor(::daemonThread)
            output = outputReader.submit<String> { drainBounded(process.inputStream) }
        } catch (failure: Throwable) {
            outputReader?.shutdownNow()
            terminateTree(process)?.let(failure::addSuppressed)
            rethrowFatal(failure)
            return ProcessOutcome.InfrastructureFailure(failure, "")
        }
        val readerExecutor = checkNotNull(outputReader)

        var interrupted: InterruptedException? = null
        var timedOut = false
        try {
            val remaining = operationDeadline - System.nanoTime()
            timedOut = remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)
        } catch (failure: InterruptedException) {
            interrupted = failure
        }

        var cleanupFailure: Throwable? = null
        if (timedOut || interrupted != null) {
            cleanupFailure = terminateTree(process)
        }
        val captured = finishOutput(process, readerExecutor, output, cleanupFailure)
        cleanupFailure = captured.second

        if (interrupted != null) {
            cleanupFailure?.let(interrupted::addSuppressed)
            Thread.currentThread().interrupt()
            return ProcessOutcome.Interrupted(interrupted, captured.first)
        }
        if (timedOut) {
            return if (cleanupFailure == null) {
                ProcessOutcome.TimedOut(captured.first)
            } else {
                ProcessOutcome.InfrastructureFailure(cleanupFailure, captured.first)
            }
        }
        if (cleanupFailure != null) return ProcessOutcome.InfrastructureFailure(cleanupFailure, captured.first)
        val exitCode = process.exitValue()
        return if (exitCode == 0) {
            ProcessOutcome.Completed(exitCode, captured.first)
        } else {
            ProcessOutcome.ExitedNonZero(exitCode, captured.first)
        }
    }

    private fun terminateTree(process: Process): Throwable? {
        val retirementDeadline = deadlineAfter(retirementTimeout)
        var failure: Throwable? = null
        val descendants = process.toHandle().descendants().toList().asReversed()
        descendants.forEach { child -> failure = attempt(failure) { child.destroy() } }
        failure = attempt(failure) { process.destroy() }
        descendants.forEach { child ->
            if (child.isAlive) failure = attempt(failure) { child.destroyForcibly() }
        }
        if (process.isAlive) failure = attempt(failure) { process.destroyForcibly() }
        failure =
            attempt(failure) {
                val remaining = retirementDeadline - System.nanoTime()
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    throw IOException("child process did not terminate within ${retirementTimeout.toMillis()} ms")
                }
            }
        return failure
    }

    private fun finishOutput(
        process: Process,
        readerExecutor: ExecutorService,
        reader: Future<String>,
        initialFailure: Throwable?,
    ): Pair<String, Throwable?> {
        var failure = initialFailure
        var result = ""
        failure = attempt(failure) { process.outputStream.close() }
        try {
            result = reader.get(retirementTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (caught: ExecutionException) {
            failure = merge(failure, caught.cause ?: caught)
        } catch (caught: TimeoutException) {
            failure = merge(failure, IOException("child output pump did not terminate", caught))
            reader.cancel(true)
        } catch (caught: InterruptedException) {
            failure = merge(failure, caught)
            reader.cancel(true)
            Thread.currentThread().interrupt()
        } catch (caught: Throwable) {
            rethrowFatal(caught)
            failure = merge(failure, caught)
        } finally {
            runCatching { process.inputStream.close() }.exceptionOrNull()?.let { failure = merge(failure, it) }
            runCatching { process.errorStream.close() }.exceptionOrNull()?.let { failure = merge(failure, it) }
            readerExecutor.shutdownNow()
        }
        return result to failure
    }

    private fun drainBounded(input: java.io.InputStream): String {
        val retained = ByteArrayOutputStream(OUTPUT_LIMIT + 32)
        val chunk = ByteArray(8 * 1024)
        var truncated = false
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            val remaining = OUTPUT_LIMIT - retained.size()
            if (remaining > 0) retained.write(chunk, 0, minOf(read, remaining))
            if (read > remaining) truncated = true
        }
        if (truncated) retained.write("\n[output truncated]".toByteArray(Charsets.UTF_8))
        return retained.toString(Charsets.UTF_8)
    }

    private fun deadlineAfter(duration: Duration): Long {
        val now = System.nanoTime()
        val nanos = runCatching { duration.toNanos() }.getOrDefault(Long.MAX_VALUE)
        return if (now > Long.MAX_VALUE - nanos) Long.MAX_VALUE else now + nanos
    }

    private fun attempt(primary: Throwable?, action: () -> Unit): Throwable? =
        try {
            action()
            primary
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            merge(primary, failure)
        }

    private fun merge(primary: Throwable?, secondary: Throwable): Throwable =
        primary?.also { it.addSuppressed(secondary) } ?: secondary

    private fun daemonThread(task: Runnable): Thread = Thread(task, "cli-demo-output-pump").apply { isDaemon = true }

    private fun rethrowFatal(failure: Throwable) {
        if (failure is Error) throw failure
    }
}

internal object OwnedActionRunner {
    private val terminationTimeout = Duration.ofSeconds(5)

    fun <T> run(executor: ExecutorService, submit: () -> Future<T>): T {
        var future: Future<T>? = null
        var primary: Throwable? = null
        var interrupted = false
        try {
            future = submit()
            return future.get()
        } catch (failure: InterruptedException) {
            primary = failure
            interrupted = true
            future?.cancel(true)
            throw failure
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            val cleanupFailure = retire(executor, interrupted)
            if (interrupted || cleanupFailure is InterruptedException) Thread.currentThread().interrupt()
            if (cleanupFailure != null) {
                if (primary == null) throw cleanupFailure
                primary.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun retire(executor: ExecutorService, force: Boolean): Throwable? {
        if (force) executor.shutdownNow() else executor.shutdown()
        return try {
            if (!executor.awaitTermination(terminationTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
                if (!executor.awaitTermination(terminationTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    IOException("demo worker did not terminate within ${terminationTimeout.toMillis()} ms")
                } else {
                    null
                }
            } else {
                null
            }
        } catch (failure: InterruptedException) {
            executor.shutdownNow()
            failure
        }
    }
}
