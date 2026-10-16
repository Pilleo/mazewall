package io.mazewall.seccomp

import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.process.ChildStdoutPump
import io.mazewall.platform.process.JavaAgentSelection
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import io.mazewall.platform.process.RealProcessLauncher
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The external owner retains the acquired JVM, not just its Future or contained worker. */
internal object ProxyDiagnosticJvm {
    private const val OWNER_SECONDS = 115L
    private const val REAP_RESERVE_SECONDS = 5L
    private const val CHILD_MILLIS = 105_000L
    private const val BARRIER_SECONDS = 10L
    private const val REAP_SECONDS = 2L
    private const val POLL_MILLIS = 10L
    private const val STUCK_WAIT_MILLIS = 100L

    data class Outcome(
        val failure: Throwable?,
        val lines: List<String>,
        val process: Process,
        val stdoutThread: Thread?,
        val retirementRemainingNanos: Long,
    )

    fun run(
        mode: String,
        vararg args: String,
        deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(OWNER_SECONDS),
    ): Outcome {
        // Same disposable acquired-JVM pattern as BpfHardeningTest; launch/reap/pump
        // all fit the original enclosing 120s budget, including dispatch overhead.
        val waitDeadline = deadline - TimeUnit.SECONDS.toNanos(REAP_RESERVE_SECONDS)
        val spec = JvmChildSpec(
            mainClass = SupervisorProxyIntegrationTest::class.java.name,
            mainArgs = listOf(mode, CHILD_MILLIS.toString(), *args),
            maxHeap = "128m",
            extraJvmArgs = listOf("-Djdk.lang.Process.launchMechanism=FORK"),
            javaAgents = JavaAgentSelection.None,
        )
        val command = JvmChildProcess.commandLine(spec)
        val lifecycle = OwnedLifecycle(deadline)
        val lines = CopyOnWriteArrayList<String>()
        val diagnosed = CountDownLatch(1)
        var pump: ChildStdoutPump? = null
        var verified = false
        var failure: Throwable? = null
        var interrupted = Thread.interrupted()
        var retirementRemainingNanos = 0L
        val process = runCatching { JvmChildProcess.start(RealProcessLauncher, spec) }.getOrElse {
            if (interrupted) Thread.currentThread().interrupt()
            throw it
        }
        try {
            val execution = runCatching {
                println("PROXY_JVM_ACQUIRED pid=${process.pid()} mode=$mode")
                val output = JvmChildProcess.startStdoutPump(process, "PROXY_NATIVE_WORKER_BLOCKED", { line ->
                    lines.add(line)
                    println(line)
                    if (line.startsWith("PROXY_DEADLINE_OUTCOME")) diagnosed.countDown()
                }, "proxy-owned-jvm-output")
                pump = output
                if (mode == "stuck") {
                    val readinessSeconds = TimeUnit.NANOSECONDS.toSeconds(remaining(waitDeadline)).coerceAtMost(BARRIER_SECONDS)
                    check(JvmChildProcess.awaitReady(output, readinessSeconds)) { "Native worker did not reach a real blocking read" }
                    diagnosed.await(minOf(remaining(waitDeadline), TimeUnit.SECONDS.toNanos(BARRIER_SECONDS)), TimeUnit.NANOSECONDS)
                }
                val wait = if (mode == "stuck") minOf(remaining(waitDeadline), TimeUnit.MILLISECONDS.toNanos(STUCK_WAIT_MILLIS)) else remaining(waitDeadline)
                verified = true
                IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ofNanos(wait), command, lifecycle)
            }
            failure = execution.exceptionOrNull()
            if (failure is InterruptedException) interrupted = true
        } finally {
            retirementRemainingNanos = remaining(deadline)
            interrupted = Thread.interrupted() || interrupted
            if (!verified) {
                runCatching { IsolatedProcessTester.verifyLifecycleForTesting(process, Duration.ZERO, command, lifecycle) }
                    .exceptionOrNull()
                    ?.let { failure = CleanupFailures.merge(failure, it) }
            }
            runCatching {
                check(!process.isAlive) { "Owned proxy JVM retirement incomplete" }
                val exit = process.exitValue()
                println("PROXY_JVM_REAPED pid=${process.pid()} exit=$exit descendantsLive=${lifecycle.liveDescendants()}")
                check(lifecycle.liveDescendants().isEmpty()) { "Owned proxy descendant retirement incomplete" }
            }.exceptionOrNull()?.let { failure = CleanupFailures.merge(failure, it) }
            failure = retireOutput(pump, deadline, failure)
            interrupted = Thread.interrupted() || interrupted
            if (interrupted) Thread.currentThread().interrupt()
        }
        return Outcome(failure, lines.toList(), process, pump?.thread, retirementRemainingNanos)
    }

    private fun retireOutput(
        pump: ChildStdoutPump?,
        deadline: Long,
        primary: Throwable?,
    ): Throwable? {
        var failure = primary
        var interrupted = Thread.interrupted()
        val thread = pump?.thread
        try {
            while (thread?.isAlive == true && remaining(deadline) > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining(deadline))
                } catch (caught: InterruptedException) {
                    interrupted = true
                    failure = CleanupFailures.merge(failure, caught)
                }
            }
            if (thread?.isAlive == true) failure = CleanupFailures.merge(failure, IllegalStateException("Owned proxy stdout physical retirement incomplete"))
            println("PROXY_STDOUT_RETIRED alive=${thread?.isAlive == true}")
            return failure
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(0)

    private class OwnedLifecycle(
        private val deadline: Long,
    ) : IsolatedProcessTester.ProcessLifecycleOperations {
        private val observed = linkedMapOf<Long, ProcessHandle>()

        override fun waitFor(
            process: Process,
            timeout: Duration,
        ): Boolean {
            val waitDeadline = minOf(deadline, System.nanoTime() + timeout.toNanos())
            do {
                descendants(process)
                val wait = remaining(waitDeadline)
                if (wait == 0L) return !process.isAlive
                if (process.waitFor(minOf(wait, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)), TimeUnit.NANOSECONDS)) {
                    descendants(process)
                    return true
                }
            } while (true)
        }

        override fun descendants(process: Process): List<ProcessHandle> {
            process.descendants().use { handles -> handles.forEach { observed[it.pid()] = it } }
            observed.values.toList().forEach { child ->
                child.descendants().use { handles -> handles.forEach { observed[it.pid()] = it } }
            }
            return observed.values.toList().asReversed()
        }

        fun liveDescendants(): List<Long> = observed.values.filter(ProcessHandle::isAlive).map(ProcessHandle::pid)

        override fun destroy(process: Process) {
            process.destroy()
        }

        override fun destroy(handle: ProcessHandle) {
            handle.destroy()
        }

        override fun destroyForcibly(process: Process) {
            process.destroyForcibly()
        }

        override fun destroyForcibly(handle: ProcessHandle) {
            handle.destroyForcibly()
        }

        override fun awaitExit(
            process: Process,
            descendants: List<ProcessHandle>,
        ): Boolean {
            val reapDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(REAP_SECONDS))
            var interrupted = Thread.interrupted()
            var failure: Throwable? = null
            try {
                while ((process.isAlive || descendants.any(ProcessHandle::isAlive)) && remaining(reapDeadline) > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(minOf(remaining(reapDeadline), TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)))
                    } catch (caught: InterruptedException) {
                        interrupted = true
                        failure = CleanupFailures.merge(failure, caught)
                    }
                }
                failure?.let { throw it }
                return !process.isAlive && descendants.none(ProcessHandle::isAlive)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
