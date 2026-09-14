package io.mazewall.platform.fd

import io.mazewall.platform.native.LinuxNative
import java.util.concurrent.atomic.AtomicBoolean

/** Single-use evidence attached by the native boundary to a descriptor acquisition result. */
public class NativeDescriptorReceipt private constructor(
    value: Int,
) {
    private val descriptor = FileDescriptor.acquired(value, FileDescriptorRole.Generic)
    private val consumed = AtomicBoolean()

    @JvmSynthetic
    internal fun <R : FileDescriptorRole> consume(role: R): FileDescriptor<R, FdState.Open, FdOwnership.Owned> {
        check(consumed.compareAndSet(false, true)) { "Native descriptor acquisition receipt has already been consumed" }
        return FileDescriptor.withRole(descriptor, role)
    }

    internal companion object {
        @JvmSynthetic
        internal fun acquired(value: Int): NativeDescriptorReceipt = NativeDescriptorReceipt(value)
    }
}

@JvmSynthetic
internal fun LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>.recordDescriptorAcquisition(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
    if (this is LinuxNative.SyscallResult.Success) {
        require(value in 0L..Int.MAX_VALUE.toLong()) { "Native descriptor result is outside the int range: $value" }
        descriptorReceipts = listOf(NativeDescriptorReceipt.acquired(value.toInt()))
    }
    return this
}
