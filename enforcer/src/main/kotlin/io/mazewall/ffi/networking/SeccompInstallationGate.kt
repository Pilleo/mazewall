package io.mazewall.ffi.networking

import io.mazewall.platform.lifecycle.MazewallInternal
import io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled
import io.mazewall.platform.native.LinuxNative.SyscallResult

/**
 * Serializes an irreversible seccomp installation with its owner's close decision.
 * Only the native install operation belongs inside [commit]; allocation and handshake waits do not.
 */
@MazewallInternal
internal fun interface SeccompInstallationGate {
    public fun commit(operation: () -> SyscallResult<Long, Unhandled>): SyscallResult<Long, Unhandled>

    public companion object {
        /** Existing callers without an enclosing lifecycle commit directly. */
        public val Direct: SeccompInstallationGate = SeccompInstallationGate { operation -> operation() }
    }
}
