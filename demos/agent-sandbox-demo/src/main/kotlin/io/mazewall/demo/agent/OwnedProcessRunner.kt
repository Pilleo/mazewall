package io.mazewall.demo.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal sealed interface ProcessOutcome {
    val output: String

    data class Completed(override val output: String) : ProcessOutcome

    data class TimedOut(override val output: String) : ProcessOutcome

    data class Interrupted(
        val cause: InterruptedException,
        override val output: String,
    ) : ProcessOutcome

    data class ExitedNonZero(
        val exitCode: Int,
        override val output: String,
    ) : ProcessOutcome

    data class InfrastructureFailure(
        val cause: Throwable,
        override val output: String,
    ) : ProcessOutcome
}

/** Owns a child, every observed descendant, and a bounded merged diagnostic stream. */
internal object OwnedProcessRunner {
    private const val OUTPUT_LIMIT = 64 * 1024
    private const val POLL_MILLIS = 10L
    private val retirementTimeout = Duration.ofSeconds(3)

    fun run(
        command: List<String>,
        timeout: Duration,
    ): ProcessOutcome {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        if (Thread.currentThread().isInterrupted) {
            return ProcessOutcome.Interrupted(InterruptedException("process owner was already interrupted"), "")
        }

        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (failure: Throwable) {
                rethrowFatal(failure)
                return ProcessOutcome.InfrastructureFailure(failure, "")
            }
        val pumpExecutor = Executors.newSingleThreadExecutor(::daemonPumpThread)
        val output = pumpExecutor.submit<String> { drainBounded(process.inputStream) }
        val observed = linkedMapOf<Long, ProcessHandle>()
        val operationDeadline = deadlineAfter(timeout)
        var interrupted: InterruptedException? = null
        var timedOut = false
        var exitCode: Int? = null
        var cleanupFailure: Throwable? = null

        try {
            process.outputStream.close()
            while (true) {
                observeDescendants(process.toHandle(), observed)
                val remaining = operationDeadline - System.nanoTime()
                if (remaining <= 0) {
                    timedOut = true
                    break
                }
                if (process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)), TimeUnit.NANOSECONDS)) {
                    observeDescendants(process.toHandle(), observed)
                    exitCode = process.exitValue()
                    break
                }
            }
        } catch (failure: InterruptedException) {
            interrupted = failure
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            cleanupFailure = failure
        }

        val retirement = terminateTree(process, observed)
        cleanupFailure = mergeNullable(cleanupFailure, retirement.failure)
        if (retirement.interrupted && interrupted == null) {
            interrupted = InterruptedException("interrupted while retiring child process tree")
        }

        val captured = finishOutput(process, pumpExecutor, output)
        cleanupFailure = mergeNullable(cleanupFailure, captured.failure)
        if (captured.interrupted && interrupted == null) {
            interrupted = InterruptedException("interrupted while retiring child output pump")
        }

        interrupted?.let { interruption ->
            cleanupFailure?.let(interruption::addSuppressed)
            Thread.currentThread().interrupt()
            return ProcessOutcome.Interrupted(interruption, captured.output)
        }
        cleanupFailure?.let { return ProcessOutcome.InfrastructureFailure(it, captured.output) }
        if (timedOut) return ProcessOutcome.TimedOut(captured.output)
        return if (exitCode == 0) {
            ProcessOutcome.Completed(captured.output)
        } else {
            ProcessOutcome.ExitedNonZero(exitCode ?: -1, captured.output)
        }
    }

    private fun observeDescendants(
        parent: ProcessHandle,
        observed: MutableMap<Long, ProcessHandle>,
    ) {
        parent.descendants().use { descendants ->
            descendants.forEach { observed[it.pid()] = it }
        }
        observed.values.toList().forEach { known ->
            known.descendants().use { descendants ->
                descendants.forEach { observed[it.pid()] = it }
            }
        }
    }

    private fun terminateTree(
        process: Process,
        observed: MutableMap<Long, ProcessHandle>,
    ): Retirement {
        val deadline = deadlineAfter(retirementTimeout)
        val gracefulDeadline =
            minOf(deadline, deadlineAfter(Duration.ofMillis(250)))
        var failure: Throwable? = null
        var interrupted = false

        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (caught: Throwable) {
                rethrowFatal(caught)
                failure = mergeNullable(failure, caught)
            }
        }

        attempt { observeDescendants(process.toHandle(), observed) }
        observed.values.toList().asReversed().forEach { child ->
            if (child.isAlive) attempt { child.destroy() }
        }
        if (process.isAlive) attempt(process::destroy)

        while (treeIsAlive(process, observed) && System.nanoTime() < gracefulDeadline) {
            attempt { observeDescendants(process.toHandle(), observed) }
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (treeIsAlive(process, observed)) {
            observed.values.toList().asReversed().forEach { child ->
                if (child.isAlive) attempt { child.destroyForcibly() }
            }
            if (process.isAlive) attempt(process::destroyForcibly)
            while (treeIsAlive(process, observed) && System.nanoTime() < deadline) {
                try {
                    TimeUnit.MILLISECONDS.sleep(POLL_MILLIS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (treeIsAlive(process, observed)) {
            failure = mergeNullable(failure, IOException("child process tree did not terminate within ${retirementTimeout.toMillis()} ms"))
        }
        return Retirement(failure, interrupted)
    }

    private fun treeIsAlive(
        process: Process,
        observed: Map<Long, ProcessHandle>,
    ): Boolean = process.isAlive || observed.values.any(ProcessHandle::isAlive)

    private fun finishOutput(
        process: Process,
        pumpExecutor: java.util.concurrent.ExecutorService,
        output: Future<String>,
    ): CapturedOutput {
        var failure: Throwable? = null
        var interrupted = false
        var captured = ""
        try {
            captured = output.get(retirementTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (caught: InterruptedException) {
            interrupted = true
            output.cancel(true)
        } catch (caught: ExecutionException) {
            failure = caught.cause ?: caught
        } catch (caught: TimeoutException) {
            failure = IOException("child output pump did not terminate", caught)
            output.cancel(true)
        } catch (caught: Throwable) {
            rethrowFatal(caught)
            failure = caught
        } finally {
            runCatching { process.inputStream.close() }.exceptionOrNull()?.let { failure = mergeNullable(failure, it) }
            runCatching { process.errorStream.close() }.exceptionOrNull()?.let { failure = mergeNullable(failure, it) }
            pumpExecutor.shutdownNow()
        }
        return CapturedOutput(captured, failure, interrupted)
    }

    private fun drainBounded(input: InputStream): String {
        val retained = ByteArrayOutputStream(OUTPUT_LIMIT + 32)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var truncated = false
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                val writable = minOf(count, OUTPUT_LIMIT - retained.size())
                if (writable > 0) retained.write(buffer, 0, writable)
                if (writable < count) truncated = true
            }
        }
        if (truncated) retained.write("\n[output truncated]".toByteArray(Charsets.UTF_8))
        return retained.toString(Charsets.UTF_8)
    }

    private fun deadlineAfter(duration: Duration): Long {
        val now = System.nanoTime()
        val nanos = runCatching(duration::toNanos).getOrDefault(Long.MAX_VALUE)
        return if (nanos >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + nanos
    }

    private fun mergeNullable(
        primary: Throwable?,
        secondary: Throwable?,
    ): Throwable? {
        if (secondary == null) return primary
        return primary?.also { if (it !== secondary) it.addSuppressed(secondary) } ?: secondary
    }

    private fun daemonPumpThread(task: Runnable): Thread =
        Thread(task, "agent-demo-process-output").apply { isDaemon = true }

    private fun rethrowFatal(failure: Throwable) {
        if (failure is Error) throw failure
    }

    private data class Retirement(val failure: Throwable?, val interrupted: Boolean)

    private data class CapturedOutput(
        val output: String,
        val failure: Throwable?,
        val interrupted: Boolean,
    )
}
