package io.mazewall.enforcer.engine

import io.mazewall.core.Syscall

/**
 * The minimal ALLOW_LIST syscall set required by ANY policy that permits post-install execution
 * of Java code which may lazily load classes (i.e. practically all JVM workloads).
 *
 * Root cause reference (issue-20260823-190000): bootstrap/jrt random-access class reads use
 * **positional reads** (`pread64`). A floor that allows `READ`+`LSEEK` but not `PREAD64` causes
 * mid-read EPERM on the modules image, which the JDK surfaces as
 * `ClassFormatError: Incompatible magic value <garbage>` instead of a clean IOException —
 * deterministic corruption, not a graceful failure.
 *
 * Operators extending or trimming these sets MUST re-run the differential suite with runtime
 * self-verification enabled (`-Dio.mazewall.selfVerify=true`) before shipping narrower floors.
 */
public object JvmFloorPresets {
    /** Syscalls the JVM needs to lazily read and define classes after containment is active. */
    public val BOOTSTRAP_READ_CLOSURE: Array<Syscall> = arrayOf(
        Syscall.READ,
        Syscall.PREAD64,
        Syscall.LSEEK,
        Syscall.FSTAT,
        Syscall.FSTATAT,
        Syscall.STATX,
        Syscall.CLOSE,
        Syscall.MMAP,
        Syscall.MPROTECT,
        Syscall.MUNMAP,
        Syscall.MADVISE,
        Syscall.BRK,
        Syscall.GETRANDOM,
        Syscall.CLOCK_GETTIME,
    )

    /** Thread coordination, signals, and lifecycle: never block these (see AGENTS.md §1). */
    public val THREAD_COORDINATION_CLOSURE: Array<Syscall> = arrayOf(
        Syscall.FUTEX,
        Syscall.SCHED_YIELD,
        Syscall.RT_SIGACTION,
        Syscall.RT_SIGPROCMASK,
        Syscall.RT_SIGRETURN,
        Syscall.GETTID,
        Syscall.GETPID,
        Syscall.EXIT,
        Syscall.EXIT_GROUP,
        Syscall.WRITE,
        Syscall.PRCTL,
        Syscall.FCNTL,
    )

    /**
     * Socket-state syscalls the JVM networking stack (OIO + NIO) invokes on ANY existing socket,
     * even when connect/bind are policy-managed: NIO checks non-blocking connect status via
     * getsockopt, configures buffers/timeouts via setsockopt, resolves addresses via
     * getsockname/getpeername, and datagram transfer uses recvfrom. Denying these aborts the JVM
     * with SIGABRT (issue-075 problem 2 / jvm-syscall-floor-implementation-findings §1).
     *
     * NOT part of [fullJvmFloor] by default: connection initiation and data transfer remain
     * policy-relevant for NO_NETWORK-style floors. Add this closure only to floors intended to
     * permit socket usage.
     */
    public val NETWORK_STABILITY_FLOOR: Array<Syscall> = arrayOf(
        Syscall.GETSOCKOPT,
        Syscall.SETSOCKOPT,
        Syscall.GETSOCKNAME,
        Syscall.GETPEERNAME,
        Syscall.RECVFROM,
    )

    /**
     * Convenience union: everything a permissive-toward-the-JVM ALLOW_LIST floor needs.
     * Deliberately does NOT include exec/memfd families; socket usage requires additionally
     * allowing [NETWORK_STABILITY_FLOOR] plus the connect/bind/listen/accept/send family.
     */
    public fun fullJvmFloor(): Array<Syscall> =
        BOOTSTRAP_READ_CLOSURE + THREAD_COORDINATION_CLOSURE
}
