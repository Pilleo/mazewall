package io.mazewall.profiler.compiler

import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import io.mazewall.seccomp.BpfInstruction
import io.mazewall.seccomp.BpfSimulator

/**
 * Audits that the installed profiling filter actually TRAPS filesystem-mutation syscalls
 * (issue-20260821-113000): `creat`/`truncate`/`ftruncate` bypass open-based traps, so if the
 * USER_NOTIF program does not deny them, profiling coverage must NOT be certified complete.
 */
internal object MutationTrapAudit {
    /** Syscalls whose mutation capability is invisible unless the filter traps them. */
    private val MUTATION_SYSCALLS = listOf(Syscall.CREAT, Syscall.TRUNCATE, Syscall.FTRUNCATE)

    /**
     * @return mutation syscall names whose NR (on [arch]) the program does NOT deny with an
     *         ERRNO-class verdict (i.e. they will never reach USER_NOTIF). Unmapped NRs
     *         (`-1`, e.g. CREAT on aarch64) are excluded — there is nothing to trap.
     */
    fun untrapped(program: List<BpfInstruction>, arch: Arch): List<String> =
        MUTATION_SYSCALLS.mapNotNull { sys ->
            val nr = sys.numberFor(arch)
            if (nr < 0) return@mapNotNull null
            val action = BpfSimulator.simulate(program, nr, arch)
            // Trapped = kernel hands the decision to userspace (ERRNO-class in enforce mode,
            // USER_NOTIF in profiling mode). Plain ALLOW means never observed.
            val trapped = action != null && (
                (action ushr 16) == (NativeConstants.SECCOMP_RET_ERRNO ushr 16) ||
                    action == NativeConstants.SECCOMP_RET_USER_NOTIF
                )
            if (trapped) null else "${sys.name.lowercase()}(nr=$nr)"
        }
}
