package io.mazewall.seccomp
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.CompiledSandbox
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.syscall.Arch

/**
 * States representing the progress of a Seccomp program installation.
 *
 * Strength order: Uninitialized (0) < Failed (1) < FilterBuilt (2) <
 * PrivilegesLocked (3) < SystemCallApplied/FallbackPrctlApplied (4) < Verified (5).
 * State merges retain the stronger state; the equal applied states retain the first operand.
 */
internal sealed interface SeccompInstallationState {
    val rank: Int

    /** The Seccomp installation process has not started. */
    data object Uninitialized : SeccompInstallationState {
        override val rank: Int = 0

        fun buildFilter(
            arena: NativeArena,
            sandbox: CompiledSandbox<*>,
            native: NativeEngine = LinuxNative.snapshot(),
        ): FilterBuilt {
            val filters = sandbox.compiledFilters
            val prog = with(arena) { native.memory.newSockFProg(filters) }
            return FilterBuilt(prog)
        }
    }

    /** The BPF program filter has been successfully constructed in memory. */
    data class FilterBuilt(
        val program: ManagedSegment,
    ) : SeccompInstallationState {
        override val rank: Int = 2

        fun lockPrivileges(
            native: NativeEngine = LinuxNative.snapshot(),
            platform: CapturedPlatform = Platform.capture(native),
        ): PrivilegesLocked {
            requirePlatformThread()
            if (!platform.isLinux) throw UnsupportedOperationException("Mazewall requires Linux for kernel-level containment.")
            PureJavaBpfEngine.lockNoNewPrivileges(native)
            return PrivilegesLocked(program)
        }
    }

    /** The thread or process has set `no_new_privs`. */
    data class PrivilegesLocked(
        val program: ManagedSegment,
    ) : SeccompInstallationState {
        override val rank: Int = 3

        fun applyFilter(
            arch: Arch,
            useTsync: Boolean,
            native: NativeEngine = LinuxNative.snapshot(),
        ): FilterApplied {
            return PureJavaBpfEngine.installFilter(arch, program, useTsync, native)
        }
    }

    /** Common interface for applied filter states. */
    sealed interface FilterApplied : SeccompInstallationState {
        fun verify(
            definition: PolicyDefinition<*>,
            native: NativeEngine = LinuxNative.snapshot(),
        ): Verified {
            PureJavaBpfEngine.verifyInstallation(definition, native)
            return Verified
        }
    }

    /** The Seccomp filter was successfully applied via the modern `seccomp(2)` syscall. */
    data object SystemCallApplied : FilterApplied {
        override val rank: Int = 4
    }

    /** The Seccomp filter was successfully applied via the fallback `prctl(2)` command. */
    data object FallbackPrctlApplied : FilterApplied {
        override val rank: Int = 4
    }

    /** The Seccomp installation was verified successfully. */
    data object Verified : SeccompInstallationState {
        override val rank: Int = 5
    }

    /** The installation process failed at a specific step. */
    data class Failed(
        val step: String,
        val errno: Int,
        val error: Throwable,
    ) : SeccompInstallationState {
        override val rank: Int = 1
    }
}
