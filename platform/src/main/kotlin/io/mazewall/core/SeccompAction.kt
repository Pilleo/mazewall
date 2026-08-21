package io.mazewall.core


import io.mazewall.ffi.NativeConstants

/**
 * Standard Linux Seccomp-BPF return actions.
 *
 * [priority] is a coarse bucket. Intersection must use [stricter] /
 * [restrictivenessRank], not [nativeCode] (kernel return bits are not ordered
 * by restrictiveness) and not [priority] alone ([ACT_ERRNO] and [ACT_TRACE]
 * previously shared priority 4).
 */
public sealed interface SeccompAction {
    public val priority: Int
    public val nativeCode: Int

    /**
     * Total order for policy intersection. [ACT_ERRNO] outranks [ACT_TRACE].
     * [nativeCode] must not be used as a rank.
     */
    public fun restrictivenessRank(): Int =
        when (this) {
            is ACT_KILL_PROCESS -> 70
            is ACT_KILL_THREAD -> 60
            is ACT_TRAP -> 50
            is ACT_ERRNO -> 41
            is ACT_TRACE -> 40
            is ACT_NOTIFY -> 30
            is ACT_LOG -> 20
            is ACT_ALLOW -> 10
        }

    /** Keeps the more restrictive action; [this] wins ties (first-policy). */
    public fun stricter(other: SeccompAction): SeccompAction =
        if (restrictivenessRank() >= other.restrictivenessRank()) this else other

    /** Immediately terminates the entire process. */
    public data object ACT_KILL_PROCESS : SeccompAction {
        override val priority: Int = 7
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_KILL_PROCESS
    }

    /** Immediately terminates the calling thread. */
    public data object ACT_KILL_THREAD : SeccompAction {
        override val priority: Int = 6
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_KILL_THREAD
    }

    /**
     * Sends a SIGSYS signal to the calling thread (native interception).
     *
     * WARNING: This action relies on the kernel delivering SIGSYS. In environments where
     * native libraries (JNI/FFM) modify thread signal masks (e.g., via `sigprocmask` / `rt_sigprocmask`) or
     * alternate signal stacks (`sigaltstack`), or where standard JVM thread pools reuse
     * threads without resetting POSIX signal masks, SIGSYS delivery can be blocked or
     * delayed indefinitely, or result in an unkillable thread state. This makes ACT_TRAP
     * unreliable under such conditions. Use ACT_KILL_PROCESS or ACT_KILL_THREAD for guaranteed
     * immediate enforcement in environments utilizing arbitrary native libraries.
     */
    public data object ACT_TRAP : SeccompAction {
        override val priority: Int = 5
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_TRAP
    }

    /**
     * Returns [errno] (default EPERM) to the calling thread.
     *
     * There is no companion inhabitant. `Policy.block()` stores [ACT_ERRNO] `()`,
     * so `action is ACT_ERRNO` matches every deny. Do not reintroduce a companion
     * that implements [SeccompAction].
     */
    public data class ACT_ERRNO(public val errno: Int = NativeConstants.EPERM) : SeccompAction {
        override val priority: Int = 4
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_ERRNO
    }

    /** Traces the syscall using ptrace/traceId. */
    public data class ACT_TRACE(public val traceId: Int) : SeccompAction {
        override val priority: Int = 4
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_TRACE
    }

    /** Sends a notification to a userspace supervisor (used by the Profiler). */
    public data object ACT_NOTIFY : SeccompAction {
        override val priority: Int = 3
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_USER_NOTIF
    }

    /** Allows the syscall but logs it via the kernel audit subsystem. */
    public data object ACT_LOG : SeccompAction {
        override val priority: Int = 2
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_LOG
    }

    /** Unconditionally allows the system call. */
    public data object ACT_ALLOW : SeccompAction {
        override val priority: Int = 1
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_ALLOW
    }

    public companion object {
        public fun stricterOf(a: SeccompAction, b: SeccompAction): SeccompAction = a.stricter(b)
    }
}
