package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import io.mazewall.ffi.memory.readInt

class SupervisorSocketInputStreamTest {

    @Test
    fun `read loop aborts immediately when thread is interrupted`() {
        val arena = io.mazewall.ffi.memory.NativeArena.global()
        // Given an open socket pair so read() blocks waiting for data
        val sv = arena.allocate(8L)
        val res = LinuxNative.networking.socketpair(1, 1, 0, sv) // AF_UNIX=1, SOCK_STREAM=1
        if (res is LinuxNative.SyscallResult.Error) {
            throw AssertionError("socketpair failed: \${res.errno}")
        }
        
        val readFd = FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>(sv.readInt(0L))
        val writeFd = FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>(sv.readInt(4L))
        
        val inputStream = SupervisorSocketInputStream(readFd, arena)

        val exceptionRef = AtomicReference<Throwable>()
        val readThread = Thread {
            try {
                Thread.currentThread().interrupt()
                inputStream.read()
            } catch (e: Throwable) {
                exceptionRef.set(e)
            }
        }

        readThread.start()
        
        // Then it should terminate quickly
        readThread.join(5000)
        assertFalse(readThread.isAlive, "Thread did not terminate after interrupt")

        val exception = exceptionRef.get()
        assertNotNull(exception, "Expected an exception to be thrown")
        assertTrue(exception is InterruptedIOException, "Expected InterruptedIOException, got ${exception?.javaClass}")
        assertEquals("Thread interrupted while reading from Supervisor socket", exception?.message)
        
        LinuxNative.fileSystem.close(readFd)
        LinuxNative.fileSystem.close(writeFd)
    }

    @Test
    fun `read array loop aborts immediately when thread is interrupted`() {
        val arena = io.mazewall.ffi.memory.NativeArena.global()
        // Given an open socket pair so read() blocks waiting for data
        val sv = arena.allocate(8L)
        val res = LinuxNative.networking.socketpair(1, 1, 0, sv) // AF_UNIX=1, SOCK_STREAM=1
        if (res is LinuxNative.SyscallResult.Error) {
            throw AssertionError("socketpair failed: \${res.errno}")
        }
        
        val readFd = FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>(sv.readInt(0L))
        val writeFd = FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>(sv.readInt(4L))
        
        val inputStream = SupervisorSocketInputStream(readFd, arena)

        val exceptionRef = AtomicReference<Throwable>()
        val readThread = Thread {
            try {
                Thread.currentThread().interrupt()
                val buf = ByteArray(10)
                inputStream.read(buf, 0, 10)
            } catch (e: Throwable) {
                exceptionRef.set(e)
            }
        }

        readThread.start()
        
        // Then it should terminate quickly
        readThread.join(5000)
        assertFalse(readThread.isAlive, "Thread did not terminate after interrupt")

        val exception = exceptionRef.get()
        assertNotNull(exception, "Expected an exception to be thrown")
        assertTrue(exception is InterruptedIOException, "Expected InterruptedIOException, got ${exception?.javaClass}")
        assertEquals("Thread interrupted while reading from Supervisor socket", exception?.message)
        
        LinuxNative.fileSystem.close(readFd)
        LinuxNative.fileSystem.close(writeFd)
    }
}
