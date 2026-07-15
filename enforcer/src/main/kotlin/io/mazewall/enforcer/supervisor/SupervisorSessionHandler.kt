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
import io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeIntUnaligned
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.memory.writeLongUnaligned
import io.mazewall.ffi.networking.NetworkOrderBuffer
import io.mazewall.isSuccess
import io.mazewall.map
import io.mazewall.onFailure
import io.mazewall.onSuccess
import io.mazewall.recover
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
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
         *
         * ### The ClassLoader/Safepoint Deadlock Problem in Stacktrace Analysis
         *
         * The [io.mazewall.enforcer.supervisor.StacktraceScopingPolicy] relies on obtaining the Java stack trace
         * of the target thread using `Thread.getStackTrace()`. This forces a JVM safepoint.
         * If the target thread triggers a seccomp-supervised syscall (e.g., `openat`) while holding internal JVM
         * locks (such as the ClassLoader lock during class resolution or a global lock during JaCoCo instrumentation),
         * forwarding that syscall to the `JVMValidationListener` thread creates a severe risk of a **ClassLoader Deadlock**.
         *
         * **The Deadlock Scenario:**
         * 1. The target thread begins loading a class or instrumenting it (e.g., JaCoCo dumping data, or reading `kotlin-stdlib`).
         * 2. It holds the ClassLoader lock and triggers an `openat` syscall.
         * 3. The `openat` is intercepted and sent to the `JVMValidationListener` thread.
         * 4. The listener invokes the user's `StacktraceScopingPolicy`, which may trigger dynamic class loading
         *    (e.g., loading a Kotlin lambda class like `stack.any { ... }`).
         * 5. The listener attempts to acquire the ClassLoader lock and blocks forever because the target thread
         *    is blocked waiting for the seccomp response.
         *
         * **The Solution:**
         * To prevent this, the daemon implements a fast-path bypass for all internal JVM file accesses.
         * We unconditionally inject file descriptors for `java.home`, `java.class.path`, `javaagent` jars,
         * and build/coverage directories. Because these syscalls bypass the JVM listener entirely,
         * no dynamic class loading is triggered during vulnerable tracee states.
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

    fun handleActiveListener(
        pollFds: MemorySegment,
        notif: MemorySegment,
        resp: MemorySegment
    ): LoopAction {
        val pfd2 = PollFdSegment(pollFds.asSlice(Layouts.POLLFD.byteSize(), Layouts.POLLFD.byteSize()))
        val socketRevents = pfd2.getRevents().toInt()
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
        if ((socketRevents and (NativeConstants.POLLIN.toInt() or errorOrHup)) != 0) {
            // JVM socket closed, errored, or sent shutdown
            return LoopAction.Shutdown
        }

        val pfd1 = PollFdSegment(pollFds.asSlice(0L, Layouts.POLLFD.byteSize()))
        val listenerRevents = pfd1.getRevents()
        if ((listenerRevents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
            notif.fill(0)
            val ok = engine.withTransaction {
                engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_RECV, notif)
                    .onFailure { errno, _ ->
                        logger.severe { "Failed to receive seccomp notification: errno=$errno" }
                    }.isSuccess()
            }
            if (!ok || !processNotification(notif, resp)) return LoopAction.Break
        }

        return LoopAction.Continue
    }

    @Suppress("TooGenericExceptionCaught")
    private fun processNotification(notif: MemorySegment, resp: MemorySegment): Boolean {
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
                        // Fallback when /proc absolute path resolution fails (e.g. Yama ptrace_scope block inside container)
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
            // Fallback: search in safeBypassPaths for relative path matching (needed inside containers due to Yama ptrace_scope)
            for (bypassPath in safeBypassPaths) {
                try {
                    val resolved = bypassPath.resolve(pathStr).normalize()
                    return resolved.toRealPath()
                } catch (ignored: Exception) {}
            }
        }
        return null
    }

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
        return Arena.ofConfined().use { arena ->
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

            engine.withTransaction {
                engine.memory.write(socketFd, buf, totalSize.toLong())
                    .onFailure { errno, _ ->
                        logger.severe { "Failed to send request to JVM: errno=$errno" }
                    }.isSuccess()
            }
        }
    }

    @Suppress("LongParameterList")
    private fun readAndHandleJvmResponse(
        id: Long,
        nr: Int,
        args: LongArray,
        pathStr: String?,
        sockaddrBytes: ByteArray?,
        resp: MemorySegment,
        tid: Tid,
        traceeArch: io.mazewall.core.Arch
    ): Boolean {
        return Arena.ofConfined().use { arena ->
            val pollFd = PollFdSegment(arena.allocate(Layouts.POLLFD))
            pollFd.setFd(socketFd.value)
            pollFd.setEvents(NativeConstants.POLLIN)

            val startMs = System.currentTimeMillis()
            var remainingTimeout = POLL_TIMEOUT_MS.toLong()
            var count = 0L
            while (remainingTimeout > 0) {
                val loopStart = System.currentTimeMillis()
                val pollResValue = engine.withTransaction {
                    engine.raw.poll(pollFd.segment, 1L, remainingTimeout.toInt())
                        .recover { errno, _ ->
                            if (errno == NativeConstants.EINTR) -1000L else -errno.toLong()
                        }
                }
                val elapsed = System.currentTimeMillis() - loopStart
                remainingTimeout -= elapsed

                if (pollResValue >= 0) {
                    count = pollResValue
                    break
                }
                if (pollResValue != -1000L) {
                    count = 0
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
                return@use false
            }

            val responseBuf = arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)
            val readSuccess = engine.withTransaction {
                engine.memory.read(socketFd, responseBuf, Layouts.SUPERVISOR_RESPONSE_SIZE)
                    .onFailure { errno, _ ->
                        logger.severe { "Failed to read response from JVM: errno=$errno" }
                    }.map { it == Layouts.SUPERVISOR_RESPONSE_SIZE }
                    .recover { _, _ -> false }
            }
            if (readSuccess) {
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
                        handleInjectFd(id, nr, args, pathStr, sockaddrBytes, resp, tid, traceeArch)
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
        resp: MemorySegment,
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
                    handleAcceptAsync(id, nr, args, tid, traceeArch)
                    return true
                }
                else -> -NativeConstants.EPERM
            }

            if (localFdValue < 0) {
                logger.warning { "[SUPERVISOR-DEBUG] localFdValue is negative error: $localFdValue. Sending seccomp error." }
                sendSeccompError(id, -localFdValue, resp)
                return false
            }

            val success = Arena.ofConfined().use { arena ->
                val addfd = SeccompNotifAddFdSegment(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
                addfd.segment.fill(0)
                addfd.setId(id)
                addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
                addfd.setSrcfd(localFdValue)

                engine.withTransaction {
                    engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD, addfd.segment)
                        .onSuccess { res ->
                            logger.info { "[SUPERVISOR-DEBUG] ioctl SECCOMP_IOCTL_NOTIF_ADDFD success: $res" }
                        }
                        .onFailure { errno, _ ->
                            logger.severe { "[SUPERVISOR-DEBUG] ioctl SECCOMP_IOCTL_NOTIF_ADDFD failed: errno=$errno" }
                        }.isSuccess()
                }
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

    private fun openFileInSupervisor(nr: Int, args: LongArray, pathStr: String, arch: io.mazewall.core.Arch): Int {
        val flags = if (nr == arch.open) args[1].toInt() else args[2].toInt()
        return Arena.ofConfined().use { arena ->
            val pathSeg = arena.allocateFrom(pathStr)
            val dirfd = if (nr == arch.open || pathStr.startsWith("/")) AT_FDCWD else args[0].toInt()
            engine.withTransaction {
                val call = if (dirfd == AT_FDCWD) {
                    engine.fileSystem.open(pathSeg, flags)
                } else {
                    val myArch = io.mazewall.core.Arch.current()
                    val myOpenat = myArch.openat.toLong()
                    engine.raw.syscall(
                        myOpenat,
                        io.mazewall.core.NativeArg.LongArg(dirfd.toLong()),
                        io.mazewall.core.NativeArg.MemoryArg(pathSeg),
                        io.mazewall.core.NativeArg.LongArg(flags.toLong()),
                        io.mazewall.core.NativeArg.LongArg(0L),
                        io.mazewall.core.NativeArg.LongArg(0L),
                        io.mazewall.core.NativeArg.LongArg(0L)
                    )
                }
                call.map { it.toInt() }.recover { errno, _ -> -errno }
            }
        }
    }

    private fun connectSocketInSupervisor(sockaddrBytes: ByteArray): Int {
        val domain = if (sockaddrBytes.size >= 2) {
            (sockaddrBytes[0].toInt() and 0xFF) or ((sockaddrBytes[1].toInt() and 0xFF) shl 8)
        } else {
            2 // AF_INET = 2
        }

        val socketRes = engine.withTransaction {
            engine.networking.socket(domain, 1, 0) // SOCK_STREAM = 1
                .map { it.toInt() }
                .recover { errno, _ -> -errno }
        }
        if (socketRes < 0) return socketRes

        val connectErr = Arena.ofConfined().use { arena ->
            val addr = arena.allocate(sockaddrBytes.size.toLong())
            MemorySegment.copy(sockaddrBytes, 0, addr, ValueLayout.JAVA_BYTE, 0L, sockaddrBytes.size)

            engine.withTransaction {
                engine.networking.connect(
                    FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(socketRes),
                    addr,
                    sockaddrBytes.size
                ).map { 0 }.recover { errno, _ -> errno }
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
                    .onFailure { errno, _ ->
                        logger.warning { "Failed to close local FD $fd: errno=$errno" }
                    }
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
        engine.withTransaction {
            engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, resp)
                .onFailure { errno, _ ->
                    logger.severe { "Failed to send seccomp continue for id=$id: errno=$errno" }
                }
            Unit
        }
    }

    private fun sendSeccompError(id: Long, errorNr: Int, resp: MemorySegment) {
        resp.fill(0)
        resp.writeLong(RESP_ID_OFF, id)
        resp.writeLong(RESP_VAL_OFF, -1L)
        resp.writeInt(RESP_ERR_OFF, -errorNr)
        resp.writeInt(RESP_FLAGS_OFF, 0)
        engine.withTransaction {
            engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, resp)
                .onFailure { errno, _ ->
                    logger.severe { "Failed to send seccomp error for id=$id: errno=$errno" }
                }
            Unit
        }
    }

    private fun readStringFromProcess(tid: Tid, remoteAddr: Long): String? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readString(tid, remoteAddr, MAX_PATH_LEN)
    }

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
                // Format: pid (comm) state ppid ...
                // parts[0] is empty (space between ')' and state), parts[1] is state, parts[2] is ppid
                val parts = content.substringAfterLast(')').split(' ')
                if (parts.size >= 3) {
                    return parts[2].toInt()
                }
            }
        } catch (ignored: Exception) {}
        return 0
    }

    private fun handleAcceptAsync(
        id: Long,
        nr: Int,
        args: LongArray,
        tid: Tid,
        traceeArch: io.mazewall.core.Arch
    ) {
        Thread {
            try {
                Arena.ofConfined().use { arena ->
                    val tgid = getTgid(tid.value)
                    logger.info { "[SUPERVISOR-DEBUG] Async accept worker started for tid=${tid.value} (tgid=$tgid), targetFd=${args[0].toInt()}" }
                    val pidfd = engine.withTransaction {
                        engine.raw.syscall(
                            434L, // SYS_pidfd_open
                            io.mazewall.core.NativeArg.LongArg(tgid.toLong()),
                            io.mazewall.core.NativeArg.LongArg(0L),
                            io.mazewall.core.NativeArg.LongArg(0L),
                            io.mazewall.core.NativeArg.LongArg(0L),
                            io.mazewall.core.NativeArg.LongArg(0L),
                            io.mazewall.core.NativeArg.LongArg(0L)
                        ).map { it.toInt() }.recover { errno, _ ->
                            logger.severe { "[SUPERVISOR-DEBUG] pidfd_open failed for tid=${tid.value} with errno $errno" }
                            sendSeccompError(id, errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                            -1
                        }
                    }
                    if (pidfd < 0) return@use

                    val targetFd = args[0].toInt()
                    logger.info { "[SUPERVISOR-DEBUG] pidfd_open success. pidfd=$pidfd. Duplicating fd $targetFd..." }
                    val dupFd = engine.withTransaction {
                        engine.raw.syscall(
                            438L, // SYS_pidfd_getfd
                            io.mazewall.core.NativeArg.LongArg(pidfd.toLong()),
                            io.mazewall.core.NativeArg.LongArg(targetFd.toLong()),
                            io.mazewall.core.NativeArg.LongArg(0L),
                            io.mazewall.core.NativeArg.LongArg(0L),
                            io.mazewall.core.NativeArg.LongArg(0L),
                            io.mazewall.core.NativeArg.LongArg(0L)
                        ).map { it.toInt() }.recover { errno, _ ->
                            logger.severe { "[SUPERVISOR-DEBUG] pidfd_getfd failed for targetFd=$targetFd with errno $errno" }
                            sendSeccompError(id, errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                            -1
                        }
                    }

                    closeLocalFd(pidfd)
                    if (dupFd < 0) return@use
                    logger.info { "[SUPERVISOR-DEBUG] pidfd_getfd success. dupFd=$dupFd. Starting accept..." }

                    try {
                        val localAddr = arena.allocate(128)
                        val localAddrLen = arena.allocate(4)
                        localAddrLen.writeInt(0, 128)

                        val myArch = io.mazewall.core.Arch.current()
                        val accept4Sys = io.mazewall.core.NetworkSyscallMapper.numberFor(
                            io.mazewall.core.Syscall.ACCEPT4,
                            myArch
                        ).toLong()

                        val flags = if (nr == traceeArch.accept4) args[3].toInt() else 0

                        val clientFd = engine.withTransaction {
                            engine.raw.syscall(
                                accept4Sys,
                                io.mazewall.core.NativeArg.LongArg(dupFd.toLong()),
                                io.mazewall.core.NativeArg.MemoryArg(localAddr),
                                io.mazewall.core.NativeArg.MemoryArg(localAddrLen),
                                io.mazewall.core.NativeArg.LongArg(flags.toLong()),
                                io.mazewall.core.NativeArg.LongArg(0L),
                                io.mazewall.core.NativeArg.LongArg(0L)
                            ).map { it.toInt() }.recover { errno, _ ->
                                sendSeccompError(id, errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                                -1
                            }
                        }
                        if (clientFd < 0) return@use

                        try {
                            // Copy peer address back if tracee provided a buffer
                            val traceeAddrPtr = args[1]
                            val traceeAddrLenPtr = args[2]
                            if (traceeAddrPtr != 0L && traceeAddrLenPtr != 0L) {
                                val actualLen = localAddrLen.readInt(0)
                                val traceeAddrLenBytes = io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readBytes(tid, traceeAddrLenPtr, 4)
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
                                    MemorySegment.copy(localAddr, ValueLayout.JAVA_BYTE, 0L, addrBytes, 0, writeLen)
                                    SupervisorProcessMemoryWriter.writeBytes(tid, traceeAddrPtr, addrBytes)
                                }

                                val lenBytes = byteArrayOf(
                                    (actualLen and 0xFF).toByte(),
                                    ((actualLen shr 8) and 0xFF).toByte(),
                                    ((actualLen shr 16) and 0xFF).toByte(),
                                    ((actualLen shr 24) and 0xFF).toByte()
                                )
                                SupervisorProcessMemoryWriter.writeBytes(tid, traceeAddrLenPtr, lenBytes)
                            }

                            // Inject accepted FD
                            val addfd = SeccompNotifAddFdSegment(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
                            addfd.segment.fill(0)
                            addfd.setId(id)
                            addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
                            addfd.setSrcfd(clientFd)

                            val injectSuccess = engine.withTransaction {
                                engine.raw.ioctl(listenerFd, NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD, addfd.segment)
                                    .onFailure { errno, _ ->
                                        logger.severe { "Failed to inject accepted FD: errno=$errno" }
                                    }.isSuccess()
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
                    Arena.ofConfined().use { arena ->
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
