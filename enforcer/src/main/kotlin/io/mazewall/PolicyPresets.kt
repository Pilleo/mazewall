package io.mazewall

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

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
            .block(Syscall.CONNECT, Syscall.SENDTO, Syscall.SENDMSG, Syscall.SENDMMSG, Syscall.RECVMMSG, Syscall.SOCKET)
            .block(Syscall.BIND, Syscall.LISTEN, Syscall.ACCEPT, Syscall.ACCEPT4)
            .block(Syscall.EXECVE, Syscall.EXECVEAT)
            .block(Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2)
            .block(Syscall.RENAME, Syscall.RENAMEAT, Syscall.RENAMEAT2)
            .block(Syscall.LINK, Syscall.LINKAT, Syscall.UNLINK, Syscall.UNLINKAT)
            .block(Syscall.SYMLINK, Syscall.SYMLINKAT, Syscall.READLINK, Syscall.READLINKAT)
            .block(Syscall.MKDIR, Syscall.MKDIRAT, Syscall.RMDIR)
            // File content mutations (issue-20260821-113000): creat/truncate bypass open-based
            // traps; without these blocks USER_NOTIF profiling never observes them.
            .block(Syscall.CREAT, Syscall.TRUNCATE, Syscall.FTRUNCATE)
            .block(Syscall.CHMOD, Syscall.FCHMOD, Syscall.FCHMODAT)
            .build()

    /**
     * Blocks `execve` / `execveat` / `memfd_create`.
     *
     * **Not JIT-safe process-wide.** [PolicyBuilder] defaults `allowMmapExec` to false, so this
     * preset also emits `mmap`/`mprotect` `PROT_EXEC` denies. [installOnProcess][io.mazewall.enforcer.api.ContainedExecutors.installOnProcess]
     * then reaches HotSpot compiler threads and can fatal the JVM.
     *
     * Use [NO_EXEC_HOTSPOT] for a running HotSpot process. Use this preset only when you
     * intend W^X (AOT / no further code generation) or you will call [PolicyBuilder.allowMmapExec].
     */
    @JvmField
    public val NO_EXEC: PolicyDefinition<PolicyScope.ProcessWideSafe> =
        PolicyBuilder<PolicyScope.ProcessWideSafe>()
            .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
            .build()

    /**
     * Process-wide Tier 1 for a HotSpot JVM: same syscall blocks as [NO_EXEC], plus
     * [PolicyBuilder.allowMmapExec] so the JIT code cache can still `mmap(PROT_EXEC)`.
     */
    @JvmField
    public val NO_EXEC_HOTSPOT: PolicyDefinition<PolicyScope.ProcessWideSafe> =
        PolicyBuilder<PolicyScope.ProcessWideSafe>()
            .base(NO_EXEC)
            .forRuntime(RuntimeProfile.HOTSPOT_JIT)
            .build()

    /**
     * Explicit W^X name for [NO_EXEC]. Same filters: no exec/memfd and no `PROT_EXEC`.
     */
    @JvmField
    public val NO_EXEC_NATIVE_IMAGE: PolicyDefinition<PolicyScope.ProcessWideSafe> = NO_EXEC

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
     * Process-wide Tier 1: [NO_EXEC] plus denial of filesystem *mutation* syscalls
     * (rename/link/unlink/symlink, mkdir/rmdir, creat/truncate/ftruncate, chmod
     * family). Reads stay allowed - use [PolicyBuilder.allowFsWrite] style scoping
     * via a ThreadLocalOnly policy when writes are needed.
     */
    @JvmField
    public val NO_EXEC_NO_FS_WRITE: PolicyDefinition<PolicyScope.ProcessWideSafe> =
        PolicyBuilder<PolicyScope.ProcessWideSafe>()
            .base(NO_EXEC)
            .block(Syscall.RENAME, Syscall.RENAMEAT, Syscall.RENAMEAT2)
            .block(Syscall.LINK, Syscall.LINKAT, Syscall.UNLINK, Syscall.UNLINKAT)
            .block(Syscall.SYMLINK, Syscall.SYMLINKAT)
            .block(Syscall.MKDIR, Syscall.MKDIRAT, Syscall.RMDIR)
            .block(Syscall.CREAT, Syscall.TRUNCATE, Syscall.FTRUNCATE)
            .block(Syscall.CHMOD, Syscall.FCHMOD, Syscall.FCHMODAT)
            .build()

    /**
     * Recommended application baseline: JIT-safe no-exec ([NO_EXEC_HOTSPOT]) +
     * [NO_NETWORK] + [NO_EXEC_NO_FS_WRITE] filesystem-mutation denials.
     * Process-wide safe by construction.
     */
    @JvmField
    public val DEFAULT_SAFE: PolicyDefinition<PolicyScope.ProcessWideSafe> =
        PolicyBuilder<PolicyScope.ProcessWideSafe>()
            .base(NO_EXEC_HOTSPOT)
            .block(Syscall.CONNECT, Syscall.SENDTO, Syscall.SENDMSG, Syscall.SENDMMSG, Syscall.SOCKET)
            .block(Syscall.BIND, Syscall.LISTEN, Syscall.ACCEPT, Syscall.ACCEPT4)
            .block(Syscall.RENAME, Syscall.RENAMEAT, Syscall.RENAMEAT2)
            .block(Syscall.LINK, Syscall.LINKAT, Syscall.UNLINK, Syscall.UNLINKAT)
            .block(Syscall.SYMLINK, Syscall.SYMLINKAT)
            .block(Syscall.MKDIR, Syscall.MKDIRAT, Syscall.RMDIR)
            .block(Syscall.CREAT, Syscall.TRUNCATE, Syscall.FTRUNCATE)
            .block(Syscall.CHMOD, Syscall.FCHMOD, Syscall.FCHMODAT)
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
