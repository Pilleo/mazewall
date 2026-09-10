package io.mazewall.profiler.engine

import io.mazewall.core.JavaAgentSelection
import io.mazewall.core.JvmChildProcess
import io.mazewall.core.JvmChildSpec
import io.mazewall.platform.seccomp.SeccompNotification
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Owns a bounded set of persistent reader processes. Only these children execute remote-memory reads.
 * Reactor cancellation kills a child's process without waiting; its worker reaps before replenishing.
 * Closing the pool outside the reactor waits boundedly and reports any incomplete cleanup.
 */
internal class ProfilerPreparationPool(
    parallelism: Int = 4,
    capacity: Int = 64,
    private val launch: () -> Process = ::launchPreparationChild,
) : ProfilerPreparation,
    AutoCloseable {
    private val workers = ThreadPoolExecutor(
        parallelism,
        parallelism,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity),
        { runnable -> Thread(runnable, "profiler-preparation").apply { isDaemon = true } },
    )
    private val children = ConcurrentHashMap.newKeySet<Process>()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    private val localChild = ThreadLocal<Child>()
    private val cleanupFailures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

    override fun submit(
        notification: SeccompNotification,
        syscallName: String,
    ): ProfilerPreparationJob {
        val job = Job(ProfilerPreparationProtocol.Request(notification.copy(args = notification.args.copyOf()), syscallName))
        jobs.add(job)
        try {
            workers.execute(job)
        } catch (failure: RejectedExecutionException) {
            jobs.remove(job)
            job.result.completeExceptionally(failure)
        }
        return job
    }

    override fun close() {
        workers.shutdown()
        jobs.forEach(Job::close)
        children.forEach { it.toHandle().destroyForcibly() }
        try {
            if (!workers.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)) {
                cleanupFailures.add(IOException("Profiler preparation workers did not terminate"))
            }
            children.toList().forEach(::reap)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted closing profiler preparation pool", failure)
        }
        if (cleanupFailures.isNotEmpty()) {
            throw IOException("Profiler preparation cleanup failed").also { primary -> cleanupFailures.forEach(primary::addSuppressed) }
        }
    }

    private inner class Job(
        private val request: ProfilerPreparationProtocol.Request,
    ) : Runnable,
        ProfilerPreparationJob {
        override val result = CompletableFuture<SyscallEvent<SyscallEventState.Resolved>>()
        private val cancellation = java.util.concurrent.CancellationException("Profiler preparation cancelled")
        private var active: Child? = null

        override fun close() {
            synchronized(this) {
                result.completeExceptionally(cancellation)
                // Process.destroyForcibly also closes pipe streams and can wait for a concurrent read.
                // ProcessHandle only signals: the reactor must never join a blocked reader here.
                active?.process?.toHandle()?.destroyForcibly()
            }
            workers.remove(this)
            jobs.remove(this)
        }

        @Suppress("TooGenericExceptionCaught") // Complete the request and retire the process on all failure paths; fatal identity is rethrown.
        override fun run() {
            if (result.isCancelled) return
            var child: Child? = null
            var primary: Throwable? = null
            try {
                child = localChild.get() ?: Child(launch().also(children::add)).also {
                    localChild.set(it)
                }
                synchronized(this) {
                    if (result.isCancelled) {
                        child.process.toHandle().destroyForcibly()
                        return
                    }
                    active = child
                }
                ProfilerPreparationProtocol.writeRequest(child.output, request)
                val event = ProfilerPreparationProtocol.readResponse(child.input, request)
                synchronized(this) {
                    active = null // Stale cancellation cannot kill the next job's process.
                    result.complete(event)
                }
            } catch (failure: Exception) {
                primary = failure
                if (!result.completeExceptionally(failure)) cancellation.addSuppressed(failure)
                child?.process?.toHandle()?.destroyForcibly()
            } catch (failure: Error) {
                primary = failure
                if (!result.completeExceptionally(failure)) cancellation.addSuppressed(failure)
                child?.process?.toHandle()?.destroyForcibly()
                throw failure
            } finally {
                synchronized(this) { active = null }
                if (result.isCancelled || result.isCompletedExceptionally) {
                    localChild.remove()
                    child?.process?.let { reap(it, primary) }
                }
                jobs.remove(this)
            }
        }
    }

    private fun reap(
        process: Process,
        primary: Throwable? = null,
    ) {
        process.toHandle().destroyForcibly()
        try {
            if (!process.waitFor(CLOSE_SECONDS, TimeUnit.SECONDS)) {
                cleanupFailures.add(IOException("Profiler preparation child did not terminate"))
                return
            }
            for (stream in listOf(process.inputStream, process.outputStream, process.errorStream)) {
                try {
                    stream.close()
                } catch (failure: IOException) {
                    // Cancellation may leave buffered request bytes: preserve its failed flush on
                    // the original I/O failure, not as a claim that a reaped child is still alive.
                    if (primary != null) primary.addSuppressed(failure) else cleanupFailures.add(failure)
                }
            }
            children.remove(process)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            cleanupFailures.add(failure)
        } catch (failure: IOException) {
            cleanupFailures.add(failure)
        }
    }

    private class Child(
        val process: Process,
    ) {
        val input = DataInputStream(process.inputStream)
        val output = DataOutputStream(process.outputStream)
    }

    companion object {
        private const val CLOSE_SECONDS = 5L
    }
}

private fun launchPreparationChild(): Process {
    val command = JvmChildProcess.commandLine(JvmChildSpec(ProfilerPreparationMain::class.java.name, javaAgents = JavaAgentSelection.None))
    return ProcessBuilder(command)
        .apply {
        JvmChildProcess.stripInheritedJvmOptions(environment())
        redirectError(ProcessBuilder.Redirect.DISCARD)
    }.start()
}
