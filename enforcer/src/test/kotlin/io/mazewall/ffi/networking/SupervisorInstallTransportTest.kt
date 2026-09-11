package io.mazewall.ffi.networking

import io.mazewall.BpfFilter
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeNetworking
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.testAcquisition
import io.mazewall.ffi.memory.ManagedSegment
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SupervisorInstallTransportTest {
    @Test fun `persistent send interruption exhausts eight attempts and closes each acquisition once`() {
        val sends = AtomicInteger()
        val closed = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val networking = object : MockNativeNetworking() {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                if (sends.incrementAndGet() <= 8) LinuxNative.SyscallResult.Error(4, -1L) else LinuxNative.SyscallResult.Success(1L)
        }
        val native = MockNativeEngine(networking = networking).apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(62111L).testAcquisition() }
            networking.socketResult = LinuxNative.SyscallResult.Success(62112L)
            pollResult = LinuxNative.SyscallResult.Success(1L)
            fileSystem.onClose = { fd ->
                closed += fd.value
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(native)
        try {
            assertFailsWith<IllegalStateException> {
                SupervisorSeccompNotifInstaller.installOwned(
                    native,
                    true,
                    "/unused",
                    BpfFilter.build(Arch.current(), Policy.builder().build().definition),
                    false,
                    onSocketConnected = { _, _ -> error("send never completed") },
                )
            }
            assertEquals(8, sends.get())
            assertEquals(1, closed.count { it == 62111 })
            assertEquals(1, closed.count { it == 62112 })
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test fun `private connect permission failure is terminal without retries`() {
        val connects = AtomicInteger()
        val native = MockNativeEngine().apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(62121L).testAcquisition() }
            networking.socketResult = LinuxNative.SyscallResult.Success(62122L)
            networking.onConnect = { _, _, _ ->
                connects.incrementAndGet()
                LinuxNative.SyscallResult.Error(13, -1L)
            }
        }
        LinuxNative.setEngine(native)
        try {
            val failure = assertFailsWith<IllegalStateException> {
                SupervisorSeccompNotifInstaller.installOwned(
                    native,
                    true,
                    "/unused",
                    BpfFilter.build(Arch.current(), Policy.builder().build().definition),
                    false,
                    onSocketConnected = { _, _ -> error("connect denied") },
                )
            }
            assertTrue(failure.message.orEmpty().contains("errno=13"))
            assertEquals(1, connects.get())
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test fun `default installation transport uses nonblocking connect and send`() {
        val socketFlags = AtomicInteger()
        val messageFlags = AtomicInteger()
        val networking = object : MockNativeNetworking() {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                messageFlags.set(flags)
                return LinuxNative.SyscallResult.Success(1L)
            }
        }
        val native = MockNativeEngine(networking = networking).apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(62101L).testAcquisition() }
            networking.onSocket = { _, type, _ ->
                socketFlags.set(type)
                LinuxNative.SyscallResult.Success(62102L)
            }
        }
        LinuxNative.setEngine(native)
        try {
            SupervisorSeccompNotifInstaller.installOwned(
                native = native,
                userNotifSupported = true,
                socketPath = "/unused",
                filter = BpfFilter.build(Arch.current(), Policy.builder().build().definition),
                processWide = false,
                onSocketConnected = { socket, ready ->
                    native.fileSystem.close(socket)
                    ready.countDown()
                },
            )
            assertEquals(0x800, socketFlags.get() and 0x800, "private control connect must not block")
            assertEquals(0x40, messageFlags.get() and 0x40, "SCM_RIGHTS send must use MSG_DONTWAIT")
        } finally {
            LinuxNative.resetToDefault()
        }
    }
}
