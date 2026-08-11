package io.mazewall.core


import io.mazewall.ffi.NativeConstants

/**
 * Standard Linux Seccomp-BPF return actions.
 *
 * The priority is used when combining multiple policies. Higher priority
 * actions (more restrictive) will override lower priority ones for the same syscall.
 */
public sealed interface SeccompAction {
    public val priority: Int
    public val nativeCode: Int

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

    /** Returns EPERM (or ENOSYS for clone3) to the calling thread. */
    public data class ACT_ERRNO(public val errno: Int = NativeConstants.EPERM) : SeccompAction {
        override val priority: Int = 4
        override val nativeCode: Int = NativeConstants.SECCOMP_RET_ERRNO

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other is Companion && this.errno == NativeConstants.EPERM) return true
            if (other !is ACT_ERRNO) return false
            return this.errno == other.errno
        }

        override fun hashCode(): Int {
            return errno
        }

        public companion object : SeccompAction {
            override val priority: Int = 4
            override val nativeCode: Int = NativeConstants.SECCOMP_RET_ERRNO
            public val errno: Int = NativeConstants.EPERM

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other is ACT_ERRNO) {
                    return other.errno == NativeConstants.EPERM
                }
                return false
            }

            override fun hashCode(): Int {
                return NativeConstants.EPERM
            }
        }
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
}
