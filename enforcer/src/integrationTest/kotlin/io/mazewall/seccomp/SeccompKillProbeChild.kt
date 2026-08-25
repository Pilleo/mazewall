package io.mazewall.seccomp

import io.mazewall.LinuxNative

/**
 * Child-process entry point for KILL_* verdict probes (issue-20260823-172000).
 *
 * Runs inside a fresh JVM spawned by [io.mazewall.IsolatedProcessTester]. Installs a seccomp
 * filter whose matched action is SECCOMP_RET_KILL_THREAD, then deliberately triggers the killed
 * syscall. A surviving process prints PROBE_OK (the failure signal the parent asserts against);
 * a correctly enforced filter terminates this JVM with SIGSYS before the marker is printed.
 *
 * Raw POSIX fork() was rejected for this purpose: a forked child of a multithreaded JVM may only
 * execute async-signal-safe code until exec, and installing filters / running probes before exec
 * is undefined behavior with FFM and JIT threads.
 */
object SeccompKillProbeChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val arch = io.mazewall.core.Arch.current()
        val victimNr = args[0].toInt()

        val policy = io.mazewall.Policy.builder()
            .defaultAction(io.mazewall.core.SeccompAction.ACT_ALLOW)
            .addAction(io.mazewall.core.SeccompAction.ACT_KILL_THREAD, io.mazewall.core.Syscall.entries.first { it.numberFor(arch) == victimNr })
            .build()

        // Thread-scoped, non-supervised: no daemon involvement.
        io.mazewall.enforcer.api.ContainedExecutors.installOnCurrentThread(policy)

        // Post-install liveness: benign syscall must succeed (issue-20260823-171500).
        val pid = ProcessHandle.current().pid()
        check(pid > 0) { "Liveness probe failed after install" }

        // Deliberately trigger the killed syscall. If we survive, enforcement failed.
        val res = LinuxNative.raw.syscall(
            victimNr.toLong(),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
            io.mazewall.core.NativeArg.LongArg(0),
        )
        println("PROBE_SURVIVED res=$res")
        println("PROBE_OK")
    }
}
