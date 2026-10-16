package io.mazewall.ffi.networking

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow

/** Private installation IPC only: no imported tracee open-file description is modified. */
internal class SeccompInstallTransport(
    private val native: NativeEngine,
    private val handshake: SeccompInstallHandshake,
) {
    // SUPPRESSION-RATIONALE: Confined memory, deadline retry and descriptor rollback scopes must all surround connect; every failure preserves its acquired owner.
    @Suppress("TooGenericExceptionCaught")
    fun connect(path: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
        NativeArena.ofConfined().use { arena ->
            val address = SupervisorSocketUtils.setupSockAddrUn(arena, path)
            while (true) {
                handshake.checkActive()
                val socket = native.networking
                    .socket(
                        SupervisorSocketUtils.AF_UNIX,
                        SupervisorSocketUtils.SOCK_STREAM or NativeConstants.SOCK_CLOEXEC or SOCK_NONBLOCK,
                        0,
                    ).getFdOrThrow("create installation socket", FileDescriptorRole.UnixSocket)
                var owned = true
                try {
                    val result = native.networking.connect(socket, ConfinedSegment(address.segment), SupervisorSocketUtils.SOCKADDR_UN_SIZE)
                    when (result) {
                        is LinuxNative.SyscallResult.Success -> {
                            handshake.checkActive()
                            // Existing listeners own blocking stream reads. Only connect requires
                            // O_NONBLOCK; SCM_RIGHTS below uses per-call MSG_DONTWAIT instead.
                            val flags = native.raw.fcntl(socket, F_GETFL, 0).getOrThrow("read installation socket flags")
                            native.raw.fcntl(socket, F_SETFL, flags and SOCK_NONBLOCK.toLong().inv()).getOrThrow("restore installation stream flags")
                            return@use socket
                        }
                        is LinuxNative.SyscallResult.Error -> {
                            check(result.errno in RETRY_CONNECT) { "Installation connect failed: errno=${result.errno}" }
                        }
                    }
                    owned = false
                    native.fileSystem.close(socket).getOrThrow("close retry installation socket")
                } catch (failure: Throwable) {
                    if (owned) {
                        try {
                            native.fileSystem.close(socket).getOrThrow("close failed installation socket")
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                    }
                    throw failure
                }
                Thread.sleep(minOf(RETRY_DELAY_MS, handshake.remainingMillis()).toLong())
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable installation connect")
        }

    fun send(
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        listener: Int,
    ): Boolean =
        NativeArena.ofConfined().use { arena ->
            val poll = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
            poll.setFd(socket.value)
            poll.setEvents(POLLOUT)
            var attempts = 0
            SupervisorSocketUtils.sendDescriptor(socket.value, listener, native.networking) {
                handshake.checkActive()
                check(attempts++ < MAX_SEND_ATTEMPTS) { "Installation descriptor send retry budget exhausted" }
                if (attempts > 1) {
                    when (val result = native.raw.poll(poll.managed, 1, handshake.remainingMillis())) {
                        is LinuxNative.SyscallResult.Success -> Unit
                        is LinuxNative.SyscallResult.Error -> check(result.errno == NativeConstants.EINTR) { "Installation send readiness failed: errno=${result.errno}" }
                    }
                    handshake.checkActive()
                }
            }
        }

    private companion object {
        // Linux UAPI bits/socket_type.h, bits/poll.h and fcntl.h (LP64 x86-64/aarch64).
        const val SOCK_NONBLOCK = 0x800
        const val F_GETFL = 3
        const val F_SETFL = 4
        const val POLLOUT: Short = 4
        const val RETRY_DELAY_MS = 10
        const val MAX_SEND_ATTEMPTS = 8
        val RETRY_CONNECT = setOf(NativeConstants.EINTR, NativeConstants.EAGAIN, NativeConstants.ENOENT, 111)
    }
}
