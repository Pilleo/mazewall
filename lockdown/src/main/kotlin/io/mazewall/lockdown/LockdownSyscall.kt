package io.mazewall.lockdown

import io.mazewall.lockdown.internal.platform.syscall.Syscall
import java.util.Locale

/**
 * Process-wide-safe Linux system calls that can be added to or removed from a [ProcessPolicy].
 *
 * Critical JVM coordination, memory-mapping, signal, and thread-creation syscalls
 * (such as `futex`, `mmap`, `rt_sigaction`, and `clone(CLONE_THREAD)`) are intentionally excluded
 * so policy customization cannot deadlock GC, safepoints, or virtual-thread carrier scheduling.
 */
public enum class LockdownSyscall(
    @get:JvmSynthetic
    internal val internalSyscall: Syscall,
) {
    EXECVE(Syscall.EXECVE),
    EXECVEAT(Syscall.EXECVEAT),
    FORK(Syscall.FORK),
    VFORK(Syscall.VFORK),
    PTRACE(Syscall.PTRACE),
    PROCESS_VM_READV(Syscall.PROCESS_VM_READV),
    PROCESS_VM_WRITEV(Syscall.PROCESS_VM_WRITEV),
    PIDFD_GETFD(Syscall.PIDFD_GETFD),
    PIDFD_OPEN(Syscall.PIDFD_OPEN),
    MEMFD_CREATE(Syscall.MEMFD_CREATE),
    IO_URING_SETUP(Syscall.IO_URING_SETUP),
    IO_URING_ENTER(Syscall.IO_URING_ENTER),
    BPF(Syscall.BPF),
    USERFAULTFD(Syscall.USERFAULTFD),
    INIT_MODULE(Syscall.INIT_MODULE),
    FINIT_MODULE(Syscall.FINIT_MODULE),
    UNSHARE(Syscall.UNSHARE),
    SETNS(Syscall.SETNS),
    MOUNT(Syscall.MOUNT),
    UMOUNT2(Syscall.UMOUNT2),
    PIVOT_ROOT(Syscall.PIVOT_ROOT),
    CHROOT(Syscall.CHROOT),
    LINK(Syscall.LINK),
    LINKAT(Syscall.LINKAT),
    SYMLINK(Syscall.SYMLINK),
    SYMLINKAT(Syscall.SYMLINKAT),
    CHMOD(Syscall.CHMOD),
    FCHMOD(Syscall.FCHMOD),
    FCHMODAT(Syscall.FCHMODAT),
    CHOWN(Syscall.CHOWN),
    LCHOWN(Syscall.LCHOWN),
    FCHOWN(Syscall.FCHOWN),
    FCHOWNAT(Syscall.FCHOWNAT),
    ;

    /** Lowercase Linux system call name corresponding to this constant. */
    public fun syscallName(): String = name.lowercase(Locale.ROOT)
}
