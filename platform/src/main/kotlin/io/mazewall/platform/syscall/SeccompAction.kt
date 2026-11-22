package io.mazewall.platform.syscall

import io.mazewall.ffi.NativeConstants

/**
 * Standard Linux Seccomp-BPF return actions.
 *
 * Linux stacks seccomp filters using the signed minimum of the action-masked
 * 32-bit return word (`ret & SECCOMP_RET_ACTION_FULL`): `ACT_KILL_PROCESS`
 * (`0x80000000`) > `ACT_KILL_THREAD` (`0x00000000`) > `ACT_TRAP` (`0x00030000`)
 * > `ACT_ERRNO` (`0x00050000`) > `ACT_NOTIFY` (`0x7fc00000`) > `ACT_TRACE`
 * (`0x7ff00000`) > `ACT_LOG` (`0x7ffc0000`) > `ACT_ALLOW` (`0x7fff0000`).
 *
 * [priority] is a legacy coarse bucket (`ACT_ERRNO` and `ACT_TRACE` share bucket
 * 4 while `ACT_NOTIFY` is 3). Policy intersection must use [stricter] /
 * [restrictivenessRank], not [priority] alone, unsigned [nativeCode] comparisons
 * (which invert `ACT_KILL_PROCESS`'s sign bit), or payload-bearing
 * [toKernelReturnCode] comparisons.
 */
public sealed interface SeccompAction {
    public val priority: Int
    public val nativeCode: Int

    /**
     * Canonical total order for policy intersection matching Linux UAPI
     * `SECCOMP_RET_*` precedence (where signed minimum action bits win).
     * Neither [priority] nor payload-bearing return words define this order.
     */
    public fun restrictivenessRank(): Int =
        when (this) {
            is ACT_KILL_PROCESS -> 70
            is ACT_KILL_THREAD -> 60
            is ACT_TRAP -> 50
            is ACT_ERRNO -> 41
            is ACT_NOTIFY -> 40
            is ACT_TRACE -> 30
            is ACT_LOG -> 20
            is ACT_ALLOW -> 10
        }

    /** Keeps the more restrictive action; [this] wins ties (first-policy). */
    public fun stricter(other: SeccompAction): SeccompAction = if (restrictivenessRank() >= other.restrictivenessRank()) this else other

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
    public data class ACT_ERRNO(
        public val errno: Int = NativeConstants.EPERM,
    ) : SeccompAction {
        init {
            require(errno in 1..MAX_ACTION_DATA) { "Seccomp errno must be in 1..65535, got $errno" }
        }

        override val priority: Int = 4
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_ERRNO
    }

    /** Traces the syscall using ptrace/traceId. */
    public data class ACT_TRACE(
        public val traceId: Int,
    ) : SeccompAction {
        init {
            require(traceId in 0..MAX_ACTION_DATA) { "Seccomp trace ID must be in 0..65535, got $traceId" }
        }

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

    /**
     * Returns the kernel return code for this action.
     * [ACT_ERRNO] and [ACT_TRACE] retain their validated 16-bit payloads.
     * Other actions return [nativeCode] directly.
     *
     * This is used for union-aware self-verification (issue-20260824-011900).
     */
    public fun toKernelReturnCode(): Int =
        when (this) {
            is ACT_ERRNO -> nativeCode or errno
            is ACT_TRACE -> nativeCode or traceId
            is ACT_KILL_PROCESS,
            is ACT_KILL_THREAD,
            is ACT_TRAP,
            is ACT_NOTIFY,
            is ACT_LOG,
            is ACT_ALLOW,
            -> nativeCode
        }

    public companion object {
        public fun stricterOf(
            a: SeccompAction,
            b: SeccompAction,
        ): SeccompAction = a.stricter(b)
    }
}

private const val MAX_ACTION_DATA = 0xffff
