package io.mazewall.profiler.strace

import io.mazewall.profiler.BillOfBehavior
import io.mazewall.profiler.TraceableWorkload
import java.io.File

/**
 * Descendant `strace -f` profiler.
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
 *
 * @deprecated Application profiling is [MazewallProfiler.profile] with a lambda.
 * Descendant strace is an internal floor probe, not a workload API.
 */
@Deprecated(
    message = "Use MazewallProfiler.open().use { it.profile { workload() } }. Descendant strace is not operator API.",
    replaceWith = ReplaceWith(
        "MazewallProfiler.open().use { it.profile { /* workload */ } }",
        "io.mazewall.profiler.MazewallProfiler",
    ),
)
object StraceProfiler {
    @Deprecated(
        message = "Use MazewallProfiler.profile { } for application work",
        replaceWith = ReplaceWith(
            "MazewallProfiler.open().use { it.profile { /* workload */ } }",
            "io.mazewall.profiler.MazewallProfiler",
        ),
    )
    fun <T : TraceableWorkload> profile(workloadClass: Class<T>): BillOfBehavior {
        val collector = io.mazewall.profiler.collector.StraceCollector(workloadClass = workloadClass)
        collector.start()
        return collector.use {
            io.mazewall.profiler.compiler.BobCompiler.compileObservations(it.drain().observations)
        }
    }
}
