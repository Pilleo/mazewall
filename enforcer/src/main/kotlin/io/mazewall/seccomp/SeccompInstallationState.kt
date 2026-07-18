package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.PolicyDefinition
import io.mazewall.CompiledSandbox
import io.mazewall.enforcer.validateLinuxAndNotVirtual
import io.mazewall.core.Arch
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena

/**
 * States representing the progress of a Seccomp program installation.
 */
internal sealed interface SeccompInstallationState {
    /** The Seccomp installation process has not started. */
    data object Uninitialized : SeccompInstallationState {
        fun buildFilter(arena: NativeArena, sandbox: CompiledSandbox<*>): FilterBuilt {
            val filters = sandbox.compiledFilters
            val prog = with(arena) { LinuxNative.memory.newSockFProg(filters) }
            return FilterBuilt(prog)
        }
    }

    /** The BPF program filter has been successfully constructed in memory. */
    data class FilterBuilt(
        val program: ManagedSegment,
    ) : SeccompInstallationState {
        fun lockPrivileges(): PrivilegesLocked {
            validateLinuxAndNotVirtual()
            PureJavaBpfEngine.setNoNewPrivs()
            return PrivilegesLocked(program)
        }
    }

    /** The thread or process has set `no_new_privs`. */
    data class PrivilegesLocked(
        val program: ManagedSegment,
    ) : SeccompInstallationState {
        fun applyFilter(arch: Arch, useTsync: Boolean): FilterApplied {
            return PureJavaBpfEngine.installFilter(arch, program, useTsync)
        }
    }

    /** Common interface for applied filter states. */
    sealed interface FilterApplied : SeccompInstallationState {
        fun verify(definition: PolicyDefinition<*>): Verified {
            PureJavaBpfEngine.verifyInstallation(definition)
            return Verified
        }
    }

    /** The Seccomp filter was successfully applied via the modern `seccomp(2)` syscall. */
    data object SystemCallApplied : FilterApplied

    /** The Seccomp filter was successfully applied via the fallback `prctl(2)` command. */
    data object FallbackPrctlApplied : FilterApplied

    /** The Seccomp installation was verified successfully. */
    data object Verified : SeccompInstallationState

    /** The installation process failed at a specific step. */
    data class Failed(
        val step: String,
        val errno: Int,
        val error: Throwable,
    ) : SeccompInstallationState
}
