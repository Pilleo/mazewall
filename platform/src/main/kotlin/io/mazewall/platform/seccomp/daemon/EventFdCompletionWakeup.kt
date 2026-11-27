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
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.fd.recordDescriptorAcquisition
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg

/**
 * Session-owned Linux eventfd used to wake the daemon reactor after off-reactor preparation.
 * The counter coalesces repeated completions; the reactor drains it before inspecting pending work.
 */
internal sealed interface SessionRetirement {
    data object Complete : SessionRetirement

    data object Pending : SessionRetirement

    data class Unknown(
        val failure: Throwable,
    ) : SessionRetirement
}

internal interface SessionCompletionWakeup :
    NotificationCompletionWakeup,
    AutoCloseable {
    val pollFd: Int

    fun drain()

    @Suppress("TooGenericExceptionCaught") // default conservatively preserves any unknown close failure
    fun retire(): SessionRetirement =
        try {
        close()
        SessionRetirement.Complete
    } catch (failure: Throwable) {
        SessionRetirement.Unknown(failure)
    }
}

internal class EventFdCompletionWakeup private constructor(
    private val engine: NativeEngine,
    private val fd: FileDescriptor<FileDescriptorRole.Generic, FdState.Open, FdOwnership.Owned>,
    private val arena: NativeArena,
    private val writeBuffer: ManagedSegment,
    private val readBuffer: ManagedSegment,
) : SessionCompletionWakeup {
    private enum class Phase { OPEN, CLOSING, IN_FLIGHT, COMPLETE, UNKNOWN }

    private val gate = Any()
    private var phase = Phase.OPEN
    private var users = 0
    private var failure: Throwable? = null

    init {
        writeBuffer.writeLong(0L, 1L)
    }

    override val pollFd: Int get() = fd.value

    override fun signal() {
        if (!enter()) return
        try {
            when (val result = engine.memory.write(fd, writeBuffer, COUNTER_BYTES)) {
                is LinuxNative.SyscallResult.Success -> check(result.value == COUNTER_BYTES) {
                    "eventfd completion signal short write: ${result.value}"
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (result.errno != NativeConstants.EAGAIN) result.throwErrno("eventfd completion signal")
                }
            }
        } finally {
            leave()
        }
    }

    override fun drain() {
        if (!enter()) return
        try {
            while (true) {
                when (val result = engine.memory.read(fd, readBuffer, COUNTER_BYTES)) {
                    is LinuxNative.SyscallResult.Success -> {
                        check(result.value == COUNTER_BYTES) { "eventfd completion drain short read: ${result.value}" }
                        check(readBuffer.readLong(0L) > 0L) { "eventfd completion drain returned zero" }
                    }
                    is LinuxNative.SyscallResult.Error -> {
                        if (result.errno == NativeConstants.EAGAIN) return
                        result.throwErrno("eventfd completion drain")
                    }
                }
            }
        } finally {
            leave()
        }
    }

    private fun enter(): Boolean =
        synchronized(gate) {
        if (phase != Phase.OPEN) return false
        users = Math.addExact(users, 1)
        true
    }

    private fun leave() {
        synchronized(gate) {
            check(users > 0)
            users--
        }
    }

    override fun close() {
        when (val outcome = retire()) {
            SessionRetirement.Complete -> Unit
            SessionRetirement.Pending -> error("eventfd completion close is pending")
            is SessionRetirement.Unknown -> throw outcome.failure
        }
    }

    @Suppress("TooGenericExceptionCaught") // retain the exact dispatched close or arena failure before publication
    override fun retire(): SessionRetirement {
        val prior = synchronized(gate) {
            when (phase) {
                Phase.COMPLETE -> SessionRetirement.Complete
                Phase.UNKNOWN -> SessionRetirement.Unknown(checkNotNull(failure))
                Phase.IN_FLIGHT -> SessionRetirement.Pending
                Phase.OPEN, Phase.CLOSING -> {
                    phase = Phase.CLOSING
                    if (users != 0) {
                        SessionRetirement.Pending
                    } else {
                        phase = Phase.IN_FLIGHT
                        null
                    }
                }
            }
        }
        if (prior != null) return prior
        return try {
            closeDescriptor(engine, fd)
            arena.close()
            synchronized(gate) { phase = Phase.COMPLETE }
            SessionRetirement.Complete
        } catch (problem: Throwable) {
            synchronized(gate) {
                failure = problem
                phase = Phase.UNKNOWN
            }
            SessionRetirement.Unknown(problem)
        }
    }

    internal companion object {
        private const val COUNTER_BYTES = 8L
        private const val EFD_NONBLOCK = NativeConstants.O_NONBLOCK
        private const val EFD_CLOEXEC = NativeConstants.O_CLOEXEC

        fun create(
            engine: NativeEngine,
            allocateBuffer: (NativeArena) -> ManagedSegment = { it.allocate(COUNTER_BYTES) },
        ): EventFdCompletionWakeup {
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
            val fd = result.getFdOrThrow("eventfd2 completion wakeup", FileDescriptorRole.Generic)
            return ResourceRetirement.runWithRollback(
                action = {
                    val arena = NativeArena.ofShared()
                    ResourceRetirement.runWithRollback(
                        action = { EventFdCompletionWakeup(engine, fd, arena, allocateBuffer(arena), allocateBuffer(arena)) },
                        rollback = arena::close,
                    )
                },
                rollback = { closeDescriptor(engine, fd) },
            )
        }

        private fun closeDescriptor(
            engine: NativeEngine,
            fd: FileDescriptor<FileDescriptorRole.Generic, FdState.Open, FdOwnership.Owned>,
        ) {
            when (val result = engine.fileSystem.close(fd)) {
                is LinuxNative.SyscallResult.Success -> fd.markClosed()
                is LinuxNative.SyscallResult.Error -> result.throwErrno("close eventfd completion wakeup")
            }
        }
    }
}
