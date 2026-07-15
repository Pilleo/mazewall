package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.onFailure
import io.mazewall.recover
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

import io.mazewall.ffi.memory.readByte

internal class SupervisorSocketInputStream(
    private val socketFd: FileDescriptor<*, FdState.Open>,
    private val arena: Arena,
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
            val resValue = LinuxNative.withTransaction {
                LinuxNative.memory.read(socketFd, readBuf, 1)
                    .onFailure { errno, rawValue ->
                        if ((errno != EINTR && rawValue != 123456789L)) {
                            System.err.println("[SUPERVISOR-SOCKET] read failed: errno=$errno")
                        }
                    }.recover { errno, rawValue -> if ((errno == EINTR || rawValue == 123456789L)) -1000L else -1L }
            }
            if (resValue == -1000L) continue
            if (resValue <= 0) return -1
            return readBuf.readByte(0L).toInt() and BYTE_MASK
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val count = Math.min(len.toLong(), BUFFER_SIZE.toLong())
        var result = -1
        var done = false
        while (!done) {
            val resValue = LinuxNative.withTransaction {
                LinuxNative.memory.read(socketFd, multiBuf, count)
                    .onFailure { errno, rawValue ->
                        if ((errno != EINTR && rawValue != 123456789L)) {
                            System.err.println("[SUPERVISOR-SOCKET] read multiple failed: errno=$errno")
                        }
                    }.recover { errno, rawValue -> if ((errno == EINTR || rawValue == 123456789L)) -1000L else -1L }
            }
            if (resValue == -1000L) continue
            if (resValue > 0) {
                val actualLen = resValue.toInt()
                MemorySegment.copy(multiBuf, ValueLayout.JAVA_BYTE, 0L, b, off, actualLen)
                result = actualLen
            }
            done = true
        }
        return result
    }

    override fun close() {
        LinuxNative.fileSystem.close(socketFd)
    }
}
