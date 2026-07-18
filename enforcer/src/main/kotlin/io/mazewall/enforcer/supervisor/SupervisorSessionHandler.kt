package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.core.LoopAction
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.ffi.networking.NetworkOrderBuffer
import io.mazewall.onSuccess
import io.mazewall.recover
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

private class SyscallArguments(
    val pathStr: String?,
    val sockaddrBytes: ByteArray?,
    val dirfd: Int = -100
)

private fun Logger.info(msg: () -> String) {
    System.err.println("[SUPERVISOR-INFO] ${msg()}")
}
private fun Logger.warning(msg: () -> String) {
    System.err.println("[SUPERVISOR-WARN] ${msg()}")
}
private fun Logger.warning(msg: String) {
    System.err.println("[SUPERVISOR-WARN] $msg")
}
private fun Logger.severe(msg: () -> String) {
    System.err.println("[SUPERVISOR-SEVERE] ${msg()}")
}
private fun Logger.severe(msg: String) {
    System.err.println("[SUPERVISOR-SEVERE] $msg")
}
private fun Logger.log(level: java.util.logging.Level, msg: String, t: Throwable) {
    System.err.println("[SUPERVISOR-$level] $msg")
    t.printStackTrace()
}

internal class SupervisorSessionHandler(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    private val engine: io.mazewall.NativeEngine = io.mazewall.LinuxNative,
    private val socketManager: io.mazewall.core.SocketManager = io.mazewall.core.RealSocketManager
) {
    private val logger = Logger.getLogger(SupervisorSessionHandler::class.java.name)

    companion object {
        private const val POLL_TIMEOUT_MS = 30000
        private const val AT_FDCWD = -100

        // Seccomp notifications offsets
        private const val NOTIF_ID_OFF = 0L
        private const val NOTIF_PID_OFF = 8L
        private const val NOTIF_ARCH_OFF = 20L
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

        /**
         * Resolves the set of paths that the supervisor daemon will inject directly
         * without forwarding to the JVM validation listener.
         */
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private val safeBypassPaths = mutableListOf<java.nio.file.Path>().apply {
            fun addPathAndReal(path: java.nio.file.Path) {
                val abs = path.toAbsolutePath().normalize()
                add(abs)
                try {
                    val real = abs.toRealPath()
                    add(real)
                } catch (ignored: Exception) {}
            }

            fun parseManifestClassPath(jarPath: java.nio.file.Path) {
                try {
                    java.util.jar.JarFile(jarPath.toFile()).use { jar ->
                        val manifest = jar.manifest ?: return
                        val classPathAttr = manifest.mainAttributes.getValue("Class-Path") ?: return
                        val parentDir = jarPath.parent ?: return
                        for (entry in classPathAttr.split(" ")) {
                            if (entry.isNotEmpty()) {
                                try {
                                    val uri = java.net.URI(entry)
                                    val resolvedPath = if (uri.isAbsolute) {
                                        java.nio.file.Paths.get(uri)
                                    } else {
                                        parentDir.resolve(uri.path).normalize()
                                    }
                                    addPathAndReal(resolvedPath)
                                } catch (ignored: Exception) {}
                            }
                        }
                    }
                } catch (ignored: Exception) {}
            }

            try {
                val javaHomeStr = System.getProperty("java.home")
                if (!javaHomeStr.isNullOrEmpty()) {
                    addPathAndReal(java.nio.file.Paths.get(javaHomeStr))
                }

                val cp = System.getProperty("java.class.path")
                if (cp != null) {
                    val cpEntries = cp.split(java.io.File.pathSeparator)
                    for (entry in cpEntries) {
                        if (entry.isNotEmpty()) {
                            try {
                                val path = java.nio.file.Paths.get(entry)
                                addPathAndReal(path)
                                if (entry.endsWith(".jar")) {
                                    parseManifestClassPath(path)
                                }
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
                                addPathAndReal(java.nio.file.Paths.get(agentPath))
                            } catch (ignored: Exception) {}
                        }
                    }
                }

                // Add CI-specific build directories and test-framework caches to prevent deadlock
                try {
                    addPathAndReal(java.nio.file.Paths.get("build"))
                    addPathAndReal(java.nio.file.Paths.get(".gradle"))
                } catch (ignored: Exception) {}

                // Add GRADLE_USER_HOME if set to support container/CI cache directories
                try {
                    val gradleUserHome = System.getenv("GRADLE_USER_HOME")
                    if (!gradleUserHome.isNullOrEmpty()) {
                        addPathAndReal(java.nio.file.Paths.get(gradleUserHome))
                    }
                } catch (ignored: Exception) {}

                // Add /proc and /sys virtual filesystems to prevent GC/JIT thread deadlocks
                try {
                    addPathAndReal(java.nio.file.Paths.get("/proc"))
                    addPathAndReal(java.nio.file.Paths.get("/sys"))
                } catch (ignored: Exception) {}

                // Add the project root directory to bypass all project classes and build artifacts
                try {
                    val userDir = System.getProperty("user.dir")
                    if (!userDir.isNullOrEmpty()) {
                        addPathAndReal(java.nio.file.Paths.get(userDir))
                    }
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }

    context(arena: NativeArena)
    fun handleActiveListener(
        pollFds: ManagedSegment,
        notif: ManagedSegment,
        resp: ManagedSegment
    ): LoopAction {
        val pfd2 = PollFdSegment(pollFds.slice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
        val socketRevents = pfd2.getRevents().toInt()
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
        if ((socketRevents and (NativeConstants.POLLIN.toInt() or errorOrHup)) != 0) {
            // JVM socket closed, errored, or sent shutdown
            return LoopAction.Shutdown
        }

        val pfd1 = PollFdSegment(pollFds.slice(0L, Layouts.POLLFD_SIZE))
        val listenerRevents = pfd1.getRevents()
        if ((listenerRevents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
            notif.fill(0)
            val recvRes = engine.withTransaction {
                engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_RECV, notif)
            }
            var ok = false
            recvRes.onSuccess {
                ok = processNotification(notif, resp)
            }
            if (!ok) return LoopAction.Break
        }

        return LoopAction.Continue
    }

    context(arena: NativeArena)
    @Suppress("TooGenericExceptionCaught")
    private fun processNotification(notif: ManagedSegment, resp: ManagedSegment): Boolean {
        val id = notif.readLong(NOTIF_ID_OFF)
        try {
            val pidVal = notif.readInt(NOTIF_PID_OFF)
            val archVal = notif.readInt(NOTIF_ARCH_OFF)
            val nr = notif.readInt(NOTIF_NR_OFF)

            val args = LongArray(MAX_ARGS)
            for (i in 0 until MAX_ARGS) {
                args[i] = notif.readLong(NOTIF_ARGS_OFF + i * BYTES_PER_LONG)
            }

            val tid = Tid(pidVal)
            val traceeArch = io.mazewall.core.Arch.fromAudit(archVal)
            val extracted = extractNotificationArgs(nr, tid, args, traceeArch)
            val ppid = getPpid(pidVal)
            logger.info { "[SUPERVISOR-DEBUG] Received syscall notification: id=$id, pid=$pidVal, arch=$archVal, ppid=$ppid, nr=$nr, path=${extracted.pathStr}" }

            val isOpen = nr == traceeArch.open || nr == traceeArch.openat || nr == traceeArch.openat2
            if (isOpen && extracted.pathStr != null) {
                val pathStr = extracted.pathStr
                try {
                    val path = resolveAbsolutePath(pidVal, extracted.dirfd, pathStr)
                    if (path != null) {
                        val matched = safeBypassPaths.any { bypassPath ->
                            path.startsWith(bypassPath) || path == bypassPath
                        }
                        if (matched) {
                            val absPathStr = path.toAbsolutePath().toString()
                            val injectRes = handleInjectFd(id, nr, args, absPathStr, null, resp, Tid(pidVal), traceeArch)
                            logger.info { "[SUPERVISOR-DEBUG] Fast-path handleInjectFd (matched) resolved=$absPathStr result=$injectRes" }
                            return injectRes
                        }
                    } else {
                        // Fallback when /proc absolute path resolution fails
                        if (pathStr.endsWith(".class") || pathStr.contains("META-INF/") || pathStr.endsWith(".jar")) {
                            val injectRes = handleInjectFd(id, nr, args, pathStr, null, resp, Tid(pidVal), traceeArch)
                            logger.info { "[SUPERVISOR-DEBUG] Fast-path handleInjectFd (fallback: classloading) result=$injectRes" }
                            return injectRes
                        }
                    }
                } catch (e: Exception) {
                    logger.warning { "[SUPERVISOR-DEBUG] Fast-path check failed with error: ${e.message}" }
                }
            }

            logger.info { "[SUPERVISOR-DEBUG] Forwarding request to JVM validation listener" }
            val success = sendRequestToJvm(id, pidVal, archVal, ppid, nr, args, extracted.pathStr, extracted.sockaddrBytes)
            if (!success) {
                logger.severe { "[SUPERVISOR-DEBUG] Failed to send request to JVM" }
                return false
            }

            val res = readAndHandleJvmResponse(id, nr, args, extracted.pathStr, extracted.sockaddrBytes, resp, tid, traceeArch)
            logger.info { "[SUPERVISOR-DEBUG] JVM validation handler response result=$res" }
            return res
        } catch (t: Throwable) {
            logger.log(java.util.logging.Level.SEVERE, "Fatal error processing notification $id", t)
            try {
                sendSeccompError(id, NativeConstants.EPERM, resp)
            } catch (ignored: Throwable) {
                // Ignore secondary errors
            }
            return false
        }
    }

    context(arena: NativeArena)
    private fun extractNotificationArgs(nr: Int, tid: Tid, args: LongArray, arch: io.mazewall.core.Arch): SyscallArguments {
        var pathStr: String? = null
        var sockaddrBytes: ByteArray? = null
        var dirfd = AT_FDCWD
        when (nr) {
            arch.open, arch.execve -> {
                pathStr = readStringFromProcess(tid, args[0])
            }
            arch.openat, arch.openat2 -> {
                dirfd = args[0].toInt()
                pathStr = readStringFromProcess(tid, args[1])
            }
            arch.connect -> {
                val addrLen = args[2].toInt()
                if (addrLen in 1..MAX_ADDR_LEN) {
                    sockaddrBytes = readBytesFromProcess(tid, args[1], addrLen)
                }
            }
            arch.accept, arch.accept4 -> {
                dirfd = args[0].toInt()
            }
        }
        return SyscallArguments(pathStr, sockaddrBytes, dirfd)
    }

    private fun resolveAbsolutePath(pid: Int, dirfd: Int, pathStr: String): java.nio.file.Path? {
        val path = java.nio.file.Paths.get(pathStr)
        if (path.isAbsolute) {
            return try {
                path.normalize().toRealPath()
            } catch (e: Exception) {
                null
            }
        }
        try {
            val baseDir = if (dirfd == AT_FDCWD) {
                java.nio.file.Paths.get("/proc/$pid/cwd").toRealPath()
            } else {
                java.nio.file.Paths.get("/proc/$pid/fd/$dirfd").toRealPath()
            }
            return baseDir.resolve(path).normalize().toRealPath()
        } catch (e: Exception) {
            // Fallback: search in safeBypassPaths for relative path matching
            for (bypassPath in safeBypassPaths) {
                try {
                    val resolved = bypassPath.resolve(pathStr).normalize()
                    return resolved.toRealPath()
                } catch (ignored: Exception) {}
            }
        }
        return null
    }

    context(arena: NativeArena)
    @Suppress("LongParameterList")
    private fun sendRequestToJvm(
        id: Long,
        pidVal: Int,
        archVal: Int,
        ppid: Int,
        nr: Int,
        args: LongArray,
        pathStr: String?,
        sockaddrBytes: ByteArray?
    ): Boolean {
        val sizeOfMeta = SIZE_META + SIZE_INT + SIZE_INT // Include PPID and Arch
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
        netBuf.writeInt(offset, archVal); offset += SIZE_INT
        netBuf.writeInt(offset, ppid); offset += SIZE_INT
        netBuf.writeInt(offset, nr); offset += SIZE_INT

        if (pathStr != null) {
            netBuf.writeInt(offset, ONE_ARG); offset += SIZE_INT
            netBuf.writeByte(offset, ARG_TYPE_STRING); offset += SIZE_BYTE
            val bytes = pathStr.toByteArray(StandardCharsets.UTF_8)
            netBuf.writeIntUnaligned(offset, bytes.size); offset += SIZE_INT
            ManagedSegment.copy(bytes, 0, buf, offset, bytes.size)
        } else if (sockaddrBytes != null) {
            netBuf.writeInt(offset, ONE_ARG); offset += SIZE_INT
            netBuf.writeByte(offset, ARG_TYPE_SOCKADDR); offset += SIZE_BYTE
            netBuf.writeIntUnaligned(offset, sockaddrBytes.size); offset += SIZE_INT
            ManagedSegment.copy(sockaddrBytes, 0, buf, offset, sockaddrBytes.size)
        } else {
            netBuf.writeInt(offset, MAX_ARGS); offset += SIZE_INT
            for (arg in args) {
                netBuf.writeByte(offset, ARG_TYPE_LONG); offset += SIZE_BYTE
                netBuf.writeLongUnaligned(offset, arg); offset += BYTES_PER_LONG
            }
        }

        val writeRes = engine.withTransaction { engine.memory.write(socketFd, buf, totalSize.toLong()) }
        return writeRes is LinuxNative.SyscallResult.Success
    }

    context(arena: NativeArena)
    @Suppress("LongParameterList")
    private fun readAndHandleJvmResponse(
        id: Long,
        nr: Int,
        args: LongArray,
        pathStr: String?,
        sockaddrBytes: ByteArray?,
        resp: ManagedSegment,
        tid: Tid,
        traceeArch: io.mazewall.core.Arch
    ): Boolean {
        val pollFd = with(arena) { PollFdSegment.allocate() }
        pollFd.setFd(socketFd.value)
        pollFd.setEvents(NativeConstants.POLLIN)

        val startMs = System.currentTimeMillis()
        var remainingTimeout = POLL_TIMEOUT_MS.toLong()
        var count = 0L
        while (remainingTimeout > 0) {
            val loopStart = System.currentTimeMillis()
            val pollRes = engine.withTransaction { engine.raw.poll(pollFd.managed, 1L, remainingTimeout.toInt()) }
            val elapsed = System.currentTimeMillis() - loopStart
            remainingTimeout -= elapsed

            var gotEintr = false
            count = pollRes.recover { errno, _ ->
                if (errno == NativeConstants.EINTR) {
                    gotEintr = true
                    0L
                } else {
                    0L
                }
            }
            if (pollRes is LinuxNative.SyscallResult.Success) {
                count = pollRes.value
                break
            }
            if (!gotEintr) {
                break
            }
        }
        val durationMs = System.currentTimeMillis() - startMs
        if (durationMs > SLOW_VALIDATION_THRESHOLD_MS) {
            logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM policy validation took ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Possible deadlock or slow stack trace resolution.")
        }
        if (count <= 0) {
            logger.severe("[SUPERVISOR-DIAGNOSTIC] JVM validation timed out or failed after ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Returning EPERM.")
            sendSeccompError(id, NativeConstants.EPERM, resp)
            return false
        }

        val responseBuf = arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)
        val readRes = engine.withTransaction {
            engine.memory.read(socketFd, responseBuf, Layouts.SUPERVISOR_RESPONSE_SIZE)
        }
        if (readRes is LinuxNative.SyscallResult.Success && readRes.value == Layouts.SUPERVISOR_RESPONSE_SIZE) {
            val respSeg = SupervisorResponseSegment(responseBuf)
            val respId = respSeg.getId()
            val decision = respSeg.getDecision()
            val errorNr = respSeg.getErrorNr()

            if (respId != id) {
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return false
            }

            return when (decision.toInt()) {
                0 -> { // Deny
                    sendSeccompError(id, errorNr, resp)
                    true
                }
                1 -> { // Allow Continue
                    sendSeccompContinue(id, resp)
                    true
                }
                2 -> { // Allow & Inject FD
                    handleInjectFd(id, nr, args, pathStr, sockaddrBytes, resp, tid, traceeArch)
                }
                else -> {
                    sendSeccompError(id, NativeConstants.EPERM, resp)
                    false
                }
            }
        } else {
            sendSeccompError(id, NativeConstants.EPERM, resp)
            return false
        }
    }

    context(arena: NativeArena)
    @Suppress("LongParameterList")
    private fun handleInjectFd(
        id: Long,
        nr: Int,
        args: LongArray,
        pathStr: String?,
        sockaddrBytes: ByteArray?,
        resp: ManagedSegment,
        tid: Tid,
        traceeArch: io.mazewall.core.Arch
    ): Boolean {
        var localFdValue = -1
        try {
            localFdValue = when (nr) {
                traceeArch.open, traceeArch.openat, traceeArch.openat2 -> {
                    if (pathStr == null) {
                        -NativeConstants.EPERM
                    } else {
                        val res = openFileInSupervisor(nr, args, pathStr, traceeArch)
                        logger.info { "[SUPERVISOR-DEBUG] openFileInSupervisor path=$pathStr res=$res" }
                        res
                    }
                }
                traceeArch.connect -> {
                    if (sockaddrBytes == null) {
                        -NativeConstants.EPERM
                    } else {
                        val res = connectSocketInSupervisor(sockaddrBytes)
                        logger.info { "[SUPERVISOR-DEBUG] connectSocketInSupervisor res=$res" }
                        res
                    }
                }
                traceeArch.accept, traceeArch.accept4 -> {
                    handleAsyncAccept(id, nr, args, tid, traceeArch)
                    return true
                }
                else -> -NativeConstants.EPERM
            }

            if (localFdValue < 0) {
                logger.warning { "[SUPERVISOR-DEBUG] localFdValue is negative error: $localFdValue. Sending seccomp error." }
                sendSeccompError(id, -localFdValue, resp)
                return false
            }

            val addfd = with(arena) { SeccompNotifAddFdSegment.allocate() }
            addfd.managed.fill(0)
            addfd.setId(id)
            addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
            addfd.setSrcfd(localFdValue)

            val success = engine.withTransaction {
                val res = engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD, addfd.managed)
                logger.info { "[SUPERVISOR-DEBUG] ioctl SECCOMP_IOCTL_NOTIF_ADDFD res=$res" }
                res is LinuxNative.SyscallResult.Success
            }

            if (!success) {
                logger.severe { "[SUPERVISOR-DEBUG] ioctl SECCOMP_IOCTL_NOTIF_ADDFD failed. Sending EPERM." }
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

    context(arena: NativeArena)
    private fun openFileInSupervisor(nr: Int, args: LongArray, pathStr: String, arch: io.mazewall.core.Arch): Int {
        val flags = if (nr == arch.open) args[1].toInt() else args[2].toInt()
        val pathSeg = arena.allocateFrom(pathStr)
        val dirfd = if (nr == arch.open || pathStr.startsWith("/")) AT_FDCWD else args[0].toInt()
        val res: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = engine.withTransaction {
            if (dirfd == AT_FDCWD) {
                engine.fileSystem.open(pathSeg, flags)
            } else {
                engine.fileSystem.openat(dirfd, pathSeg, flags)
            }
        }
        return when (res) {
            is LinuxNative.SyscallResult.Success -> res.value.toInt()
            is LinuxNative.SyscallResult.Error -> -res.errno
        }
    }

    context(arena: NativeArena)
    private fun connectSocketInSupervisor(sockaddrBytes: ByteArray): Int {
        val domain = if (sockaddrBytes.size >= 2) {
            (sockaddrBytes[0].toInt() and 0xFF) or ((sockaddrBytes[1].toInt() and 0xFF) shl 8)
        } else {
            2 // AF_INET = 2
        }

        val socketRes = engine.withTransaction {
            val res = engine.networking.socket(domain, 1, 0) // SOCK_STREAM = 1
            when (res) {
                is LinuxNative.SyscallResult.Success -> res.value.toInt()
                is LinuxNative.SyscallResult.Error -> -res.errno
            }
        }
        if (socketRes < 0) return socketRes

        val addr = arena.allocate(sockaddrBytes.size.toLong())
        ManagedSegment.copy(sockaddrBytes, 0, addr, 0L, sockaddrBytes.size)

        val connectErr = engine.withTransaction {
            val res = engine.networking.connect(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(socketRes),
                addr,
                sockaddrBytes.size
            )
            when (res) {
                is LinuxNative.SyscallResult.Success -> 0
                is LinuxNative.SyscallResult.Error -> res.errno
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
            engine.withTransaction {
                engine.fileSystem.close(FileDescriptor.unsafe<FileDescriptorRole.Generic>(fd))
                Unit
            }
        } catch (ignored: IllegalStateException) {
            // Ignore
        }
    }

    private fun sendSeccompContinue(id: Long, resp: ManagedSegment) {
        resp.fill(0)
        resp.writeLong(RESP_ID_OFF, id)
        resp.writeLong(RESP_VAL_OFF, 0L)
        resp.writeInt(RESP_ERR_OFF, 0)
        resp.writeInt(RESP_FLAGS_OFF, NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt())
        engine.withTransaction {
            engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, resp)
            Unit
        }
    }

    private fun sendSeccompError(id: Long, errorNr: Int, resp: ManagedSegment) {
        resp.fill(0)
        resp.writeLong(RESP_ID_OFF, id)
        resp.writeLong(RESP_VAL_OFF, -1L)
        resp.writeInt(RESP_ERR_OFF, -errorNr)
        resp.writeInt(RESP_FLAGS_OFF, 0)
        engine.withTransaction {
            engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, resp)
            Unit
        }
    }

    context(arena: NativeArena)
    private fun readStringFromProcess(tid: Tid, remoteAddr: Long): String? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readString(tid, remoteAddr, MAX_PATH_LEN)
    }

    context(arena: NativeArena)
    private fun readBytesFromProcess(tid: Tid, remoteAddr: Long, len: Int): ByteArray? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readBytes(tid, remoteAddr, len)
    }

    private fun getTgid(tid: Int): Int {
        try {
            val statusFile = java.io.File("/proc/$tid/status")
            if (statusFile.exists()) {
                statusFile.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("Tgid:")) {
                            return line.substringAfter("Tgid:").trim().toInt()
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return tid
    }

    private fun getPpid(pid: Int): Int {
        try {
            val statFile = java.io.File("/proc/$pid/stat")
            if (statFile.exists()) {
                val content = statFile.readText()
                val parts = content.substringAfterLast(')').split(' ')
                if (parts.size >= 3) {
                    return parts[2].toInt()
                }
            }
        } catch (ignored: Exception) {}
        return 0
    }

    private fun handleAsyncAccept(
        id: Long,
        nr: Int,
        args: LongArray,
        tid: Tid,
        traceeArch: io.mazewall.core.Arch
    ) {
        Thread {
            try {
                NativeArena.ofConfined().use { arena ->
                    val tgid = getTgid(tid.value)
                    logger.info { "[SUPERVISOR-DEBUG] Async accept worker started for tid=${tid.value} (tgid=$tgid), targetFd=${args[0].toInt()}" }
                    val pidfdRes: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = engine.withTransaction {
                        engine.process.pidfdOpen(tgid, 0)
                    }
                    val pidfd = when (pidfdRes) {
                        is LinuxNative.SyscallResult.Success -> pidfdRes.value.toInt()
                        is LinuxNative.SyscallResult.Error -> {
                            logger.severe { "[SUPERVISOR-DEBUG] pidfd_open failed for tid=${tid.value} with errno ${pidfdRes.errno}" }
                            sendSeccompError(id, pidfdRes.errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                            return@use
                        }
                    }

                    val targetFd = args[0].toInt()
                    logger.info { "[SUPERVISOR-DEBUG] pidfd_open success. pidfd=$pidfd. Duplicating fd $targetFd..." }
                    val dupRes: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = engine.withTransaction {
                        engine.process.pidfdGetFd(pidfd, targetFd, 0)
                    }

                    closeLocalFd(pidfd)

                    val dupFd = when (dupRes) {
                        is LinuxNative.SyscallResult.Success -> dupRes.value.toInt()
                        is LinuxNative.SyscallResult.Error -> {
                            logger.severe { "[SUPERVISOR-DEBUG] pidfd_getfd failed for targetFd=$targetFd with errno ${dupRes.errno}" }
                            sendSeccompError(id, dupRes.errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                            return@use
                        }
                    }
                    logger.info { "[SUPERVISOR-DEBUG] pidfd_getfd success. dupFd=$dupFd. Starting accept..." }

                    try {
                        val localAddr = arena.allocate(128)
                        val localAddrLen = arena.allocate(4)
                        localAddrLen.writeInt(0, 128)

                        val flags = if (nr == traceeArch.accept4) args[3].toInt() else 0

                        val acceptRes = engine.withTransaction {
                            engine.networking.accept4(
                                FileDescriptor.unsafe<FileDescriptorRole.Generic>(dupFd),
                                localAddr,
                                localAddrLen,
                                flags
                            )
                        }

                        val clientFd = when (acceptRes) {
                            is LinuxNative.SyscallResult.Success -> acceptRes.value.toInt()
                            is LinuxNative.SyscallResult.Error -> {
                                sendSeccompError(id, acceptRes.errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                                return@use
                            }
                        }

                        try {
                            // Copy peer address back if tracee provided a buffer
                            val traceeAddrPtr = args[1]
                            val traceeAddrLenPtr = args[2]
                            if (traceeAddrPtr != 0L && traceeAddrLenPtr != 0L) {
                                val actualLen = localAddrLen.readInt(0)
                                val traceeAddrLenBytes = with(arena) {
                                    io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readBytes(tid, traceeAddrLenPtr, 4)
                                }
                                val traceeAddrLen = if (traceeAddrLenBytes != null && traceeAddrLenBytes.size >= 4) {
                                    (traceeAddrLenBytes[0].toInt() and 0xFF) or
                                    ((traceeAddrLenBytes[1].toInt() and 0xFF) shl 8) or
                                    ((traceeAddrLenBytes[2].toInt() and 0xFF) shl 16) or
                                    ((traceeAddrLenBytes[3].toInt() and 0xFF) shl 24)
                                } else {
                                    0
                                }

                                val writeLen = minOf(actualLen, traceeAddrLen)
                                if (writeLen > 0) {
                                    val addrBytes = ByteArray(writeLen)
                                    ManagedSegment.copy(localAddr, 0L, addrBytes, 0, writeLen)
                                    with(arena) {
                                        SupervisorProcessMemoryWriter.writeBytes(tid, traceeAddrPtr, addrBytes)
                                    }
                                }

                                val lenBytes = byteArrayOf(
                                    (actualLen and 0xFF).toByte(),
                                    ((actualLen shr 8) and 0xFF).toByte(),
                                    ((actualLen shr 16) and 0xFF).toByte(),
                                    ((actualLen shr 24) and 0xFF).toByte()
                                )
                                with(arena) {
                                    SupervisorProcessMemoryWriter.writeBytes(tid, traceeAddrLenPtr, lenBytes)
                                }
                            }

                            // Inject accepted FD
                            val addfd = with(arena) { SeccompNotifAddFdSegment.allocate() }
                            addfd.managed.fill(0)
                            addfd.setId(id)
                            addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
                            addfd.setSrcfd(clientFd)

                            val injectSuccess = engine.withTransaction {
                                val res = engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD, addfd.managed)
                                res is LinuxNative.SyscallResult.Success
                            }

                            if (!injectSuccess) {
                                sendSeccompError(id, NativeConstants.EPERM, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                            }
                        } finally {
                            closeLocalFd(clientFd)
                        }
                    } finally {
                        closeLocalFd(dupFd)
                    }
                }
            } catch (t: Throwable) {
                logger.log(java.util.logging.Level.SEVERE, "Error in async accept worker for notification $id", t)
                try {
                    NativeArena.ofConfined().use { arena ->
                        sendSeccompError(id, NativeConstants.EPERM, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                    }
                } catch (ignored: Throwable) {}
            }
        }.apply {
            isDaemon = true
            name = "supervisor-accept-worker-$id"
            start()
        }
    }
}
