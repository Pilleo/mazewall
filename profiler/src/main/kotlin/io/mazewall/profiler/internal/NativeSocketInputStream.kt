package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * A custom [InputStream] implementation that reads from a native socket file descriptor.
 *
 * This implementation provides robust thread interruption safety by checking the current
 * thread's interrupted status at the beginning of each loop iteration. Additionally, it
 * mitigates high-CPU tight-loop spinning under active signal/interruption spikes by employing
 * a progressive backoff throttling strategy (yielding and sleeping) on consecutive [EINTR] errors.
 */
internal class NativeSocketInputStream(
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
        if (eintrCount > 1) {
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
    }

    override fun read(): Int {
        var eintrCount = 0
        while (true) {
            // Guard against tight-loop CPU spinning under interruption spikes by checking interrupted state
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during native socket read")
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

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        return readWithRetry(b, off, len)
    }

    private fun readWithRetry(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val count = Math.min(len.toLong(), BUFFER_SIZE.toLong())
        var eintrCount = 0
        while (true) {
            // Guard against tight-loop CPU spinning under interruption spikes by checking interrupted state
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during native socket readWithRetry")
            }
            val res = LinuxNative.memory.read(socketFd, multiBuf, count)
            when (res) {
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value <= 0) return -1
                    val actualLen = res.value.toInt()
                    ManagedSegment.copy(multiBuf, 0L, b, off, actualLen)
                    return actualLen
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

    override fun close() {
        // Do not close socketFd here, as its lifecycle is managed entirely by ProfilerTraceListener
    }
}
