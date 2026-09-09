package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockSocketManager
import io.mazewall.core.Arch
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.TraceeMemoryReader
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

internal typealias NativeResult = LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

/** A scripted native boundary: no fake descriptor can reach a real closer or process. */
internal class SupervisorBoundaryFixture {
    val engine = MockNativeEngine()
    val sockets = MockSocketManager()
    val socket = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(51000)
    val listener = FileDescriptor.replace<FileDescriptorRole.SeccompNotif>(51001)
    val replies = CopyOnWriteArrayList<KernelReply>()
    val closed = CopyOnWriteArrayList<Int>()
    val replied = CountDownLatch(1)
    var replyResult: NativeResult = success(0)
    var injectionResult: NativeResult = success(61)
    var decision = 1
    var responseId = 42L
    var error = 13
    var frameLength = Layouts.SUPERVISOR_RESPONSE_SIZE
    var pathLength: Int? = null
    var readCount = 0
    var writeCount = 0

    init {
        engine.pollResult = success(1)
        engine.onPoll = { fds, _, _ ->
            PollFdSegment.of(fds).setRevents(NativeConstants.POLLIN)
            engine.pollResult
        }
        engine.memory.onWrite = { _, _, size ->
            writeCount++
            success(size)
        }
        engine.memory.onRead = { _, buffer, size ->
            readCount++
            if (size == 1L) {
                buffer.writeByte(0, 0)
            } else if (frameLength == Layouts.SUPERVISOR_RESPONSE_SIZE) {
                val response = SupervisorResponseSegment.of(buffer)
                response.setId(responseId)
                response.setDecision(decision.toByte())
                response.setErrorNr(error)
                pathLength?.let { buffer.writeInt(Layouts.SUPERVISOR_RESPONSE_PATH_LEN_OFFSET, it) }
            }
            success(minOf(frameLength, size))
        }
        engine.fileSystem.openResult = success(51002)
        engine.fileSystem.onClose = { fd ->
            closed += fd.value
            success(0)
        }
        engine.networking.socketResult = success(51003)
        engine.networking.acceptResult = success(51004)
        engine.process.onPidfdOpen = { _, _ -> success(51005) }
        engine.process.onPidfdGetFd = { _, _, _ -> success(51006) }
        engine.onIoctl = { _, command, buffer ->
            when (command) {
                IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND.code -> {
                    replies += KernelReply(
                        buffer.readLong(0),
                        buffer.readInt(16),
                        buffer.readInt(20),
                        false,
                    )
                    replied.countDown()
                    replyResult
                }
                IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD.code -> {
                    if (buffer.readInt(8) and NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt() != 0) {
                        replies += KernelReply(buffer.readLong(0), 0, 0, true)
                        replied.countDown()
                    }
                    injectionResult
                }
                else -> success(0)
            }
        }
    }

    fun handler() = SupervisorSessionHandler(socket, listener, engine, sockets, SupervisorRoutePreparation(memory) { 0 })

    private val memory = object : TraceeMemoryReader {
        context(arena: NativeArena) override fun readString(
            tid: Tid,
            remoteAddr: Long,
            maxLen: Int,
        ): String? = if (remoteAddr == 4096L) "/tmp/task8-target" else null

        context(arena: NativeArena) override fun readBytes(
            tid: Tid,
            remoteAddr: Long,
            len: Int,
        ): ByteArray? =
            when (len) {
                24 -> ByteArray(24)
                8 -> byteArrayOf(2, 0, 0, 80, 127, 0, 0, 1)
                else -> null
            }

        context(arena: NativeArena) override fun resolveLink(
            tid: Tid,
            link: String,
        ): String? = null
    }

    fun notification(
        arena: NativeArena,
        nr: Int,
    ): ManagedSegment {
        val buffer = arena.allocate(Layouts.SECCOMP_NOTIF)
        buffer.writeLong(0, 42)
        buffer.writeInt(8, 1)
        buffer.writeInt(16, nr)
        buffer.writeInt(20, Arch.AMD64.audit)
        val args = when (nr) {
            Arch.AMD64.open -> longArrayOf(4096, 0, 0)
            Arch.AMD64.openat -> longArrayOf(-100, 4096, 0, 0)
            Arch.AMD64.openat2 -> longArrayOf(-100, 4096, 8192, 24)
            Arch.AMD64.connect -> longArrayOf(4, 8192, 8)
            Arch.AMD64.accept, Arch.AMD64.accept4 -> longArrayOf(4, 0, 0, 0)
            else -> longArrayOf(0, 0, 0)
        }
        args.forEachIndexed { index, value -> buffer.writeLong(32L + index * 8, value) }
        return buffer
    }

    fun context(
        arena: NativeArena,
        nr: Int = Arch.AMD64.clone,
    ): SupervisorRouteContext {
        val path = if (nr in listOf(Arch.AMD64.open, Arch.AMD64.openat, Arch.AMD64.openat2)) "/tmp/task8-target" else null
        val address = byteArrayOf(2, 0, 0, 80, 127, 0, 0, 1)
        return SupervisorRouteContext(
            JvmVerdictRequest(42, NotifHeader(nr, Tid(1), Arch.AMD64, Arch.AMD64.audit, 0, LongArray(6)), path, address),
            SyscallArguments(path, address, openHow = OpenHow(io.mazewall.core.OpenFlags(0), 0, 0)),
            arena.allocate(Layouts.SECCOMP_NOTIF_RESP),
        )
    }

    companion object {
        fun success(value: Long): NativeResult = LinuxNative.SyscallResult.Success(value)

        fun failure(errno: Int): NativeResult = LinuxNative.SyscallResult.Error(errno, -1)
    }
}

internal data class KernelReply(
    val id: Long,
    val error: Int,
    val flags: Int,
    val injected: Boolean,
)
