package io.mazewall

import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall

public object PolicyPresets {
    /**
     * Low-level building block. Blocks all network I/O, process execution, and file opens.
     */
    @JvmField
    public val PURE_COMPUTE_UNSAFE: PolicyDefinition<PolicyScope.ProcessWideSafe> =
        PolicyBuilder<PolicyScope.ProcessWideSafe>()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .allowMmapExec()
            .block(Syscall.CONNECT, Syscall.SENDTO, Syscall.SENDMSG, Syscall.SENDMMSG, Syscall.RECVMMSG, Syscall.SOCKET)
            .block(Syscall.BIND, Syscall.LISTEN, Syscall.ACCEPT, Syscall.ACCEPT4)
            .block(Syscall.EXECVE, Syscall.EXECVEAT)
            .block(Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2)
            .block(Syscall.RENAME, Syscall.RENAMEAT, Syscall.RENAMEAT2)
            .block(Syscall.LINK, Syscall.LINKAT, Syscall.UNLINK, Syscall.UNLINKAT)
            .block(Syscall.SYMLINK, Syscall.SYMLINKAT, Syscall.READLINK, Syscall.READLINKAT)
            .block(Syscall.MKDIR, Syscall.MKDIRAT, Syscall.RMDIR)
            .block(Syscall.CHMOD, Syscall.FCHMOD, Syscall.FCHMODAT)
            .build()

    /**
     * The absolute minimum baseline for any production JVM process.
     */
    @JvmField
    public val NO_EXEC: PolicyDefinition<PolicyScope.ProcessWideSafe> =
        PolicyBuilder<PolicyScope.ProcessWideSafe>()
            .allowMmapExec()
            .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
            .build()

    /**
     * Blocks all network-related system calls. Safe for process-wide application.
     */
    @JvmField
    public val NO_NETWORK: PolicyDefinition<PolicyScope.ProcessWideSafe> =
        PolicyBuilder<PolicyScope.ProcessWideSafe>()
            .block(Syscall.CONNECT, Syscall.SENDTO, Syscall.SENDMSG, Syscall.SENDMMSG, Syscall.RECVMMSG, Syscall.SOCKET)
            .block(Syscall.BIND, Syscall.LISTEN, Syscall.ACCEPT, Syscall.ACCEPT4)
            .build()

    /**
     * Standard high-level preset for pure computational tasks.
     */
    @JvmField
    public val PURE_COMPUTE: PolicyDefinition<PolicyScope.ThreadLocalOnly> =
        PolicyBuilder<PolicyScope.ThreadLocalOnly>()
            .base(PURE_COMPUTE_UNSAFE)
            .block(Syscall.IOCTL)
            .allowJvmClasspath()
            .build()
}
