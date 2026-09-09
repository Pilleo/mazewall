package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.NativeFileSystem
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.NativeConstants

/** Ownership originates only in a successful native descriptor-producing call. */
internal class SupervisorOwnedFd(
    val handle: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    private val fileSystem: NativeFileSystem,
) : AutoCloseable {
    /** A successful native acquisition must not leak if releasing its prerequisite fails. */
    fun produce(acquire: () -> SupervisorDescriptorResult): SupervisorDescriptorResult {
        var produced: SupervisorDescriptorResult? = null
        try {
            return use { acquire().also { result -> produced = result } }
        } catch (expectedAcquisitionFailure: Exception) {
            val output = produced
            if (output is SupervisorDescriptorResult.Opened) output.descriptor.use { throw expectedAcquisitionFailure }
            throw expectedAcquisitionFailure
        }
    }

    override fun close() {
        if (!handle.isLiveForIo()) return
        when (val result = fileSystem.close(handle)) {
            is LinuxNative.SyscallResult.Success -> Unit
            is LinuxNative.SyscallResult.Error -> error("Supervisor close fd=${handle.value} failed with errno=${result.errno}")
        }
    }
}

internal sealed interface SupervisorDescriptorResult {
    data class Opened(
        val descriptor: SupervisorOwnedFd,
    ) : SupervisorDescriptorResult

    data class Denied(
        val errno: Int,
    ) : SupervisorDescriptorResult

    companion object {
        fun from(
            result: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>,
            engine: NativeEngine,
        ): SupervisorDescriptorResult =
            when (result) {
                is LinuxNative.SyscallResult.Error -> Denied(result.errno)
                is LinuxNative.SyscallResult.Success ->
                    if (result.value in 0..Int.MAX_VALUE.toLong()) {
                        Opened(SupervisorOwnedFd(FileDescriptor.adopt(result.value.toInt(), FileDescriptorRole.Generic), engine.fileSystem))
                    } else {
                        Denied(NativeConstants.EPERM)
                    }
            }
    }
}
