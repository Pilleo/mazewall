package io.mazewall.ffi.networking

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.close
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeMemory
import io.mazewall.platform.syscall.ErrnoMapping
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * EINTR-safe reader over an externally owned descriptor and caller-owned confined arena.
 * The public primary constructor captures the current native memory dependency once;
 * the additive constructor captures an explicitly supplied dependency instead.
 *
 * [NativeStreamOwnership.Borrowed] (the default) does not release [socketFd] or [arena]
 * on [close], so [java.io.InputStream.use] is safe. [NativeStreamOwnership.OwnsArena]
 * and [NativeStreamOwnership.OwnsDescriptorAndArena] close those owners from [close].
 */
internal enum class NativeStreamOwnership {
    Borrowed,
    OwnsArena,
    OwnsDescriptorAndArena,
}

internal open class NativeDescriptorInputStream(
    private val socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    private val arena: NativeArena,
    private val description: String = "native descriptor",
) : InputStream() {
    // Assigned only during construction: an extra primary parameter would break Kotlin metadata.
    private var memory: NativeMemory = LinuxNative.snapshot().memory
    private var ownership: NativeStreamOwnership = NativeStreamOwnership.Borrowed

    public constructor(
        socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        arena: NativeArena,
        description: String,
        memory: NativeMemory,
        ownership: NativeStreamOwnership = NativeStreamOwnership.Borrowed,
    ) : this(socketFd, arena, description) {
        this.memory = memory
        this.ownership = ownership
    }

    private val readBuf = arena.allocate(1)
    private val multiBuf = arena.allocate(BUFFER_SIZE.toLong())

    override fun read(): Int = readWithRetry(readBuf, 1, "read") { readBuf.readByte(0).toInt() and BYTE_MASK }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        java.util.Objects.checkFromIndexSize(off, len, b.size)
        if (len == 0) return 0
        return readWithRetry(multiBuf, minOf(len, BUFFER_SIZE).toLong(), "bulk read") { read ->
            ManagedSegment.copy(multiBuf, 0, b, off, read.toInt())
            read.toInt()
        }
    }

    private fun readWithRetry(
        buffer: ManagedSegment,
        count: Long,
        operation: String,
        onRead: (Long) -> Int,
    ): Int {
        var eintrCount = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw interrupted(operation)
            }
            when (val result = memory.read(socketFd, buffer, count)) {
                is LinuxNative.SyscallResult.Success -> {
                    return successfulRead(result.value, count, onRead)
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (result.errno != EINTR) throw nativeReadFailure(result.errno, result.rawValue, operation)
                    eintrCount++
                    backoff(eintrCount)
                }
            }
        }
    }

    private fun successfulRead(
        value: Long,
        count: Long,
        onRead: (Long) -> Int,
    ): Int {
        if (value < 0 || value > count) throw IOException("Invalid native read result $value for count $count")
        if (value == 0L) return -1
        return onRead(value)
    }

    private fun nativeReadFailure(
        errno: Int,
        rawValue: Long,
        operation: String,
    ): IOException {
        val symbol = ErrnoMapping.getSymbolicName(errno)?.let { " ($it)" }.orEmpty()
        return IOException("$description $operation failed with errno=$errno$symbol (raw return=$rawValue)")
    }

    private fun interrupted(operation: String): InterruptedIOException {
        Thread.currentThread().interrupt()
        return InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during $description $operation")
    }

    private fun backoff(eintrCount: Int) {
        if (eintrCount <= FIRST_RETRY) return
        if (eintrCount > YIELD_RETRIES) {
            try {
                Thread.sleep(BACKOFF_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during EINTR backoff sleep")
            }
        } else {
            Thread.yield()
        }
    }

    override fun close() {
        when (ownership) {
            NativeStreamOwnership.Borrowed -> Unit
            NativeStreamOwnership.OwnsArena -> if (arena.isAlive) arena.close()
            NativeStreamOwnership.OwnsDescriptorAndArena -> {
                try {
                    if (socketFd.isLiveForIo()) socketFd.close()
                } finally {
                    if (arena.isAlive) arena.close()
                }
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8192
        const val BYTE_MASK = 0xFF
        const val EINTR = 4
        const val FIRST_RETRY = 1
        const val YIELD_RETRIES = 3
        const val BACKOFF_MILLIS = 1L
    }
}
