package io.mazewall.ffi.networking

import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperRejection
import io.mazewall.enforcer.internal.supervisor.ConfinedRejectReason
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.readLong
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeNetworking
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import java.io.EOFException
import java.io.IOException
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/** Called only by the connection's retained I/O owner; descriptor closure belongs to its lifetime. */
internal class ConfinedHelperChannel(
    private val native: NativeEngine,
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val cancelled: () -> Boolean,
    private val healthy: () -> Boolean,
) {
    data class ReceivedFrame(
        val frame: ConfinedHelperProtocol.Frame,
        val localDeadline: ConfinedDeadline,
    )

    /** The connection owner verifies the common time domain before accepting wire deadlines. */
    fun receiveFrame(localCap: ConfinedDeadline): ReceivedFrame =
        exchange {
        val bytes = receive(ConfinedHelperProtocol.HEADER_BYTES, localCap)
        val header = ConfinedHelperProtocol.header(bytes)
        val deadline = localCap.accept(header.deadline, guard(localCap))
        val payload = receive(header.payloadBytes, deadline)
        ReceivedFrame(ConfinedHelperProtocol.decode(bytes + payload), deadline)
    }

    fun pollFrame(localCap: ConfinedDeadline? = null): ReceivedFrame? =
        exchange {
        val cap = localCap ?: ConfinedDeadline.mint(native) { 5_000_000_000L }
        if (poll(NativeConstants.POLLIN, cap)) receiveFrame(cap) else null
    }

    fun sendFrame(
        frame: ConfinedHelperProtocol.Frame,
        localCap: ConfinedDeadline = frame.deadline,
    ): Unit =
        exchange {
        send(ConfinedHelperProtocol.encode(frame), localCap.accept(frame.deadline, guard(localCap)))
    }

    private var terminal = false

    /** Stage waits use the same native clock and terminal checks as socket effects. */
    fun checkDeadline(deadline: ConfinedDeadline): Long = exchange { guard(deadline) }

    /** AF_UNIX backlog retries stay on the acquired nonblocking socket and never renew the cap. */
    fun connect(
        socketPath: String,
        deadline: ConfinedDeadline,
    ): Unit =
        exchange {
            require(socketPath.isNotEmpty() && '\u0000' !in socketPath) { "Invalid helper socket path" }
            NativeArena.ofConfined().use { arena ->
                val address = SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)
                var connected = false
                while (!connected) {
                    guard(deadline)
                    val result = native.networking.connect(socket, ConfinedSegment(address.segment), SupervisorSocketUtils.SOCKADDR_UN_SIZE)
                    val value = progress(result, "connect helper control")
                    val now = guard(deadline)
                    if (value == null) {
                        // A backlog-full UNIX socket is not connecting; POLLOUT/HUP is not success.
                        val wait = native.raw.poll(ConfinedSegment(address.segment), 0, deadline.pollMillis(now))
                        progress(wait, "wait for helper control backlog")
                        guard(deadline)
                    } else {
                        check(value == 0L) { "Invalid helper connect result" }
                        connected = true
                    }
                }
            }
        }

    /** recvfrom(MSG_PEEK) leaves SCM_RIGHTS queued; recvmsg(MSG_PEEK) would acquire duplicates. */
    fun peekByte(deadline: ConfinedDeadline): Int =
        exchange {
            NativeArena.ofConfined().use { arena ->
                val byte = arena.allocate(1)
                var observed: Int? = null
                while (observed == null) {
                    await(NativeConstants.POLLIN, deadline)
                    guard(deadline)
                    val result = native.raw.syscall(
                        Arch.current().recvfrom.toLong(),
                        NativeArg.FdArg(socket),
                        NativeArg.MemoryArg(byte),
                        NativeArg.LongArg(1),
                        NativeArg.IntArg(DONTWAIT or 0x02),
                        NativeArg.NullArg,
                        NativeArg.NullArg,
                    )
                    val count = progress(result, "peek helper control byte")
                    guard(deadline)
                    when (count) {
                        null -> Unit
                        0L -> throw EOFException("Helper peer closed before control prelude")
                        1L -> observed = byte.readByte(0).toInt() and BYTE_MASK
                        else -> throw IOException("Invalid helper peek progress: $count")
                    }
                }
                observed
            }
        }

    init {
        check(socket.isLiveForIo()) { "Helper socket has retired" }
        val flags = native.raw.fcntl(socket, NativeConstants.F_GETFL, 0).helperValue("get helper socket flags")
        check(native.raw.fcntl(socket, NativeConstants.F_SETFL, flags or NativeConstants.O_NONBLOCK).helperValue("set nonblocking helper socket") == 0L)
    }

    fun sendDescriptor(
        owner: SupervisorOwnedFd,
        deadline: ConfinedDeadline,
    ): Unit =
        exchange {
        check(owner.handle.isLiveForIo()) { "Helper transfer owner retired" }
        // Preserve native failure identity through the legacy Boolean utility's checked boundary.
        val checked = object : NativeNetworking by native.networking {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val result = native.networking.sendmsg(sockfd, msg, flags)
                progress(result, "send helper descriptor")
                guard(deadline)
                return result
            }
        }
        val sent = SupervisorSocketUtils.sendDescriptor(socket.value, owner.handle.value, checked) {
            await(NativeConstants.POLLOUT, deadline)
            guard(deadline)
        }
        if (!sent) throw IOException("Invalid helper descriptor send")
    }

    fun receiveDescriptor(deadline: ConfinedDeadline): SupervisorOwnedFd =
        exchange {
        NativeArena.ofConfined().use { scope ->
            val arena = scope.arena
            val data = arena.allocate(1)
            val control = arena.allocate(SupervisorSocketUtils.MSG_CONTROL_BUF_SIZE)
            val iov = IovecSegment(arena.allocate(Layouts.IOVEC))
            iov.setIovBase(data)
            iov.setIovLen(1)
            val message = MsghdrSegment(arena.allocate(Layouts.MSGHDR))
            message.setMsgIov(iov.segment)
            message.setMsgIovlen(1)
            message.setMsgControl(control)
            var received: SupervisorOwnedFd? = null
            while (received == null) {
                await(NativeConstants.POLLIN, deadline)
                control.fill(0)
                message.setMsgControllen(control.byteSize())
                message.setMsgFlags(0)
                guard(deadline)
                val result = native.networking.recvmsg(socket, ConfinedSegment(message.segment), DONTWAIT or NativeConstants.MSG_CMSG_CLOEXEC)
                val count = progress(result, "receive helper descriptor")
                if (count != null) {
                    val descriptor = SupervisorSocketUtils.validateReceivedDescriptor(count, message, control, FileDescriptorRole.Generic, native.fileSystem)
                        ?: throw IOException("Malformed helper descriptor receipt")
                    val owner = SupervisorOwnedFd(descriptor, native.fileSystem)
                    received = ResourceRetirement.runWithRollback({
                        if (data.get(ValueLayout.JAVA_BYTE, 0) != 0.toByte()) throw IOException("Invalid helper descriptor byte")
                        guard(deadline)
                        owner
                    }, owner::close)
                } else {
                    guard(deadline)
                }
            }
            received
        }
    }

    fun send(
        bytes: ByteArray,
        deadline: ConfinedDeadline,
    ): Unit =
        exchange {
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Helper write exceeds frame bound" }
        NativeArena.ofConfined().use { scope ->
            val arena = scope.arena
            val data = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)
            val iov = IovecSegment(arena.allocate(Layouts.IOVEC))
            val message = MsghdrSegment(arena.allocate(Layouts.MSGHDR))
            message.setMsgIov(iov.segment)
            message.setMsgIovlen(1)
            var sent = 0
            while (sent < bytes.size) {
                await(NativeConstants.POLLOUT, deadline)
                iov.setIovBase(data.asSlice(sent.toLong()))
                iov.setIovLen((bytes.size - sent).toLong())
                guard(deadline)
                val result = native.networking.sendmsg(socket, ConfinedSegment(message.segment), DONTWAIT or NOSIGNAL)
                val count = progress(result, "send helper frame")
                guard(deadline)
                if (count == null) continue
                if (count !in 1..(bytes.size - sent).toLong()) throw IOException("Invalid helper send progress: $count")
                sent += count.toInt()
            }
        }
    }

    fun receive(
        size: Int,
        deadline: ConfinedDeadline,
    ): ByteArray =
        exchange {
        require(size in 1..MAX_FRAME_BYTES) { "Helper read exceeds frame bound" }
        NativeArena.ofConfined().use { scope ->
            val arena = scope.arena
            val data = arena.allocate(size.toLong())
            val control = arena.allocate(SupervisorSocketUtils.MSG_CONTROL_BUF_SIZE)
            val iov = IovecSegment(arena.allocate(Layouts.IOVEC))
            val message = MsghdrSegment(arena.allocate(Layouts.MSGHDR))
            message.setMsgIov(iov.segment)
            message.setMsgIovlen(1)
            message.setMsgControl(control)
            var received = 0
            while (received < size) {
                await(NativeConstants.POLLIN, deadline)
                iov.setIovBase(data.asSlice(received.toLong()))
                iov.setIovLen((size - received).toLong())
                control.fill(0)
                message.setMsgControllen(control.byteSize())
                message.setMsgFlags(0)
                guard(deadline)
                val result = native.networking.recvmsg(socket, ConfinedSegment(message.segment), DONTWAIT or NativeConstants.MSG_CMSG_CLOEXEC)
                // Successful recvmsg already owns received rights, even if cancellation won meanwhile.
                if (result is LinuxNative.SyscallResult.Success) SupervisorSocketUtils.rejectUnexpectedAncillary(message, control, native.fileSystem)
                val count = progress(result, "receive helper frame")
                guard(deadline)
                if (count == null) continue
                if (count == 0L) throw EOFException("Helper peer closed during frame")
                if (count !in 1..(size - received).toLong()) throw IOException("Invalid helper receive progress: $count")
                received += count.toInt()
            }
            data.toArray(ValueLayout.JAVA_BYTE)
        }
    }

    private fun await(
        events: Short,
        deadline: ConfinedDeadline,
    ) {
        while (!poll(events, deadline)) {
            // EINTR/EAGAIN and idle readiness reuse the original operation cap.
        }
    }

    private fun poll(
        events: Short,
        deadline: ConfinedDeadline,
    ): Boolean =
        NativeArena.ofConfined().use { arena ->
            val poll = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
            poll.setFd(socket.value)
            poll.setEvents(events)
            val now = guard(deadline)
            poll.setRevents(0)
            val result = native.raw.poll(poll.managed, 1, deadline.pollMillis(now))
            val count = progress(result, "poll helper channel")
            guard(deadline)
            if (count == null || count == 0L) return@use false
            val returned = poll.getRevents().toInt()
            val readableHangup = events == NativeConstants.POLLIN && returned and NativeConstants.POLLIN.toInt() != 0
            if (count != 1L || returned and (POLL_ERROR or POLL_INVALID) != 0 || returned and POLL_HANGUP != 0 && !readableHangup) throw IOException("Helper channel poll failed")
            true
        }

    private fun guard(deadline: ConfinedDeadline): Long {
        if (cancelled()) throw CancellationException("Confined helper cancelled")
        if (!healthy()) throw IOException("Confined helper peer retired")
        check(socket.isLiveForIo()) { "Helper channel owner retired during I/O" }
        return ConfinedDeadline.now(native).also { deadline.remainingNanos(it) }
    }

    private fun progress(
        result: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>,
        operation: String,
    ): Long? =
        when (result) {
        is LinuxNative.SyscallResult.Success -> result.value
        is LinuxNative.SyscallResult.Error -> if (result.errno == NativeConstants.EINTR || result.errno == NativeConstants.EAGAIN) null else result.helperValue(operation)
    }

    private inline fun <T> exchange(action: () -> T): T {
        check(!terminal) { "Confined helper channel is terminal" }
        var completed = false
        try {
            return action().also { completed = true }
        } finally {
            if (!completed) terminal = true
        }
    }

    private companion object {
        const val DONTWAIT = 0x40
        const val NOSIGNAL = 0x4000
        const val BYTE_MASK = 0xff
        const val POLL_ERROR = 8
        const val POLL_HANGUP = 16
        const val POLL_INVALID = 32
        const val MAX_FRAME_BYTES = 65536 + 15
    }
}

internal data class ConfinedDeadline(
    val nanos: Long,
) {
    init {
        require(nanos > 0) { "Confined deadline must be positive" }
    }

    fun accept(
        offered: ConfinedDeadline,
        now: Long,
    ): ConfinedDeadline {
        remainingNanos(now)
        require(offered.remainingNanos(now) <= MAX_NANOS) { "Offered helper deadline is too distant" }
        return ConfinedDeadline(minOf(nanos, offered.nanos))
    }

    fun remainingNanos(now: Long): Long {
        require(now >= 0) { "Invalid monotonic observation" }
        if (now >= nanos) throw TimeoutException("Confined helper deadline expired")
        return nanos - now
    }

    fun pollMillis(now: Long): Int = ((minOf(remainingNanos(now), MAX_POLL_NANOS) + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI).toInt()

    companion object {
        private const val MAX_NANOS = 5_000_000_000L
        private const val MAX_POLL_NANOS = 25_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val TIMESPEC = MemoryLayout.structLayout(ValueLayout.JAVA_LONG.withName("seconds"), ValueLayout.JAVA_LONG.withName("nanos"))

        fun mint(
            native: NativeEngine,
            remainingNanos: () -> Long,
        ): ConfinedDeadline {
            val before = remainingNanos()
            if (before <= 0) throw TimeoutException("No helper preparation budget")
            val now = now(native)
            val after = remainingNanos()
            requireRemainingAfterClock(after)
            return try {
                ConfinedDeadline(Math.addExact(now, minOf(MAX_NANOS, before, after)))
            } catch (failure: ArithmeticException) {
                throw ConfinedHelperRejection.classify(failure, ConfinedRejectReason.RuntimeClosure)
            }
        }

        private fun requireRemainingAfterClock(remaining: Long) {
            if (remaining <= 0) throw TimeoutException("Helper preparation budget exhausted during clock observation")
        }

        fun now(native: NativeEngine): Long =
            try {
            NativeArena.ofConfined().use { arena ->
            val timespec = arena.allocate(TIMESPEC)
            val result = native.raw.syscall(Arch.current().clock_gettime.toLong(), NativeArg.IntArg(1), NativeArg.MemoryArg(timespec))
            check(result.helperValue("helper CLOCK_MONOTONIC") == 0L) { "Invalid clock_gettime return value" }
            val seconds = timespec.readLong(0)
            val nanos = timespec.readLong(8)
            require(seconds >= 0 && nanos in 0 until NANOS_PER_SECOND) { "Invalid CLOCK_MONOTONIC timespec" }
            Math.addExact(Math.multiplyExact(seconds, NANOS_PER_SECOND), nanos)
        }
        } catch (expectedClockFailure: Exception) {
            throw ConfinedHelperRejection.classify(expectedClockFailure, ConfinedRejectReason.RuntimeClosure)
        }
    }
}

internal fun LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>.helperValue(operation: String): Long =
    when (this) {
    is LinuxNative.SyscallResult.Success -> value
    is LinuxNative.SyscallResult.Error -> throw ConfinedHelperRejection.nativeFailure(operation, errno)
}
