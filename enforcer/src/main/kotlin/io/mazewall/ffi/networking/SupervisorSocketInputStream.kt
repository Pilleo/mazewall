package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import java.io.InputStream

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

    override fun read(): Int {
        while (true) {
            val res = LinuxNative.memory.read(socketFd, readBuf, 1)
            when (res) {
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value <= 0) return -1
                    return readBuf.readByte(0L).toInt() and BYTE_MASK
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (res.errno == EINTR) continue
                    return -1
                }
            }
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val count = Math.min(len.toLong(), BUFFER_SIZE.toLong())
        var result = -1
        var done = false
        while (!done) {
            val res = LinuxNative.memory.read(socketFd, multiBuf, count)
            when (res) {
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value > 0) {
                        val actualLen = res.value.toInt()
                        ManagedSegment.copy(multiBuf, 0L, b, off, actualLen)
                        result = actualLen
                    }
                    done = true
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (res.errno != EINTR) {
                        done = true
                    }
                }
            }
        }
        return result
    }

    override fun close() {
        LinuxNative.fileSystem.close(socketFd)
    }
}
