package io.mazewall.platform.seccomp.daemon

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.recordDescriptorAcquisition
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-owned Linux eventfd used to wake the daemon reactor after off-reactor preparation.
 * The counter coalesces repeated completions; the reactor drains it before inspecting pending work.
 */
internal interface SessionCompletionWakeup :
    NotificationCompletionWakeup,
    AutoCloseable {
    val pollFd: Int

    fun drain()
}

internal class EventFdCompletionWakeup private constructor(
    private val engine: NativeEngine,
    private val fd: FileDescriptor<FileDescriptorRole.Generic, FdState.Open, FdOwnership.Owned>,
) : SessionCompletionWakeup {
    private val arena = NativeArena.ofShared()
    private val writeBuffer: ManagedSegment = arena.allocate(COUNTER_BYTES)
    private val readBuffer: ManagedSegment = arena.allocate(COUNTER_BYTES)
    private val closed = AtomicBoolean(false)

    init {
        writeBuffer.writeLong(0L, 1L)
    }

    override val pollFd: Int get() = fd.value

    override fun signal() {
        if (closed.get()) return
        when (val result = engine.memory.write(fd, writeBuffer, COUNTER_BYTES)) {
            is LinuxNative.SyscallResult.Success -> check(result.value == COUNTER_BYTES) {
                "eventfd completion signal short write: ${result.value}"
            }
            is LinuxNative.SyscallResult.Error -> {
                if (result.errno != NativeConstants.EAGAIN) {
                    result.throwErrno("eventfd completion signal")
                }
            }
        }
    }

    override fun drain() {
        while (!closed.get()) {
            when (val result = engine.memory.read(fd, readBuffer, COUNTER_BYTES)) {
                is LinuxNative.SyscallResult.Success -> {
                    check(result.value == COUNTER_BYTES) {
                        "eventfd completion drain short read: ${result.value}"
                    }
                    check(readBuffer.readLong(0L) > 0L) { "eventfd completion drain returned zero" }
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (result.errno == NativeConstants.EAGAIN) return
                    result.throwErrno("eventfd completion drain")
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                when (val result = engine.fileSystem.close(fd)) {
                    is LinuxNative.SyscallResult.Success -> Unit
                    is LinuxNative.SyscallResult.Error -> result.throwErrno("close eventfd completion wakeup")
                }
            } finally {
                FileDescriptor.closedView(fd)
                arena.close()
            }
        }
    }

    internal companion object {
        private const val COUNTER_BYTES = 8L
        private const val EFD_NONBLOCK = NativeConstants.O_NONBLOCK
        private const val EFD_CLOEXEC = NativeConstants.O_CLOEXEC

        fun create(engine: NativeEngine): EventFdCompletionWakeup {
            val syscallNumber = when (Arch.current().name) {
                "amd64" -> 290L
                "aarch64" -> 19L
                else -> throw UnsupportedOperationException("eventfd2 is unavailable on ${Arch.current().name}")
            }
            val flags = EFD_NONBLOCK or EFD_CLOEXEC.toLong()
            val result = engine.raw
                .syscall(
                syscallNumber,
                NativeArg.LongArg(0L),
                NativeArg.LongArg(flags),
            ).recordDescriptorAcquisition()
            return EventFdCompletionWakeup(engine, result.getFdOrThrow("eventfd2 completion wakeup", FileDescriptorRole.Generic))
        }
    }
}
