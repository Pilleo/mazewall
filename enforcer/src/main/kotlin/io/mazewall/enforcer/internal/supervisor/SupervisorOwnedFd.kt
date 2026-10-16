package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.getFdOrThrow

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
        } catch (expectedAcquisitionFailure: Throwable) {
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
                        Opened(SupervisorOwnedFd(result.getFdOrThrow("acquire supervisor descriptor"), engine.fileSystem))
                    } else {
                        Denied(NativeConstants.EPERM)
                    }
            }
    }
}
