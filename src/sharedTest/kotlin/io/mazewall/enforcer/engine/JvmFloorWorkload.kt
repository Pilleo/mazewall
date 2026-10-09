package io.mazewall.enforcer.engine

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.state.*
import io.mazewall.platform.fd.CleanupFailures
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A synthetic workload designed to trigger all essential JVM subsystems that interact with the OS kernel.
 *
 * This workload is used as a baseline to determine the "JVM Invariant Syscall Floor" — the minimum
 * set of system calls required for a JVM (HotSpot/Graal) to remain functional under pressure.
 *
 * ### Future Improvements:
 * 1. **Self-Profiling Mode:** Integrate with `Profiler.profile` (Tier S) to automatically generate
 *    a `.sbob` baseline file with binary accuracy and JVM stack traces.
 * 2. **Process-Wide Tracing:** Enhance Tier S with `TSYNC` or `clone` tracking to capture
 *    background threads (GC, JIT) which currently bypass per-thread profiling.
 * 3. **Deterministic JIT:** Run with `-Xbatch` and `-XX:CompileThreshold` to ensure
 *    reliable capture of compilation-related syscalls (e.g. `mmap` with `PROT_EXEC`).
 *
 * It exercises:
 * 1. JIT Compiler (C1/C2): Background compilation and executable memory allocation.
 * 2. GC Handshakes: Allocation pressure and forced garbage collection.
 * 3. Loom/Concurrency: Virtual thread scheduling and carrier thread coordination.
 * 4. NIO/Networking: Loading of the native networking stack and selector initialization.
 * 5. OS Thread Lifecycle: Native thread creation, joining, and signaling.
 */
object JvmFloorWorkload {
    private const val JIT_ITERATIONS = 50_000
    private const val GC_ALLOCATION_COUNT = 500
    private const val MB_SIZE = 1024 * 1024
    private const val LOOM_TASK_COUNT = 100
    private const val LOOM_SLEEP_MS = 1L
    private const val OS_THREAD_ITERATIONS = 10
    private const val OS_THREAD_SLEEP_MS = 1L

    /**
     * Executes the stress workload.
     */
    fun run() = run(beforeWork = null)

    /** The test barrier runs after entry validation and before the interruptible workload. */
    fun run(beforeWork: (() -> Unit)?) =
        runWithNetworkProbe(beforeWork) {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 1), 1)
        }
    }

    /** Per-invocation test boundary; the public workload always supplies the real one-ms connect. */
    internal fun runWithNetworkProbe(
        beforeWork: (() -> Unit)?,
        networkProbe: () -> Unit,
    ) = runWithOwners(beforeWork, networkProbe, null)

    /** Observation/barrier callbacks receive only owners actually acquired by this invocation. */
    internal class OwnerBarriers(
        val loomTask: (() -> Unit)? = null,
        val loomAcquired: ((ExecutorService) -> Unit)? = null,
        val workerTask: (() -> Unit)? = null,
        val workerAcquired: ((Thread) -> Unit)? = null,
    )

    internal fun runWithOwners(
        beforeWork: (() -> Unit)?,
        networkProbe: () -> Unit,
        barriers: OwnerBarriers?,
    ) {
        println("[JVM FLOOR] Starting stress workload...")

        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Workload interrupted before start")
        }
        beforeWork?.invoke()

        // 1. JIT Compiler Pressure
        // Force methods to reach the compilation threshold
        val startTime = System.nanoTime()
        var count = 0
        repeat(JIT_ITERATIONS) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("JIT stress interrupted")
            }
            count += "jit-stress-iteration-$it".hashCode()
        }
        val jitDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
        println("[JVM FLOOR] JIT stress completed in ${jitDuration}ms (result: $count)")

        // 2. GC & Memory Pressure
        // Large allocations followed by clear and explicit GC to trigger handshakes.
        // We use a significant chunk of memory to ensure the GC actually has work to do.
        val gcStartTime = System.nanoTime()
        val garbage = mutableListOf<ByteArray>()
        repeat(GC_ALLOCATION_COUNT) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("GC stress interrupted during allocation")
            }
            garbage.add(ByteArray(MB_SIZE)) // 1MB chunks
        }
        // Retain a small amount of memory to prevent the heap from being completely empty
        // which can cause some GCs to skip intensive handshake phases.
        val retainedGarbage = garbage.take(10)
        garbage.clear()

        System.gc() // Trigger safepoints and signal-based handshakes

        // Use the retained garbage to prevent premature optimization/collection
        println("[JVM FLOOR] Retained sample size: ${retainedGarbage.size}")

        val gcDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - gcStartTime)
        println("[JVM FLOOR] GC stress completed in ${gcDuration}ms")

        // 3. Loom & Carrier Thread Coordination
        // Spawning virtual threads that yield to exercise the ForkJoinPool scheduler
        val loomStartTime = System.nanoTime()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        var loomFailure: Throwable? = null
        var loomInterrupted = false
        try {
            val execution = runCatching {
            val tasks = (1..LOOM_TASK_COUNT).map { id ->
                executor.submit(
                    Callable {
                    if (Thread.currentThread().isInterrupted) {
                        return@Callable 0
                    }
                    try {
                        barriers?.loomTask?.invoke()
                        Thread.sleep(LOOM_SLEEP_MS) // Workload delay: exercise virtual-thread parking and carrier coordination.
                        Thread.yield() // Force carrier thread switch
                        id * id
                    } catch (e: InterruptedException) {
                        System.err.println("[JVM FLOOR] Loom task cancelled: $e")
                        Thread.currentThread().interrupt() // restore status
                        0
                    }
                },
                )
            }
            barriers?.loomAcquired?.invoke(executor)
            for (task in tasks) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Loom stress interrupted while waiting for tasks")
                }
                task.get()
            }
            }
            execution.exceptionOrNull()?.let { caught ->
                loomFailure = caught
                if (caught is InterruptedException) loomInterrupted = true
            }
        } finally {
            try {
                loomInterrupted = Thread.interrupted() || loomInterrupted
                val cleanup = runCatching { terminateExecutor(executor, cancel = loomFailure != null || loomInterrupted) }
                cleanup.exceptionOrNull()?.let { loomFailure = CleanupFailures.merge(loomFailure, it) }
            } finally {
                loomInterrupted = Thread.interrupted() || loomInterrupted
                if (loomInterrupted) Thread.currentThread().interrupt()
            }
        }
        loomFailure?.let { throw it }
        val loomDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loomStartTime)
        println("[JVM FLOOR] Loom stress completed in ${loomDuration}ms")

        // 4. NIO & Native Networking Warmup
        // Ensure classes like epoll/poll/selector and socket are loaded and initialized
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("NIO warmup interrupted before start")
        }
        java.nio.channels.Selector.open().use { selector ->
            try {
                networkProbe()
            } catch (e: SocketTimeoutException) {
                System.err.println("[JVM FLOOR] Expected loopback connection timeout: $e")
            } catch (e: InterruptedIOException) {
                Thread.currentThread().interrupt()
                throw InterruptedException("NIO warmup interrupted").apply { initCause(e) }
            } catch (e: ConnectException) {
                // Accept the expected refused loopback connection, not arbitrary
                // IO failures (in particular EPERM/EACCES from containment).
                if (e.message != "Connection refused") throw e
                System.err.println("[JVM FLOOR] Expected loopback connection refusal: $e")
            }
        }
        println("[JVM FLOOR] NIO warmup completed")

        // 5. OS Thread Coordination
        // Standard OS thread lifecycle
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("OS Thread coordination interrupted before start")
        }
        val workerOutcome = java.util.concurrent.atomic
            .AtomicReference<Result<Unit>>()
        val thread = Thread {
            val execution = runCatching {
            try {
                barriers?.workerTask?.invoke()
                repeat(OS_THREAD_ITERATIONS) {
                    if (Thread.currentThread().isInterrupted) {
                        return@runCatching
                    }
                    Thread.sleep(OS_THREAD_SLEEP_MS) // Workload delay: exercise OS-thread sleep, wakeup, and join syscalls.
                }
            } catch (e: InterruptedException) {
                System.err.println("[JVM FLOOR] OS worker cancelled: $e")
                Thread.currentThread().interrupt()
            }
            }
            workerOutcome.set(execution)
        }
        thread.start()
        var workerFailure: Throwable? = null
        var workerInterrupted = false
        try {
            val joining = runCatching {
                barriers?.workerAcquired?.invoke(thread)
                thread.join()
            }
            joining.exceptionOrNull()?.let { caught ->
                workerFailure = caught
                if (caught is InterruptedException) workerInterrupted = true
            }
        } finally {
            try {
                workerInterrupted = Thread.interrupted() || workerInterrupted
                val cleanup = runCatching { terminateWorker(thread) }
                cleanup.exceptionOrNull()?.let { workerFailure = CleanupFailures.merge(workerFailure, it) }
            } finally {
                workerInterrupted = Thread.interrupted() || workerInterrupted
                if (workerInterrupted) Thread.currentThread().interrupt()
            }
        }
        workerOutcome.get()?.exceptionOrNull()?.let { workerFailure = CleanupFailures.merge(workerFailure, it) }
        workerFailure?.let { throw it }
        println("[JVM FLOOR] OS Thread coordination completed")

        println("[JVM FLOOR] Workload successfully finished.")
    }

    /** All termination observations share the original five-second cleanup budget. */
    private fun terminateExecutor(
        executor: ExecutorService,
        cancel: Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var failure: Throwable? = null
        var interrupted = Thread.interrupted()
        var forced = false

        fun attempt(action: () -> Unit) {
            val outcome = runCatching(action)
            outcome.exceptionOrNull()?.let { caught ->
                if (caught is InterruptedException) interrupted = true
                failure = CleanupFailures.merge(failure, caught)
            }
            interrupted = Thread.interrupted() || interrupted
        }
        try {
            attempt { executor.shutdown() }
            if (cancel || failure != null) {
                attempt { executor.shutdownNow() }
                forced = true
            }
            var terminated = executor.isTerminated
            while (!terminated) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                attempt { terminated = executor.awaitTermination(remaining, TimeUnit.NANOSECONDS) }
                if (!terminated && !forced) {
                    attempt { executor.shutdownNow() }
                    forced = true
                }
            }
            if (!terminated) {
                if (!forced) attempt { executor.shutdownNow() }
                attempt { check(executor.isTerminated) { "Loom executor cleanup could not confirm physical termination" } }
            }
            failure?.let { throw it }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Interruption never substitutes for the original bounded physical join. */
    private fun terminateWorker(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000)
        var failure: Throwable? = null
        var interrupted = Thread.interrupted()
        val signalling = runCatching { if (thread.isAlive) thread.interrupt() }
        signalling.exceptionOrNull()?.let { failure = CleanupFailures.merge(failure, it) }
        try {
            while (thread.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                val joining = runCatching { TimeUnit.NANOSECONDS.timedJoin(thread, remaining) }
                joining.exceptionOrNull()?.let { caught ->
                    if (caught is InterruptedException) interrupted = true
                    failure = CleanupFailures.merge(failure, caught)
                }
                interrupted = Thread.interrupted() || interrupted
            }
            if (thread.isAlive) failure = CleanupFailures.merge(failure, IllegalStateException("OS worker cleanup could not confirm physical termination"))
            failure?.let { throw it }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        run()
    }
}
