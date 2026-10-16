package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.seccomp.daemon.ReceiveOutcome
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.logging.Logger

class NotificationReaderTest {
    @Test
    fun `EINTR requires another readiness poll before another receive attempt`() {
        var attempts = 0
        val engine = MockNativeEngine().apply {
            onIoctl = { _, _, _ ->
                attempts++
                LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            }
        }
        val reader = NotificationReader(engine, Logger.getAnonymousLogger())

        NativeArena.ofConfined().use { arena ->
            assertEquals(
                ReceiveOutcome.RetryAfterPoll,
                reader.recvNotification(
                    TestFileDescriptors.replace<FileDescriptorRole.SeccompNotif>(11),
                    arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF),
                ),
            )
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `EAGAIN is a benign not-ready receive outcome`() {
        val engine = MockNativeEngine().apply {
            onIoctl = { _, _, _ -> LinuxNative.SyscallResult.Error(NativeConstants.EAGAIN, -1L) }
        }
        val reader = NotificationReader(engine, Logger.getAnonymousLogger())

        NativeArena.ofConfined().use { arena ->
            assertEquals(
                ReceiveOutcome.NotReady,
                reader.recvNotification(
                    TestFileDescriptors.replace<FileDescriptorRole.SeccompNotif>(11),
                    arena.allocate(io.mazewall.ffi.Layouts.SECCOMP_NOTIF),
                ),
            )
        }
    }
}
