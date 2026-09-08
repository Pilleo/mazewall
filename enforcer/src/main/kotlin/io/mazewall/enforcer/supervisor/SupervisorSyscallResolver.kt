package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import io.mazewall.core.SyscallNumber

/** Maps an audited syscall number to its policy identity without inventing a fallback. */
internal object SupervisorSyscallResolver {
    fun resolve(
        number: Int,
        arch: Arch,
    ): SupervisorSyscallResolution =
        Syscall.entries
            .firstOrNull { it.numberFor(arch) == number }
            ?.let(SupervisorSyscallResolution::Known)
            ?: SupervisorSyscallResolution.Unknown(SyscallNumber(number), arch)
}

internal sealed interface SupervisorSyscallResolution {
    data class Known(
        val syscall: Syscall,
    ) : SupervisorSyscallResolution

    data class Unknown(
        val number: SyscallNumber,
        val arch: Arch,
    ) : SupervisorSyscallResolution
}
