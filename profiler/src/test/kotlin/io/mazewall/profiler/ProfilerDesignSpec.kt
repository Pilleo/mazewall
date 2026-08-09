package io.mazewall.profiler

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mazewall.LinuxNative
import io.mazewall.core.NativeArg
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.profiler.engine.HandshakeSession
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.profiler.engine.NativeIoOperations
import io.mazewall.profiler.engine.ProfilerMemoryReader
import io.mazewall.profiler.engine.ProfilerSessionHandler
import io.mazewall.profiler.engine.ProfilerTransport
import io.mazewall.profiler.engine.SeccompResponder
import io.mazewall.profiler.engine.SocketLifecycleManager
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import io.mazewall.profiler.engine.TraceEventPublisher
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.memory.writeShort
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class ProfilerDesignSpec :
    FreeSpec({

        class MockMemoryReader : ProfilerMemoryReader {
            var readStringResult: String? = null
            var resolveLinkResult: String? = null

            context(arena: io.mazewall.ffi.memory.NativeArena)
            override fun readStringFromProcess(
                tid: Tid,
                remoteAddr: Long,
                maxLen: Int,
            ): String? = readStringResult

            context(arena: io.mazewall.ffi.memory.NativeArena)
            override fun resolveLink(
                tid: Tid,
                link: String,
            ): String? = if (link == "cwd") resolveLinkResult else null
        }

        class MockTransport : ProfilerTransport, SeccompResponder, TraceEventPublisher, NativeIoOperations, SocketLifecycleManager {
            val ioctlCalls = mutableListOf<Long>()
            var nextNotifId = 123L
            var nextNotifPid = 456
            var nextNotifNr = 2
            val nextNotifArgs = LongArray(6)
            var nextPollResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)

            override val raw = object : io.mazewall.RawSyscallOperations {
                override fun poll(fds: ManagedSegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (nextPollResult is LinuxNative.SyscallResult.Success && (nextPollResult as LinuxNative.SyscallResult.Success).value > 0) {
                        val fdsSeg = MemorySegment.ofAddress(fds.address()).reinterpret(fds.byteSize())
                        fdsSeg.set(ValueLayout.JAVA_SHORT, 6L, NativeConstants.POLLIN)
                    }
                    return nextPollResult
                }

                override fun syscall(nr: Long, arg1: NativeArg, arg2: NativeArg, arg3: NativeArg, arg4: NativeArg, arg5: NativeArg, arg6: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

                override fun syscall4(nr: Long, arg1: NativeArg, arg2: NativeArg, arg3: NativeArg, arg4: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

                override fun fcntl(fd: FileDescriptor<*, FdState.Open>, cmd: Int, arg: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    ioctlCalls.add(request)
                    if (request == 0xc0502100L) { // RECV
                        val argSeg = MemorySegment.ofAddress(arg.address()).reinterpret(arg.byteSize())
                        argSeg.set(ValueLayout.JAVA_LONG, 0L, nextNotifId) // id
                        argSeg.set(ValueLayout.JAVA_INT, 8L, nextNotifPid) // pid
                        argSeg.set(ValueLayout.JAVA_INT, 16L, nextNotifNr) // nr
                        for (i in 0 until 6) {
                            argSeg.set(ValueLayout.JAVA_LONG, 32L + i * 8, nextNotifArgs[i])
                        }
                    }
                    return LinuxNative.SyscallResult.Success(0L)
                }

                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
            }

            val sentEvents = mutableListOf<SyscallEvent<SyscallEventState.Resolved>>()
            var createdServerPath: String? = null
            var acceptedServerFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>? = null
            val closedFds = mutableListOf<FileDescriptor<*, *>>()

            context(arena: Arena)
            override fun sendTraceEvent(
                socketFd: FileDescriptor<*, FdState.Open>,
                event: SyscallEvent<SyscallEventState.Resolved>,
            ) {
                sentEvents.add(event)
            }

            context(arena: Arena)
            override fun sendSeccompContinue(
                session: HandshakeSession.Success,
                resp: MemorySegment,
            ) {
                ioctlCalls.add(0xc0182101L) // SECCOMP_IOCTL_NOTIF_SEND
            }

            context(arena: Arena)
            override fun sendSeccompError(
                session: HandshakeSession.Failed,
                resp: MemorySegment,
                errorNr: Int,
            ) {
                ioctlCalls.add(0xc0182101L) // SECCOMP_IOCTL_NOTIF_SEND
            }

            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? =
                FileDescriptor.unsafe(20)

            override fun read(
                fd: FileDescriptor<*, FdState.Open>,
                buf: MemorySegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (count == 1L) {
                    buf.set(ValueLayout.JAVA_BYTE, 0L, 0xAC.toByte())
                    return LinuxNative.SyscallResult.Success(1L)
                }
                return LinuxNative.SyscallResult.Success(count)
            }

            override fun write(
                fd: FileDescriptor<*, FdState.Open>,
                buf: MemorySegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(count)

            override fun recv(
                sockfd: FileDescriptor<*, FdState.Open>,
                buf: MemorySegment,
                len: Long,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(len)

            override fun poll(
                fds: MemorySegment,
                nfds: Long,
                timeout: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = nextPollResult

            override fun ioctl(
                fd: FileDescriptor<*, FdState.Open>,
                request: Long,
                arg: MemorySegment,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

            override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
                createdServerPath = socketPath
                return FileDescriptor.unsafe(99)
            }

            override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
                acceptedServerFd = serverFd
                return FileDescriptor.unsafe(100)
            }

            override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(101)

            override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean = true

            override fun close(fd: FileDescriptor<*, FdState.Open>) {
                closedFds.add(fd)
            }
        }

        "ProfilerSessionHandler Isolated Mock Testing (profiler/AGENTS.md Section 2)" - {
            "Session handler processes notification, resolves path, and notifies parent" {
                val transport = MockTransport()
                transport.nextNotifNr = 2
                transport.nextNotifArgs[0] = 0x1000L

                val reader = MockMemoryReader()
                reader.readStringResult = "/tmp/test.txt"

                val syscallMap = mapOf(2 to "OPEN")
                val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
                val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
                ProfilerSessionHandler(
                    socketFd,
                    listenerFd,
                    transport,
                    transport,
                    transport,
                    reader,
                    syscallMap
                ) {}.use { handler ->
                    io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                        val notif = io.mazewall.ffi.memory.SegmentPool.SECCOMP_NOTIF_POOL.rent()
                        val resp = io.mazewall.ffi.memory.SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                        try {
                            notif.writeLong(0L, 123L) // id
                            notif.writeInt(8L, 456)   // pid
                            notif.writeInt(16L, 2)    // nr = 2 (OPEN)
                            // NOTIF_ARGS_OFF = 32L: args[0] = path pointer
                            notif.writeLong(32L, 0x1000L)

                            val ok = with(arena) { handler.processNotification(notif, resp, listenerFd, socketFd) }

                            ok shouldBe io.mazewall.platform.seccomp.daemon.NotifResult.HANDLED
                            transport.sentEvents.size shouldBe 1
                            transport.sentEvents[0].syscallName shouldBe "OPEN"
                            transport.sentEvents[0].tid shouldBe Tid(456)
                            transport.sentEvents[0].paths shouldBe listOf("/tmp/test.txt")
                        } finally {
                            io.mazewall.ffi.memory.SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                            io.mazewall.ffi.memory.SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                        }
                    }
                }
            }

            "Path resolution correctly resolves relative paths using cwd and dirfd" {
                val transport = MockTransport()
                transport.nextNotifNr = 257 // OPENAT
                transport.nextNotifArgs[0] = -100L // dirfd = AT_FDCWD
                transport.nextNotifArgs[1] = 0x1000L // path address

                val reader = MockMemoryReader()
                reader.readStringResult = "relative.txt"
                reader.resolveLinkResult = "/home/user"

                val syscallMap = mapOf(257 to "OPENAT")
                val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
                val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
                ProfilerSessionHandler(
                    socketFd,
                    listenerFd,
                    transport,
                    transport,
                    transport,
                    reader,
                    syscallMap
                ) {}.use { handler ->
                    io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                        val notif = io.mazewall.ffi.memory.SegmentPool.SECCOMP_NOTIF_POOL.rent()
                        val resp = io.mazewall.ffi.memory.SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                        try {
                            notif.writeLong(0L, 123L)
                            notif.writeInt(8L, 456)
                            notif.writeInt(16L, 257) // nr = 257 (OPENAT)
                            // NOTIF_ARGS_OFF = 32L: args[0] = dirfd, args[1] = path pointer
                            notif.writeLong(32L, -100L)  // args[0] = AT_FDCWD
                            notif.writeLong(40L, 0x1000L) // args[1] = path ptr

                            with(arena) {
                                handler.processNotification(notif, resp, listenerFd, socketFd)
                            }
                            transport.sentEvents.size shouldBe 1
                            transport.sentEvents[0].paths shouldBe listOf("/home/user/relative.txt")
                        } finally {
                            io.mazewall.ffi.memory.SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                            io.mazewall.ffi.memory.SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                        }
                    }
                }
            }
        }
    })
