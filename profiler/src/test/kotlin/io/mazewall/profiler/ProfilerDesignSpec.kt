package io.mazewall.profiler

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import java.lang.foreign.Arena
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.profiler.engine.*
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class ProfilerDesignSpec :
    FreeSpec({

        "ACK Protocol Constants" - {
            "PROTOCOL_ACK_BYTE is exactly 0xAC" {
                PROTOCOL_ACK_BYTE shouldBe 0xAC.toByte()
            }

            "SHUTDOWN_COMMAND_BYTE is exactly 0x53 ('S')" {
                SHUTDOWN_COMMAND_BYTE shouldBe 0x53.toByte()
            }

            "ACK byte is a single byte (ACK_BUF_SIZE == 1)" {
                ACK_BUF_SIZE shouldBe 1L
            }
        }

        class MockMemoryReader : ProfilerMemoryReader {
            var readStringResult: String? = "/tmp/test.txt"
            var resolveLinkResult: String? = "/proc/1/cwd"

            context(arena: Arena)
            override fun readStringFromProcess(
                tid: Tid,
                remoteAddr: Long,
                maxLen: Int,
            ): String? = readStringResult

            context(arena: Arena)
            override fun resolveLink(
                tid: Tid,
                link: String,
            ): String? = resolveLinkResult
        }

        class MockTransport : ProfilerTransport {
            var nextPollResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
            var nextReadResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
            var ackByte: Byte = 0xAC.toByte()
            val ioctlCalls = mutableListOf<Long>()
            var nextNotifId = 123L
            var nextNotifPid = 456
            var nextNotifNr = 2

            override val raw: io.mazewall.RawSyscallOperations = object : io.mazewall.MockNativeEngine() {
                context(context: io.mazewall.NativeTransaction)
                override fun poll(
                    fds: MemorySegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (nfds == 1L) {
                        fds.set(ValueLayout.JAVA_SHORT, 6L, NativeConstants.POLLIN)
                    }
                    return nextPollResult
                }

                context(context: io.mazewall.NativeTransaction)
                override fun ioctl(
                    fd: FileDescriptor<*, FdState.Open>,
                    request: Long,
                    arg: MemorySegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    ioctlCalls.add(request)
                    if (request == NativeConstants.SECCOMP_IOCTL_NOTIF_RECV) { // RECV
                        arg.set(ValueLayout.JAVA_LONG, 0L, nextNotifId) // id
                        arg.set(ValueLayout.JAVA_INT, 8L, nextNotifPid) // pid
                        arg.set(ValueLayout.JAVA_INT, 16L, nextNotifNr) // nr
                    }
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }

            val sentEvents = mutableListOf<SyscallEvent<SyscallEventState.Resolved>>()

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
                ioctlCalls.add(NativeConstants.SECCOMP_IOCTL_NOTIF_SEND)
            }

            context(arena: Arena)
            override fun sendSeccompError(
                session: HandshakeSession.Failed,
                resp: MemorySegment,
                errorNr: Int,
            ) {
                ioctlCalls.add(NativeConstants.SECCOMP_IOCTL_NOTIF_SEND)
            }

            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? =
                FileDescriptor.unsafe(5)

            override fun read(
                fd: FileDescriptor<*, FdState.Open>,
                buf: MemorySegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (count == 1L) {
                    buf.set(ValueLayout.JAVA_BYTE, 0L, ackByte)
                }
                return nextReadResult
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

            override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(99)

            override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(100)

            override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(101)

            override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean = true

            override fun close(fd: FileDescriptor<*, FdState.Open>) {}
        }

        "ProfilerSessionHandler Isolated Mock Testing" - {
            "Session handler processes notification" {
                val transport = MockTransport()
                val reader = MockMemoryReader()
                val handler = ProfilerSessionHandler(
                    FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                    FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20),
                    transport,
                    transport,
                    transport,
                    reader,
                    mapOf(2 to "OPEN")
                ) {}

                Arena.ofConfined().use { arena ->
                    val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
                    val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
                    val ackBuf = arena.allocate(1L)
                    val socketPollFd = arena.allocate(Layouts.POLLFD)

                    val pollFds = arena.allocate(MemoryLayout.sequenceLayout(2, Layouts.POLLFD))
                    pollFds.set(ValueLayout.JAVA_SHORT, 6L, NativeConstants.POLLIN)

                    val action = with(arena) { handler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd) }

                    action shouldBe LoopAction.Continue
                    transport.sentEvents.size shouldBe 1
                    transport.ioctlCalls.contains(NativeConstants.SECCOMP_IOCTL_NOTIF_SEND) shouldBe true
                }
            }
        }
    })
