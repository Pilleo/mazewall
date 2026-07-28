package io.mazewall.profiler.engine

/**
 * Main entry point for the Profiler Daemon process.
 *
 * ### ⚠️ Security Warning & TOCTOU Limitations:
 * The USER_NOTIF Tier S Profiler (which this Profiler Daemon participates in) is inherently
 * vulnerable to concurrent memory mutation (TOCTOU / Time-of-Check to Time-of-Use) when resolving
 * pointer-based system call arguments (such as file paths) using `process_vm_readv`.
 *
 * This daemon and the associated profiling mechanism are strictly intended for profiling
 * trusted/benign workloads, not for intercepting or preventing malicious evasion attempts.
 * For robust, race-free, and kernel-enforced filesystem containment, **Landlock LSM** is the
 * preferred and recommended mechanism, as it evaluates and enforces path-based restrictions
 * directly in the kernel space at the inode level, making it completely immune to pointer-dereferencing TOCTOU attacks.
 */
object ProfilerDaemon {
    internal var runner: DaemonRunner = RealDaemonRunner()

    @JvmStatic
    fun main(args: Array<String>) {
        runner.runDaemon(args)
    }
}
