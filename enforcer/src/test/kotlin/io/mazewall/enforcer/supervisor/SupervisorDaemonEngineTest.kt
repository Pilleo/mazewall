package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeNetworking
import io.mazewall.MockNativeMemory
import io.mazewall.NativeTransaction
import io.mazewall.RealNativeEngine
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

class SupervisorDaemonEngineTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.setEngine(RealNativeEngine)
    }

    @Test
    fun `processConnectionStep retries on EINTR during ACK write`() {
        var writeCalls = 0
        val mockMemory = object : MockNativeMemory() {
            context(_: NativeTransaction)
            override fun write(
                fd: FileDescriptor<*, io.mazewall.core.FdState.Open>,
                buf: MemorySegment,
                count: Long
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                writeCalls++
                return if (writeCalls == 1) {
                    LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(1L)
                }
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val memory = mockMemory
        }

        LinuxNative.setEngine(mockEngine)

        val engine = SupervisorDaemonEngine("/tmp/test.sock")
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11)
        val connection = io.mazewall.ffi.networking.SeccompConnection.FdAttached(socketFd, listenerFd)

        Arena.ofConfined().use { arena ->
            val pollFd = io.mazewall.ffi.memory.PollFdSegment(arena.allocate(8))
            // We need to bypass the EINTR loop at the start of processConnectionStep which calls poll()
            // We can do this by making the mock engine return success for poll()
            mockEngine.pollResult = LinuxNative.SyscallResult.Success(1L)

            val result = engine.javaClass.getDeclaredMethods().find { it.name == "processConnectionStep" }?.let {
                it.isAccessible = true
                it.invoke(engine, arena, connection, socketFd, pollFd) as io.mazewall.ffi.networking.SeccompConnection?
            }

            assertNotNull(result)
            assertEquals(2, writeCalls)
        }
    }

    @Test
    fun `handleNewConnection retries on EINTR`() {
        var acceptCalls = 0
        val mockNetworking = object : MockNativeNetworking() {
            context(_: NativeTransaction)
            override fun accept(
                sockfd: FileDescriptor<*, io.mazewall.core.FdState.Open>,
                addr: MemorySegment,
                addrlen: MemorySegment
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                acceptCalls++
                return if (acceptCalls == 1) {
                    LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
                } else {
                    // Fail with something else to stop the loop after success if we don't want to deal with executor
                    LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
                }
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val networking = mockNetworking
        }

        LinuxNative.setEngine(mockEngine)

        val engine = SupervisorDaemonEngine("/tmp/test.sock")
        val serverFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(5)

        engine.javaClass.getDeclaredMethod(
            "handleNewConnection",
            FileDescriptor::class.java
        ).let {
            it.isAccessible = true
            it.invoke(engine, serverFd)
        }

        assertEquals(2, acceptCalls)
    }
}
