package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.SyscallNumber
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.seccomp.SupervisedKind

/** Pure classification of the local resource required by an injection route. */
internal sealed interface SupervisorInjectionPlan {
    data class Open(
        val request: SupervisedOpen,
    ) : SupervisorInjectionPlan

    data class Connect(
        val sockaddr: ByteArray,
    ) : SupervisorInjectionPlan

    data object Accept : SupervisorInjectionPlan

    data class Deny(
        val errno: Int = NativeConstants.EPERM,
    ) : SupervisorInjectionPlan

    companion object {
        fun create(
            nr: Int,
            arch: Arch,
            args: LongArray,
            extracted: SyscallArguments,
        ): SupervisorInjectionPlan =
            when (injectTarget(SupervisorNotificationMachine.classify(SyscallNumber(nr), arch))) {
                is InjectTarget.Open ->
                    extracted.pathStr
                    ?.let { SupervisedOpen.parse(nr, args, it, arch, extracted.openHow) }
                    ?.let(::Open)
                    ?: Deny()
                is InjectTarget.Connect -> extracted.sockaddrBytes?.let(::Connect) ?: Deny()
                is InjectTarget.Accept -> Accept
                is InjectTarget.Unsupported -> Deny()
            }
    }
}
