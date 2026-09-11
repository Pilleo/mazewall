package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.SeccompConnection
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeccompDaemonEngineTest {
    @Test
    fun `session reactor closes factory-created notification handler`() {
        var notificationHandlerClosed = false
        val nativeEngine = MockNativeEngine().apply {
            onPoll = { _, _, _ -> LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L) }
        }
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ ->
                object : SeccompNotifHandler, AutoCloseable {
                    context(arena: NativeArena) override fun beginNotification(
                        notif: ManagedSegment,
                        resp: ManagedSegment,
                        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                        deadline: io.mazewall.core.Deadline,
                    ): NotificationWork = error("unused")

                    override fun close() {
                        notificationHandlerClosed = true
                    }
                }
            },
            engine = nativeEngine,
        )
        val socketFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = TestFileDescriptors
            .replace<FileDescriptorRole.SeccompNotif>(11)
        val connection = SeccompConnection.Active(socketFd, listenerFd)

        NativeArena.ofConfined().use { arena ->
            daemon.processConnectionStep(arena, connection, socketFd, arena.allocate(8L))
        }

        assertTrue(notificationHandlerClosed)
    }
}
