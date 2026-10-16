package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Diagnostic process-local procfs read loop; not a containment or performance-promotion gate. */
public object TierENodeNoiseTarget {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1)
        val seconds = args[0].toLongOrNull()?.takeIf { it in 1..30 }
            ?: throw IllegalArgumentException("Noise duration must be 1..30 seconds")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        var iterations = 0L
        while (System.nanoTime() < deadline) {
            Files.newInputStream(Path.of("/proc/self/stat")).use { stream ->
                require(stream.readNBytes(128).isNotEmpty()) { "Procfs stat read failed" }
            }
            iterations++
        }
        println("syscall_loop_iterations=$iterations")
    }
}
