package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NewFdFlags
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SeccompNotifAddFdSegment
import io.mazewall.ffi.memory.fill
import io.mazewall.ffi.typed
import java.util.logging.Logger

/** Performs the retry-safe USER_NOTIF descriptor injection for a locally owned descriptor. */
internal class SupervisorFdInjector(
    private val engine: io.mazewall.NativeEngine,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    private val logger: Logger,
) {
    context(arena: NativeArena) fun inject(
        id: Long,
        localFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        flags: NewFdFlags,
    ): Boolean {
        val addfd = SeccompNotifAddFdSegment.of(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
        addfd.managed.fill(0)
        addfd.setId(id)
        addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
        addfd.setSrcfd(localFd.value)
        addfd.setNewfdFlags(flags.value)
        while (true) {
            when (val result = engine.raw.ioctl(listenerFd, IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD, addfd.managed.typed<IoctlPayload.SeccompNotifAddFd>())) {
                is LinuxNative.SyscallResult.Success -> return true
                is LinuxNative.SyscallResult.Error -> if (result.errno != NativeConstants.EINTR) {
                    if (result.errno == NativeConstants.EBADF) {
                        logger.severe("[SUPERVISOR-SECURITY] SECCOMP_IOCTL_NOTIF_ADDFD failed with EBADF (listenerFd=${listenerFd.value}, srcfd=${localFd.value}).")
                    } else {
                        logger.severe("[SUPERVISOR-DEBUG] SECCOMP_IOCTL_NOTIF_ADDFD failed with errno ${result.errno}.")
                    }
                    return false
                }
            }
        }
    }
}
