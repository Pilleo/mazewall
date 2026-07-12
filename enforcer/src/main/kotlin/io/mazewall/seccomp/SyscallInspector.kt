package io.mazewall.seccomp

import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.ffi.NativeConstants

/**
 * Context provided to [SyscallInspector]s to determine how to build their rules.
 */
internal data class InspectionContext(
    val syscallActions: Map<Int, SeccompAction>,
    val defaultAction: SeccompAction,
    val jvmCriticalNrs: Set<Int>,
    val allowMmapExec: Boolean,
    val allowNonThreadClone: Boolean,
    val allowUnsafePrctl: Boolean,
) {
    /**
     * Resolves the effective action for a given syscall number, taking into account
     * critical JVM requirements (which override policy) and default fallbacks.
     */
    fun resolveEffectiveAction(nr: Int): SeccompAction {
        if (nr in jvmCriticalNrs) return SeccompAction.ACT_ALLOW
        return syscallActions[nr] ?: defaultAction
    }
}

/**
 * A plugin that generates Seccomp-BPF argument inspections for specific syscalls.
 */
internal interface SyscallInspector {
    /**
     * Returns a list of standard, argument-based inspections to be emitted.
     */
    fun getInspections(arch: Arch, context: InspectionContext): List<SyscallInspection> = emptyList()

    /**
     * Emits special, non-standard BPF logic directly into the builder.
     * Used for syscalls that don't fit the standard ArgCheck model (e.g. clone3).
     */
    fun emitSpecial(
        builder: BpfBuilder.NrLoaded,
        arch: Arch,
        context: InspectionContext,
        handledNrs: MutableSet<Int>
    ) {
    }
}

/**
 * Ensures clone3 always returns ENOSYS, as it is complex to inspect and usually not
 * needed by JVMs if standard clone is available.
 */
internal class Clone3Inspector : SyscallInspector {
    override fun emitSpecial(
        builder: BpfBuilder.NrLoaded,
        arch: Arch,
        context: InspectionContext,
        handledNrs: MutableSet<Int>
    ) {
        if (arch.clone3 >= 0) {
            val enosysAction = NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.ENOSYS
            handledNrs.add(arch.clone3)
            builder.expect(arch.clone3) {
                ret(enosysAction)
            }
        }
    }
}

/**
 * Inspects memory mapping syscalls (mmap, mprotect, pkey_mprotect) to block PROT_EXEC
 * when execution is not explicitly allowed.
 */
internal class MmapExecInspector : SyscallInspector {
    override fun getInspections(arch: Arch, context: InspectionContext): List<SyscallInspection> {
        if (context.allowMmapExec) return emptyList()

        return listOf(arch.mmap, arch.mprotect, arch.pkeyMprotect)
            .filter { it >= 0 }
            .map { nr ->
                SyscallInspection(
                    syscallNumber = nr,
                    argIndex = 2, // PROT flag is usually the 3rd argument
                    check = ArgCheck.MaskEquals(PROT_EXEC, 0x00L),
                    ifMatched = context.resolveEffectiveAction(nr),
                    ifNotMatched = SeccompAction.ACT_ERRNO,
                )
            }
    }

    private companion object {
        private const val PROT_EXEC = 0x04L
    }
}

/**
 * Inspects clone to ensure it is only used for creating threads (CLONE_THREAD)
 * and not full processes, when non-thread cloning is not allowed.
 *
 * ### Safepoint Deadlock Prevention
 * JVM thread creation (via `Thread.start()`) calls `clone(CLONE_THREAD | ...)`.
 * If this syscall is intercepted and sent to the JVM supervisor, the supervisor
 * will call `Thread.getStackTrace()`, which triggers a JVM safepoint.
 * However, the thread-creating thread holds internal JVM locks that are required
 * for a safepoint to complete, leading to a permanent deadlock.
 *
 * SOLUTION: The BPF filter must unconditionally ALLOW `clone` when `CLONE_THREAD`
 * is set, bypassing seccomp interception entirely for Java thread creation.
 */
internal class ThreadCloneInspector : SyscallInspector {
    override fun getInspections(arch: Arch, context: InspectionContext): List<SyscallInspection> {
        if (arch.clone < 0) return emptyList()

        val nr = arch.clone
        return listOf(
            SyscallInspection(
                syscallNumber = nr,
                argIndex = 0, // clone flags are the 1st argument
                check = ArgCheck.MaskEquals(CLONE_THREAD, CLONE_THREAD),
                ifMatched = SeccompAction.ACT_ALLOW, // Bypass supervisor for JVM threads
                ifNotMatched = context.resolveEffectiveAction(nr),
            )
        )
    }

    private companion object {
        private const val CLONE_THREAD = 0x00010000L
    }
}

/**
 * Inspects prctl to block dangerous sub-commands (like PR_SET_MM or PR_SET_PTRACER)
 * when unsafe prctl operations are not explicitly allowed.
 */
internal class UnsafePrctlInspector : SyscallInspector {
    override fun getInspections(arch: Arch, context: InspectionContext): List<SyscallInspection> {
        if (context.allowUnsafePrctl || arch.prctl < 0) return emptyList()

        val nr = arch.prctl
        return listOf(
            SyscallInspection(
                syscallNumber = nr,
                argIndex = 0, // prctl option is the 1st argument
                check = ArgCheck.EqualsAny(SAFE_PRCTL_OPTIONS),
                ifMatched = context.resolveEffectiveAction(nr),
                ifNotMatched = SeccompAction.ACT_ERRNO,
            )
        )
    }

    private companion object {
        private val SAFE_PRCTL_OPTIONS = listOf(15L, 16L, 21L, 22L, 38L, 39L)
    }
}

/**
 * Inspects ioctl to allow essential JVM operations (NIO, ZGC) while blocking others.
 */
internal class IoctlInspector : SyscallInspector {
    override fun getInspections(arch: Arch, context: InspectionContext): List<SyscallInspection> {
        if (arch.ioctl < 0) return emptyList()

        val nr = arch.ioctl
        return listOf(
            SyscallInspection(
                syscallNumber = nr,
                argIndex = 1, // ioctl request is the 2nd argument
                check = ArgCheck.EqualsAny(ALLOWED_IOCTLS),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = context.resolveEffectiveAction(nr),
            )
        )
    }

    private companion object {
        private val ALLOWED_IOCTLS = listOf(
            NativeConstants.FIONBIO.toLong(),
            NativeConstants.FIONREAD.toLong(),
            NativeConstants.UFFDIO_API,
            NativeConstants.UFFDIO_REGISTER,
            NativeConstants.UFFDIO_UNREGISTER,
            NativeConstants.UFFDIO_COPY,
            NativeConstants.UFFDIO_ZEROPAGE,
        )
    }
}
