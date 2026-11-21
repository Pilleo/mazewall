package io.mazewall.lockdown

import java.util.Collections
import java.util.EnumSet

/**
 * Curated process-wide seccomp policy presets selectable via [ProcessPolicy.fromPreset]
 * or the corresponding convenience factories on [ProcessPolicy].
 */
public enum class LockdownPreset(
    vararg defaultBlocked: LockdownSyscall,
) {
    /**
     * Elasticsearch-compatible process-execution baseline:
     * blocks `execve`, `execveat`, `fork`, and `vfork`.
     */
    ELASTICSEARCH(
        LockdownSyscall.EXECVE,
        LockdownSyscall.EXECVEAT,
        LockdownSyscall.FORK,
        LockdownSyscall.VFORK,
    ),

    /**
     * OpenSearch-compatible process and cross-process inspection baseline:
     * blocks [ELASTICSEARCH] syscalls plus `ptrace`, `process_vm_readv`,
     * `process_vm_writev`, and `pidfd_getfd`.
     */
    OPENSEARCH(
        LockdownSyscall.EXECVE,
        LockdownSyscall.EXECVEAT,
        LockdownSyscall.FORK,
        LockdownSyscall.VFORK,
        LockdownSyscall.PTRACE,
        LockdownSyscall.PROCESS_VM_READV,
        LockdownSyscall.PROCESS_VM_WRITEV,
        LockdownSyscall.PIDFD_GETFD,
    ),

    /**
     * Mazewall `0.1.0` default no-exec baseline:
     * blocks `execve`, `execveat`, `memfd_create`, `io_uring_setup`, and `io_uring_enter`
     * while leaving `fork` and `vfork` permitted (children inherit the irreversible filter).
     */
    MAZEWALL(
        LockdownSyscall.EXECVE,
        LockdownSyscall.EXECVEAT,
        LockdownSyscall.MEMFD_CREATE,
        LockdownSyscall.IO_URING_SETUP,
        LockdownSyscall.IO_URING_ENTER,
    ),

    /**
     * Combined strict lockdown blocking the full union of [OPENSEARCH] and [MAZEWALL]:
     * `execve`, `execveat`, `fork`, `vfork`, `ptrace`, `process_vm_readv`,
     * `process_vm_writev`, `pidfd_getfd`, `memfd_create`, `io_uring_setup`, and `io_uring_enter`.
     */
    MAZEWALL_STRICT(
        LockdownSyscall.EXECVE,
        LockdownSyscall.EXECVEAT,
        LockdownSyscall.FORK,
        LockdownSyscall.VFORK,
        LockdownSyscall.PTRACE,
        LockdownSyscall.PROCESS_VM_READV,
        LockdownSyscall.PROCESS_VM_WRITEV,
        LockdownSyscall.PIDFD_GETFD,
        LockdownSyscall.MEMFD_CREATE,
        LockdownSyscall.IO_URING_SETUP,
        LockdownSyscall.IO_URING_ENTER,
    ),
    ;

    private val defaultBlockedSet: Set<LockdownSyscall> =
        Collections.unmodifiableSet(
            EnumSet.noneOf(LockdownSyscall::class.java).apply {
                addAll(defaultBlocked)
            },
        )

    /** Returns an unmodifiable set containing the syscalls blocked by this preset in canonical enum order. */
    public fun defaultBlockedSyscalls(): Set<LockdownSyscall> = defaultBlockedSet
}
