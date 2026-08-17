package io.mazewall.profiler.strace

import io.mazewall.profiler.BillOfBehavior
import io.mazewall.profiler.TraceableWorkload
import java.io.File

/**
 * Tier P Profiler: Traces system calls and path accesses of a workload class
 * by running it in a child JVM process wrapped directly under Linux `strace`.
 *
 * This allows safe, high-speed profiling in rootless, unprivileged containers
 * bypassing Yama ptrace scope restrictions.
 *
 * ### ⚠️ Security Warning & TOCTOU Limitations:
 * Strace-based profiling (which relies on Linux `ptrace`) is inherently subject to **Time-of-Check to Time-of-Use (TOCTOU)**
 * security races when resolving pointer-based system call arguments (such as file paths). A concurrent thread in the
 * target process can overwrite memory content at the argument pointer after the tracer processes it but before the
 * kernel actually executes the system call.
 *
 * For robust, race-free, and kernel-enforced filesystem containment, **Landlock LSM** is the preferred and recommended
 * mechanism, as it evaluates and enforces path-based restrictions directly in the kernel space at the inode level,
 * making it completely immune to pointer-dereferencing TOCTOU attacks.
 */
object StraceProfiler {
    fun <T : TraceableWorkload> profile(workloadClass: Class<T>): BillOfBehavior {
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")

        // Create a temporary strace log file
        val tempLog = File.createTempFile("strace_prof_", ".log")
        tempLog.deleteOnExit()

        // Assemble the strace child JVM command
        val cmd = listOf(
            "strace",
            "-f",
            "-e",
            "trace=file,network",
            "-o",
            tempLog.absolutePath,
            javaBin,
            "-cp",
            classpath,
            "io.mazewall.profiler.strace.StraceWorkloadRunner",
            workloadClass.name,
        )

        // Spawn the process
        val pb = ProcessBuilder(cmd)
        val process = pb.start()

        // Wait for the process to finish
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errText = process.errorStream.bufferedReader().readText()
            val outText = process.inputStream.bufferedReader().readText()
            throw IllegalStateException("Child JVM failed with exit code $exitCode. Stdout: $outText, Stderr: $errText")
        }

        val log = if (tempLog.exists()) tempLog.readText() else ""
        tempLog.delete()
        val observations = io.mazewall.profiler.compiler.StraceLogParser.parse(log)
        return io.mazewall.profiler.compiler.BobCompiler.compileObservations(observations)
    }
}
