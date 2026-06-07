package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

internal class NativeSocketInputStream(
    private val socketFd: Int,
    private val arena: Arena,
    private val nativeRead: (Int, MemorySegment, Long) -> LinuxNative.SyscallResult = LinuxNative::read,
    private val nativeClose: (Int) -> Unit = LinuxNative::close,
) : InputStream() {
    private val readBuf = arena.allocate(1)
    private val multiBuf = arena.allocate(8192)

    override fun read(): Int {
        while (true) {
            val res = nativeRead(socketFd, readBuf, 1)
            if (res.returnValue <= 0) {
                if (res.returnValue < 0 && res.errno == 4) continue // EINTR
                return -1
            }
            return readBuf.get(ValueLayout.JAVA_BYTE, 0L).toInt() and 0xFF
        }
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        val count = Math.min(len.toLong(), 8192L)
        while (true) {
            val res = nativeRead(socketFd, multiBuf, count)
            if (res.returnValue <= 0) {
                if (res.returnValue < 0 && res.errno == 4) continue // EINTR
                return -1
            }
            val actualLen = res.returnValue.toInt()
            MemorySegment.copy(multiBuf, ValueLayout.JAVA_BYTE, 0L, b, off, actualLen)
            return actualLen
        }
    }

    override fun close() {
        nativeClose(socketFd)
    }
}
