package io.mazewall.ffi.networking

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import java.io.InputStream
import java.io.InterruptedIOException

internal class SupervisorSocketInputStream(
    private val socketFd: FileDescriptor<*, FdState.Open>,
    private val arena: NativeArena,
) : InputStream() {
    private val readBuf = arena.allocate(1)
    private val multiBuf = arena.allocate(BUFFER_SIZE.toLong())

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val BYTE_MASK = 0xFF
        private const val EINTR = 4
    }

    private fun handleBackoff(eintrCount: Int) {
        if (eintrCount <= 1) return
        if (eintrCount > 3) {
            try {
                Thread.sleep(1)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during EINTR backoff sleep")
            }
        } else {
            Thread.yield()
        }
    }

    override fun read(): Int {
        var eintrCount = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during supervisor socket read")
            }
            val res = LinuxNative.memory.read(socketFd, readBuf, 1)
            when (res) {
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value <= 0) return -1
                    return readBuf.readByte(0L).toInt() and BYTE_MASK
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (res.errno == EINTR) {
                        eintrCount++
                        handleBackoff(eintrCount)
                        continue
                    }
                    return -1
                }
            }
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val count = Math.min(len.toLong(), BUFFER_SIZE.toLong())
        var eintrCount = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during supervisor socket bulk read")
            }
            val res = LinuxNative.memory.read(socketFd, multiBuf, count)
            when (res) {
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value > 0) {
                        val actualLen = res.value.toInt()
                        ManagedSegment.copy(multiBuf, 0L, b, off, actualLen)
                        return actualLen
                    }
                    return -1
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (res.errno != EINTR) return -1
                    eintrCount++
                    handleBackoff(eintrCount)
                }
            }
        }
    }

    override fun close() {
        // The supervisor session owns the socket descriptor and performs its lifecycle cleanup.
    }
}
