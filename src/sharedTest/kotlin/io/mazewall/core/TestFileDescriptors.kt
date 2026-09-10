@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena

/** Test-only native acquisition simulation. Never shipped in the production platform artifact. */
object TestFileDescriptors {
    fun <R : FileDescriptorRole> adopt(
        value: Int,
        role: R,
        arena: NativeArena? = null,
    ): FileDescriptor<R, FdState.Open, FdOwnership.Owned> = FileDescriptor.acquired(value, role, arena)

    @Suppress("UNCHECKED_CAST")
    fun <R : FileDescriptorRole> replace(
        value: Int,
        arena: NativeArena? = null,
    ): FileDescriptor<R, FdState.Open, FdOwnership.Owned> = FileDescriptor.acquired(value, FileDescriptorRole.Generic, arena) as FileDescriptor<R, FdState.Open, FdOwnership.Owned>
}

fun LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>.testAcquisition(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
    when (this) {
        is LinuxNative.SyscallResult.Success -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(value).recordDescriptorAcquisition()
        is LinuxNative.SyscallResult.Error -> this
    }

fun LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>.testSyscallAcquisition(
    nr: Long,
    a1: NativeArg,
    a2: NativeArg,
    a3: NativeArg,
): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
    val arch = Arch.current()
    val acquired = nr == arch.open.toLong() ||
        nr == arch.openat.toLong() ||
        nr == arch.openat2.toLong() ||
        nr == arch.socket.toLong() ||
        nr == arch.memfdCreate.toLong() ||
        nr == arch.pidfdOpen.toLong() ||
        nr == arch.pidfdGetFd.toLong() ||
        (nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR && a3.asLong == 0L) ||
        (
            nr == arch.seccompSyscallNumber.toLong() &&
            a1.asLong == NativeConstants.SECCOMP_SET_MODE_FILTER.toLong() &&
            a2.asLong and NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER.toLong() != 0L
        )
    return if (acquired) testAcquisition() else this
}
