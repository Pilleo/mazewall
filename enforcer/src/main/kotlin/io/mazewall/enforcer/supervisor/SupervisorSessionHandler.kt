package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SeccompNotifAddFdSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeIntUnaligned
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.memory.writeLongUnaligned
import io.mazewall.ffi.networking.NetworkOrderBuffer
import io.mazewall.onSuccess
import io.mazewall.recover
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

private class SyscallArguments(
    val pathStr: String?,
    val sockaddrBytes: ByteArray?
)

internal class SupervisorSessionHandler(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>
) {
    private val logger = Logger.getLogger(SupervisorSessionHandler::class.java.name)

    companion object {
        private const val POLL_TIMEOUT_MS = 30000
        private const val AT_FDCWD = -100

        // Seccomp notifications offsets
        private const val NOTIF_ID_OFF = 0L
        private const val NOTIF_PID_OFF = 8L
        private const val NOTIF_NR_OFF = 16L
        private const val NOTIF_ARGS_OFF = 32L

        // Seccomp response offsets
        private const val RESP_ID_OFF = 0L
        private const val RESP_VAL_OFF = 8L
        private const val RESP_ERR_OFF = 16L
        private const val RESP_FLAGS_OFF = 20L

        // Argument types
        private const val ARG_TYPE_LONG: Byte = 0
        private const val ARG_TYPE_STRING: Byte = 1
        private const val ARG_TYPE_SOCKADDR: Byte = 2

        // Syscalls
        private const val SYS_OPEN = 2
        private const val SYS_CONNECT = 42
        private const val SYS_EXECVE = 59
        private const val SYS_OPENAT = 257
        private const val SYS_OPENAT2 = 437

        private const val MAX_ARGS = 6
        private const val BYTES_PER_LONG = 8L
        private const val MAX_ADDR_LEN = 128
        private const val MAX_PATH_LEN = 4096

        private const val SIZE_META = 20
        private const val SIZE_ARG_HEADER = 5
        private const val SIZE_INT = 4
        private const val SIZE_BYTE = 1
        private const val ONE_ARG = 1

        private const val SLOW_VALIDATION_THRESHOLD_MS = 2000L

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private val safeBypassPaths = mutableListOf<java.nio.file.Path>().apply {
            try {
                val javaHome = java.nio.file.Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize()
                add(javaHome)

                val cp = System.getProperty("java.class.path")
                if (cp != null) {
                    val cpEntries = cp.split(java.io.File.pathSeparator)
                    for (entry in cpEntries) {
                        if (entry.isNotEmpty()) {
                            try {
                                val cpPath = java.nio.file.Paths.get(entry).toAbsolutePath().normalize()
                                add(cpPath)
                            } catch (ignored: Exception) {}
                        }
                    }
                }

                // Add javaagent jars to prevent deadlocks during agent instrumentation
                val jvmArgs = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
                for (arg in jvmArgs) {
                    if (arg.startsWith("-javaagent:")) {
                        val agentPath = arg.substringAfter("-javaagent:").substringBefore("=")
                        if (agentPath.isNotEmpty()) {
                            try {
                                val p = java.nio.file.Paths.get(agentPath).toAbsolutePath().normalize()
                                add(p)
                            } catch (ignored: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }

    fun handleActiveListener(
        pollFds: MemorySegment,
        notif: MemorySegment,
        resp: MemorySegment
    ): LoopAction {
        val pfd2 = PollFdSegment(pollFds.asSlice(Layouts.POLLFD.byteSize(), Layouts.POLLFD.byteSize()))
        val socketRevents = pfd2.getRevents()
        if ((socketRevents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
            // JVM socket closed or sent shutdown
            return LoopAction.Shutdown
        }

        val pfd1 = PollFdSegment(pollFds.asSlice(0L, Layouts.POLLFD.byteSize()))
        val listenerRevents = pfd1.getRevents()
        if ((listenerRevents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
            notif.fill(0)
            val recvRes = LinuxNative.withTransaction {
                LinuxNative.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_RECV, notif)
            }
            var ok = false
            recvRes.onSuccess {
                ok = processNotification(notif, resp)
            }
            if (!ok) return LoopAction.Break
        }

        return LoopAction.Continue
    }

    private fun processNotification(notif: MemorySegment, resp: MemorySegment): Boolean {
        val id = notif.readLong(NOTIF_ID_OFF)
        val pidVal = notif.readInt(NOTIF_PID_OFF)
        val nr = notif.readInt(NOTIF_NR_OFF)

        val args = LongArray(MAX_ARGS)
        for (i in 0 until MAX_ARGS) {
            args[i] = notif.readLong(NOTIF_ARGS_OFF + i * BYTES_PER_LONG)
        }

        val tid = Tid(pidVal)
        val extracted = extractNotificationArgs(nr, tid, args)

        // --- DAEMON-SIDE FAST-PATH BYPASS ---
        // HAZARD: When the sandboxed thread triggers lazy classloading (e.g., loading IOException
        // or a Kotlin helper) during a blocked file syscall, it holds the JVM's internal ClassLoader lock.
        // If we dispatch this request back to the JVM validation listener thread, the listener's policy
        // evaluation could also trigger classloading, blocking the listener on the tracee's ClassLoader lock.
        // This causes a permanent circular deadlock.
        //
        // SOLUTION: The uncontained daemon intercepts file read operations targeting the JVM's home directory,
        // application classpath, or Java agents. Paths are resolved to absolute form and normalized.
        // Since these paths contain trusted platform/application classes and libraries that are already loaded
        // or destined to be loaded, it is safe to bypass policy evaluation and directly inject the file descriptor.
        if ((nr == SYS_OPEN || nr == SYS_OPENAT || nr == SYS_OPENAT2) && extracted.pathStr != null) {
            val pathStr = extracted.pathStr
            try {
                val path = java.nio.file.Paths.get(pathStr).toAbsolutePath().normalize()
                val matched = safeBypassPaths.any { bypassPath ->
                    path.startsWith(bypassPath) || path == bypassPath
                }
                if (matched) {
                    return handleInjectFd(id, nr, args, pathStr, null, resp)
                }
            } catch (ignored: Exception) {
                // Fall back to slow-path JVM validation if path normalization fails
            }
        }

        val success = sendRequestToJvm(id, pidVal, nr, args, extracted.pathStr, extracted.sockaddrBytes)
        if (!success) return false

        return readAndHandleJvmResponse(id, nr, args, extracted.pathStr, extracted.sockaddrBytes, resp)
    }

    private fun extractNotificationArgs(nr: Int, tid: Tid, args: LongArray): SyscallArguments {
        var pathStr: String? = null
        var sockaddrBytes: ByteArray? = null
        when (nr) {
            SYS_OPEN, SYS_EXECVE -> {
                pathStr = readStringFromProcess(tid, args[0])
            }
            SYS_OPENAT, SYS_OPENAT2 -> {
                pathStr = readStringFromProcess(tid, args[1])
            }
            SYS_CONNECT -> {
                val addrLen = args[2].toInt()
                if (addrLen in 1..MAX_ADDR_LEN) {
                    sockaddrBytes = readBytesFromProcess(tid, args[1], addrLen)
                }
            }
        }
        return SyscallArguments(pathStr, sockaddrBytes)
    }

    @Suppress("LongParameterList")
    private fun sendRequestToJvm(
        id: Long,
        pidVal: Int,
        nr: Int,
        args: LongArray,
        pathStr: String?,
        sockaddrBytes: ByteArray?
    ): Boolean {
        return Arena.ofConfined().use { arena ->
            val sizeOfMeta = SIZE_META
            val sizeOfArgHeader = SIZE_ARG_HEADER
            val totalSize = sizeOfMeta + (
                if (pathStr != null) {
                    sizeOfArgHeader + pathStr.toByteArray(StandardCharsets.UTF_8).size
                } else if (sockaddrBytes != null) {
                    sizeOfArgHeader + sockaddrBytes.size
                } else {
                    MAX_ARGS * (SIZE_BYTE + BYTES_PER_LONG.toInt())
                }
            )

            val buf = arena.allocate(totalSize.toLong())
            val netBuf = NetworkOrderBuffer(buf)
            var offset = 0L

            netBuf.writeLong(offset, id); offset += BYTES_PER_LONG
            netBuf.writeInt(offset, pidVal); offset += SIZE_INT
            netBuf.writeInt(offset, nr); offset += SIZE_INT

            if (pathStr != null) {
                netBuf.writeInt(offset, ONE_ARG); offset += SIZE_INT
                netBuf.writeByte(offset, ARG_TYPE_STRING); offset += SIZE_BYTE
                val bytes = pathStr.toByteArray(StandardCharsets.UTF_8)
                netBuf.writeIntUnaligned(offset, bytes.size); offset += SIZE_INT
                MemorySegment.copy(bytes, 0, buf, ValueLayout.JAVA_BYTE, offset, bytes.size)
            } else if (sockaddrBytes != null) {
                netBuf.writeInt(offset, ONE_ARG); offset += SIZE_INT
                netBuf.writeByte(offset, ARG_TYPE_SOCKADDR); offset += SIZE_BYTE
                netBuf.writeIntUnaligned(offset, sockaddrBytes.size); offset += SIZE_INT
                MemorySegment.copy(sockaddrBytes, 0, buf, ValueLayout.JAVA_BYTE, offset, sockaddrBytes.size)
            } else {
                netBuf.writeInt(offset, MAX_ARGS); offset += SIZE_INT
                for (arg in args) {
                    netBuf.writeByte(offset, ARG_TYPE_LONG); offset += SIZE_BYTE
                    netBuf.writeLongUnaligned(offset, arg); offset += BYTES_PER_LONG
                }
            }

            val writeRes = LinuxNative.withTransaction { LinuxNative.memory.write(socketFd, buf, totalSize.toLong()) }
            writeRes is LinuxNative.SyscallResult.Success
        }
    }

    @Suppress("LongParameterList")
    private fun readAndHandleJvmResponse(
        id: Long,
        nr: Int,
        args: LongArray,
        pathStr: String?,
        sockaddrBytes: ByteArray?,
        resp: MemorySegment
    ): Boolean {
        return Arena.ofConfined().use { arena ->
            val pollFd = PollFdSegment(arena.allocate(Layouts.POLLFD))
            pollFd.setFd(socketFd.value)
            pollFd.setEvents(NativeConstants.POLLIN)

            val startMs = System.currentTimeMillis()
            val pollRes = LinuxNative.withTransaction { LinuxNative.poll(pollFd.segment, 1L, POLL_TIMEOUT_MS) }
            val durationMs = System.currentTimeMillis() - startMs
            if (durationMs > SLOW_VALIDATION_THRESHOLD_MS) {
                logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM policy validation took ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Possible deadlock or slow stack trace resolution.")
            }
            val count = pollRes.recover { errno, _ ->
                if (errno == NativeConstants.EINTR) 1L else 0L
            }
            if (count <= 0) {
                logger.severe("[SUPERVISOR-DIAGNOSTIC] JVM validation timed out or failed after ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Returning EPERM.")
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return@use false
            }

            val responseBuf = arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)
            val readRes = LinuxNative.withTransaction {
                LinuxNative.memory.read(socketFd, responseBuf, Layouts.SUPERVISOR_RESPONSE_SIZE)
            }
            if (readRes is LinuxNative.SyscallResult.Success && readRes.value == Layouts.SUPERVISOR_RESPONSE_SIZE) {
                val respSeg = SupervisorResponseSegment(responseBuf)
                val respId = respSeg.getId()
                val decision = respSeg.getDecision()
                val errorNr = respSeg.getErrorNr()

                if (respId != id) {
                    sendSeccompError(id, NativeConstants.EPERM, resp)
                    return@use false
                }

                when (decision.toInt()) {
                    0 -> { // Deny
                        sendSeccompError(id, errorNr, resp)
                        true
                    }
                    1 -> { // Allow Continue
                        sendSeccompContinue(id, resp)
                        true
                    }
                    2 -> { // Allow & Inject FD
                        handleInjectFd(id, nr, args, pathStr, sockaddrBytes, resp)
                    }
                    else -> {
                        sendSeccompError(id, NativeConstants.EPERM, resp)
                        false
                    }
                }
            } else {
                sendSeccompError(id, NativeConstants.EPERM, resp)
                false
            }
        }
    }

    @Suppress("LongParameterList")
    private fun handleInjectFd(
        id: Long,
        nr: Int,
        args: LongArray,
        pathStr: String?,
        sockaddrBytes: ByteArray?,
        resp: MemorySegment
    ): Boolean {
        var localFdValue = -1
        try {
            localFdValue = when (nr) {
                SYS_OPEN, SYS_OPENAT, SYS_OPENAT2 -> {
                    if (pathStr == null) {
                        -NativeConstants.EPERM
                    } else {
                        openFileInSupervisor(nr, args, pathStr)
                    }
                }
                SYS_CONNECT -> {
                    if (sockaddrBytes == null) {
                        -NativeConstants.EPERM
                    } else {
                        connectSocketInSupervisor(sockaddrBytes)
                    }
                }
                else -> -NativeConstants.EPERM
            }

            if (localFdValue < 0) {
                sendSeccompError(id, -localFdValue, resp)
                return false
            }

            val success = Arena.ofConfined().use { arena ->
                val addfd = SeccompNotifAddFdSegment(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
                addfd.segment.fill(0)
                addfd.setId(id)
                addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
                addfd.setSrcfd(localFdValue)

                LinuxNative.withTransaction {
                    val res = LinuxNative.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD, addfd.segment)
                    res is LinuxNative.SyscallResult.Success
                }
            }

            if (!success) {
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return false
            }
            return true
        } finally {
            if (localFdValue >= 0) {
                closeLocalFd(localFdValue)
            }
        }
    }

    private fun openFileInSupervisor(nr: Int, args: LongArray, pathStr: String): Int {
        val flags = if (nr == SYS_OPEN) args[1].toInt() else args[2].toInt()
        return Arena.ofConfined().use { arena ->
            val pathSeg = arena.allocateFrom(pathStr)
            val dirfd = if (nr == SYS_OPEN) AT_FDCWD else args[0].toInt()
            val res = LinuxNative.withTransaction {
                if (dirfd == AT_FDCWD) {
                    LinuxNative.fileSystem.open(pathSeg, flags)
                } else {
                    val LinuxSys = 257L // SYS_openat
                    LinuxNative.syscall(
                        LinuxSys,
                        io.mazewall.core.NativeArg.LongArg(dirfd.toLong()),
                        io.mazewall.core.NativeArg.MemoryArg(pathSeg),
                        io.mazewall.core.NativeArg.LongArg(flags.toLong()),
                        io.mazewall.core.NativeArg.LongArg(0L),
                        io.mazewall.core.NativeArg.LongArg(0L),
                        io.mazewall.core.NativeArg.LongArg(0L)
                    )
                }
            }
            when (res) {
                is LinuxNative.SyscallResult.Success -> res.value.toInt()
                is LinuxNative.SyscallResult.Error -> -res.errno
            }
        }
    }

    private fun connectSocketInSupervisor(sockaddrBytes: ByteArray): Int {
        val domain = if (sockaddrBytes.size >= 2) {
            sockaddrBytes[0].toInt() or (sockaddrBytes[1].toInt() shl 8)
        } else {
            2 // AF_INET = 2
        }

        val socketRes = LinuxNative.withTransaction {
            val res = LinuxNative.networking.socket(domain, 1, 0) // SOCK_STREAM = 1
            when (res) {
                is LinuxNative.SyscallResult.Success -> res.value.toInt()
                is LinuxNative.SyscallResult.Error -> -res.errno
            }
        }
        if (socketRes < 0) return socketRes

        val connectErr = Arena.ofConfined().use { arena ->
            val addr = arena.allocate(sockaddrBytes.size.toLong())
            MemorySegment.copy(sockaddrBytes, 0, addr, ValueLayout.JAVA_BYTE, 0L, sockaddrBytes.size)

            LinuxNative.withTransaction {
                val res = LinuxNative.networking.connect(
                    FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(socketRes),
                    addr,
                    sockaddrBytes.size
                )
                when (res) {
                    is LinuxNative.SyscallResult.Success -> 0
                    is LinuxNative.SyscallResult.Error -> res.errno
                }
            }
        }
        if (connectErr != 0) {
            closeLocalFd(socketRes)
            return -connectErr
        }
        return socketRes
    }

    private fun closeLocalFd(fd: Int) {
        try {
            LinuxNative.withTransaction {
                LinuxNative.fileSystem.close(FileDescriptor.unsafe<FileDescriptorRole.Generic>(fd))
                Unit
            }
        } catch (ignored: IllegalStateException) {
            // Ignore
        }
    }

    private fun sendSeccompContinue(id: Long, resp: MemorySegment) {
        resp.fill(0)
        resp.writeLong(RESP_ID_OFF, id)
        resp.writeLong(RESP_VAL_OFF, 0L)
        resp.writeInt(RESP_ERR_OFF, 0)
        resp.writeInt(RESP_FLAGS_OFF, NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt())
        LinuxNative.withTransaction {
            LinuxNative.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, resp)
            Unit
        }
    }

    private fun sendSeccompError(id: Long, errorNr: Int, resp: MemorySegment) {
        resp.fill(0)
        resp.writeLong(RESP_ID_OFF, id)
        resp.writeLong(RESP_VAL_OFF, -1L)
        resp.writeInt(RESP_ERR_OFF, -errorNr)
        resp.writeInt(RESP_FLAGS_OFF, 0)
        LinuxNative.withTransaction {
            LinuxNative.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, resp)
            Unit
        }
    }

    private fun readStringFromProcess(tid: Tid, remoteAddr: Long): String? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readString(tid, remoteAddr, MAX_PATH_LEN)
    }

    private fun readBytesFromProcess(tid: Tid, remoteAddr: Long, len: Int): ByteArray? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readBytes(tid, remoteAddr, len)
    }
}
