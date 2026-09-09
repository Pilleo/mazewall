package io.mazewall.ffi.networking

import io.mazewall.LinuxNative.SyscallHandledState.Unhandled
import io.mazewall.LinuxNative.SyscallResult
import io.mazewall.MazewallInternal

/**
 * Serializes an irreversible seccomp installation with its owner's close decision.
 * Only the native install operation belongs inside [commit]; allocation and handshake waits do not.
 */
@MazewallInternal
public fun interface SeccompInstallationGate {
    public fun commit(operation: () -> SyscallResult<Long, Unhandled>): SyscallResult<Long, Unhandled>

    public companion object {
        /** Existing callers without an enclosing lifecycle commit directly. */
        public val Direct: SeccompInstallationGate = SeccompInstallationGate { operation -> operation() }
    }
}
