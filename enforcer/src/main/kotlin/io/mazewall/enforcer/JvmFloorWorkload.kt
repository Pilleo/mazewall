package io.mazewall.enforcer

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A synthetic workload designed to trigger all essential JVM subsystems that interact with the OS kernel.
 *
 * This workload is used as a baseline to determine the "JVM Invariant Syscall Floor" — the minimum
 * set of system calls required for a JVM (HotSpot/Graal) to remain functional under pressure.
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
    fun run() {
        println("[JVM FLOOR] Starting stress workload...")

        // 1. JIT Compiler Pressure
        // Force methods to reach the compilation threshold
        val startTime = System.nanoTime()
        var count = 0
        repeat(JIT_ITERATIONS) {
            count += "jit-stress-iteration-$it".hashCode()
        }
        val jitDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
        println("[JVM FLOOR] JIT stress completed in ${jitDuration}ms (result: $count)")

        // 2. GC & Memory Pressure
        // Large allocations followed by clear and explicit GC to trigger handshakes
        val gcStartTime = System.nanoTime()
        val garbage = mutableListOf<ByteArray>()
        repeat(GC_ALLOCATION_COUNT) {
            garbage.add(ByteArray(MB_SIZE)) // 1MB chunks
        }
        garbage.clear()
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc() // Trigger safepoints and signal-based handshakes
        val gcDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - gcStartTime)
        println("[JVM FLOOR] GC stress completed in ${gcDuration}ms")

        // 3. Loom & Carrier Thread Coordination
        // Spawning virtual threads that yield to exercise the ForkJoinPool scheduler
        val loomStartTime = System.nanoTime()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val tasks = (1..LOOM_TASK_COUNT).map { id ->
            executor.submit {
                Thread.sleep(LOOM_SLEEP_MS)
                Thread.yield() // Force carrier thread switch
                id * id
            }
        }
        tasks.forEach { it.get() }
        executor.shutdown()
        val loomDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loomStartTime)
        println("[JVM FLOOR] Loom stress completed in ${loomDuration}ms")

        // 4. NIO & Native Networking Warmup
        // Ensure classes like epoll/poll/selector and socket are loaded and initialized
        java.nio.channels.Selector.open().use { selector ->
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", 1), 1)
                }
            } catch (ignored: Exception) {
                // Connection failure is expected and fine, we just want the syscalls
            }
        }
        println("[JVM FLOOR] NIO warmup completed")

        // 5. OS Thread Coordination
        // Standard OS thread lifecycle
        val thread = Thread {
            repeat(OS_THREAD_ITERATIONS) {
                Thread.sleep(OS_THREAD_SLEEP_MS)
            }
        }
        thread.start()
        thread.join()
        println("[JVM FLOOR] OS Thread coordination completed")

        println("[JVM FLOOR] Workload successfully finished.")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        run()
    }
}
