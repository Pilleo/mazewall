package io.mazewall.core

import io.mazewall.ffi.NativeConstants

/**
 * Standard Linux Seccomp-BPF return actions.
 *
 * The priority is used when combining multiple policies. Higher priority
 * actions (more restrictive) will override lower priority ones for the same syscall.
 */
public enum class SeccompAction(
    public val priority: Int,
    public val nativeCode: Int,
) {
    /** Immediately terminates the entire process. */
    ACT_KILL_PROCESS(7, NativeConstants.SECCOMP_RET_KILL_PROCESS),

    /** Immediately terminates the calling thread. */
    ACT_KILL_THREAD(6, NativeConstants.SECCOMP_RET_KILL_THREAD),

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
    ACT_TRAP(5, NativeConstants.SECCOMP_RET_TRAP),

    /** Returns EPERM (or ENOSYS for clone3) to the calling thread. */
    ACT_ERRNO(4, NativeConstants.SECCOMP_RET_ERRNO),

    /** Sends a notification to a userspace supervisor (used by the Profiler). */
    ACT_NOTIFY(3, NativeConstants.SECCOMP_RET_USER_NOTIF),

    /** Allows the syscall but logs it via the kernel audit subsystem. */
    ACT_LOG(2, NativeConstants.SECCOMP_RET_LOG),

    /** Unconditionally allows the system call. */
    ACT_ALLOW(1, NativeConstants.SECCOMP_RET_ALLOW),
}
