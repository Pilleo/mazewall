package io.mazewall.enforcer.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*
import io.mazewall.LinuxNative
import io.mazewall.platform.seccomp.SupervisedKind
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeArg
import io.mazewall.core.OpenFlags
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.typed
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SeccompNotifAddFdSegment
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.native
import io.mazewall.ffi.memory.fill
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
) : io.mazewall.platform.seccomp.daemon.SeccompNotifHandler {

    context(arena: io.mazewall.ffi.memory.NativeArena)
    override fun processNotification(
        notif: io.mazewall.ffi.memory.ManagedSegment,
        resp: io.mazewall.ffi.memory.ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>
    ): io.mazewall.platform.seccomp.daemon.NotifResult {
        return if (processNotification(notif, resp)) {
            io.mazewall.platform.seccomp.daemon.NotifResult.HANDLED
        } else {
            io.mazewall.platform.seccomp.daemon.NotifResult.TERMINATE
        }
    }

    companion object {
        private val logger = Logger.getLogger(SupervisorSessionHandler::class.java.name)

        private const val POLL_TIMEOUT_MS = 30000
        private const val AT_FDCWD = -100


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

    }

    context(arena: NativeArena)
    fun handleActiveListener(
        pollFds: ManagedSegment,
        notif: ManagedSegment,
        resp: ManagedSegment
    ): LoopAction {
        val pfd2 = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
        val socketRevents = pfd2.getRevents().toInt()
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
        if ((socketRevents and (NativeConstants.POLLIN.toInt() or errorOrHup)) != 0) {
            // JVM socket closed, errored, or sent shutdown
            return LoopAction.Shutdown
        }

        val pfd1 = PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE))
        val listenerRevents = pfd1.getRevents()
        if ((listenerRevents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
            notif.fill(0)
            var recvRes: LinuxNative.SyscallResult<Long, *>
            while (true) {
                recvRes = engine.raw.ioctl(listenerFd, IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV, notif.typed<IoctlPayload.SeccompNotif>())
                if (recvRes is LinuxNative.SyscallResult.Error<*> && recvRes.errno == NativeConstants.EINTR) {
                    continue
                }
                break
            }
            var ok = false
            recvRes.onSuccess {
                ok = processNotification(notif, resp)
            }
            if (!ok) return LoopAction.Break
        }

        return LoopAction.Continue
    }

    @Suppress("SwallowedException")
    private fun processNotification(notif: ManagedSegment, resp: ManagedSegment): Boolean {
        return NativeArena.ofConfined().use { notificationArena ->
            with(notificationArena) {
                val parsed = io.mazewall.platform.seccomp.SeccompNotifications.read(notif)
                val id = parsed.id
                try {
                    val pidVal = parsed.pid
                    val archVal = parsed.arch
                    val nr = parsed.nr
                    val args = parsed.args

                    val tid = Tid(pidVal)
                    val traceeArch = io.mazewall.core.Arch.fromAudit(archVal)
                    val extracted = extractNotificationArgs(nr, tid, args, traceeArch)
                    val kind = SupervisorNotificationMachine.classify(nr, traceeArch)
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
                    var resolvedPathStr: String? = extracted.pathStr
                    var resolvedPath: java.nio.file.Path? = null
                    if (kind is SupervisedKind.Open && extracted.pathStr != null) {
                        try {
                            resolvedPath = resolveAbsolutePath(pidVal, extracted.dirfd, extracted.pathStr)
                            if (resolvedPath != null) {
                                resolvedPathStr = resolvedPath.toAbsolutePath().toString()
                            }
                        } catch (e: Exception) {
                            logger.severe { "[SUPERVISOR-DEBUG] Fast-path check failed with critical error: ${e.message}" }
                            throw e
                        }
                    }
                    when (val route = SupervisorNotificationMachine.evaluateFastPath(kind, resolvedPath, extracted.pathStr)) {
                        is SupervisorRoute.Continue -> {
                            sendSeccompContinue(id, resp)
                            logger.info { "[SUPERVISOR-DEBUG] Fast-path allow continue resolved=$resolvedPathStr" }
                            return true
                        }
                        is SupervisorRoute.Abort -> {
                            logger.severe { "[SUPERVISOR-DEBUG] ${route.reason}" }
                            sendSeccompError(id, route.errno, resp)
                            return true
                        }
                        is SupervisorRoute.AskJvm -> { }
                        is SupervisorRoute.InjectFd,
                        is SupervisorRoute.SecureExec,
                        -> error("fast-path cannot inject or rewrite")
                    }

                    logger.info { "[SUPERVISOR-DEBUG] Forwarding request to JVM validation listener" }
                    val success = sendRequestToJvm(id, pidVal, archVal, ppid, nr, args, resolvedPathStr, extracted.sockaddrBytes)
                    if (!success) {
                        logger.severe { "[SUPERVISOR-DEBUG] Failed to send request to JVM" }
                        return false
                    }

                    val res = readAndHandleJvmResponse(id, nr, args, resolvedPathStr, extracted.sockaddrBytes, resp, tid, traceeArch)
                    logger.info { "[SUPERVISOR-DEBUG] JVM validation handler response result=$res" }
                    return res
                } catch (e: Exception) {
                    logger.log(java.util.logging.Level.SEVERE, "Fatal error processing notification $id", e)
                    try {
                        sendSeccompError(id, NativeConstants.EPERM, resp)
                    } catch (ignored: Exception) {
                        // Ignore secondary errors during best-effort EPERM response
                    }
                    return false
                }
            }
        }
    }

    context(arena: NativeArena)
    private fun extractNotificationArgs(nr: Int, tid: Tid, args: LongArray, arch: io.mazewall.core.Arch): SyscallArguments {
        var pathStr: String? = null
        var sockaddrBytes: ByteArray? = null
        var dirfd = AT_FDCWD
        when (nr) {
            arch.open -> {
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
            arch.execve -> {
                pathStr = readExecPath(tid, args[0])
            }
            arch.execveat -> {
                dirfd = args[0].toInt()
                pathStr = readExecPath(tid, args[1])
            }
        }
        return SyscallArguments(pathStr, sockaddrBytes, dirfd)
    }

    private fun resolveBypassPath(resolvedPath: java.nio.file.Path): java.nio.file.Path? {
        return resolvedPath.takeIf(BypassPaths::isBypassPath)
    }

    private fun resolveAbsolutePath(pid: Int, dirfd: Int, pathStr: String): java.nio.file.Path? {
        val path = java.nio.file.Paths.get(pathStr)
        if (path.isAbsolute) {
            try {
                return BypassPaths.toRealPathWithFallback(path)
            } catch (e: java.nio.file.NoSuchFileException) {
                return null
            } catch (e: java.io.FileNotFoundException) {
                return null
            } catch (e: Exception) {
                logger.severe { "Critical error during absolute path resolution for $pathStr: ${e.message}" }
                throw e
            }
        }
        try {
            val baseDir = if (dirfd == AT_FDCWD) {
                BypassPaths.toRealPathWithFallback(java.nio.file.Paths.get("/proc/$pid/cwd"))
            } else {
                BypassPaths.toRealPathWithFallback(java.nio.file.Paths.get("/proc/$pid/fd/$dirfd"))
            }
            return BypassPaths.toRealPathWithFallback(baseDir.resolve(path))
        } catch (e: java.nio.file.NoSuchFileException) {
            // /proc/<pid>/cwd or /proc/<pid>/fd/<dirfd> is gone. Do not invent a
            // path under a daemon bypass root; fail closed and let the caller deny.
            return null
        } catch (e: java.io.FileNotFoundException) {
            return null
        } catch (e: Exception) {
            logger.severe { "Critical error during baseDir or /proc resolution for pid=$pid dirfd=$dirfd path=$pathStr: ${e.message}" }
            throw e
        }
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
        val netBuf = NetworkOrderBuffer(buf.native)
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

        val writeRes = io.mazewall.core.SocketIo.writeFully(engine.memory, socketFd, buf, totalSize.toLong())
        return writeRes is LinuxNative.SyscallResult.Success<*, *>
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
        val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
        pollFd.setFd(socketFd.value)
        pollFd.setEvents(NativeConstants.POLLIN)

        val startMs = System.currentTimeMillis()
        var remainingTimeout = POLL_TIMEOUT_MS.toLong()
        var count = 0L
        val pollFdManaged = pollFd.managed
        var eintrCount = 0
        while (remainingTimeout > 0) {
            if (Thread.currentThread().isInterrupted) {
                logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM validation poll interrupted.")
                break
            }

            val loopStart = System.currentTimeMillis()
            val pollRes = engine.raw.poll(pollFdManaged, 1L, remainingTimeout.toInt())
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

            eintrCount++
            if (eintrCount > 1) {
                if (eintrCount > 3) {
                    try {
                        Thread.sleep(1)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                } else {
                    Thread.yield()
                }
            }
        }
        val durationMs = System.currentTimeMillis() - startMs
        if (durationMs > SLOW_VALIDATION_THRESHOLD_MS) {
            logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM policy validation took ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Possible deadlock or slow stack trace resolution.")
        }
        if (count <= 0) {
            logger.severe("[SUPERVISOR-DIAGNOSTIC] JVM validation timed out or failed after ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Closing socket to prevent desynchronization and returning EPERM.")
            try {
                socketManager.close(socketFd)
            } catch (ignored: Exception) {}
            sendSeccompError(id, NativeConstants.EPERM, resp)
            return false
        }

        val responseBuf = arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)
        val readRes = io.mazewall.core.SocketIo.readFully(
            engine.memory,
            socketFd,
            responseBuf,
            Layouts.SUPERVISOR_RESPONSE_SIZE,
        )
        if (readRes is LinuxNative.SyscallResult.Success && readRes.value == Layouts.SUPERVISOR_RESPONSE_SIZE) {
            val respSeg = SupervisorResponseSegment.of(responseBuf)
            val respId = respSeg.getId()
            val decision = respSeg.getDecision()
            val errorNr = respSeg.getErrorNr()

            if (respId != id) {
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return false
            }

            val jvmPath = respSeg.getPath()
            val kind = SupervisorNotificationMachine.classify(nr, traceeArch)
            val verdict = SupervisorNotificationMachine.parseJvmVerdict(decision.toInt(), errorNr)
            if (verdict == null) {
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return false
            }
            return when (val route = SupervisorNotificationMachine.evaluateJvm(kind, verdict)) {
                is SupervisorRoute.Abort -> {
                    sendSeccompError(id, route.errno, resp)
                    true
                }
                is SupervisorRoute.Continue -> {
                    sendSeccompContinue(id, resp)
                    true
                }
                is SupervisorRoute.InjectFd ->
                    handleInjectFd(id, nr, args, pathStr, sockaddrBytes, resp, tid, traceeArch)
                is SupervisorRoute.SecureExec ->
                    handleSecureExecve(id, nr, args, pathStr, jvmPath, resp, tid, traceeArch)
                is SupervisorRoute.AskJvm -> {
                    sendSeccompError(id, NativeConstants.EPERM, resp)
                    false
                }
            }
        } else {
            sendSeccompError(id, NativeConstants.EPERM, resp)
            return false
        }
    }

    /**
     * Approves execve/execveat by opening the validated binary in the supervisor,
     * injecting that fd into the tracee, and rewriting the in-flight syscall to
     * execveat(injected_fd, "", argv, envp, AT_EMPTY_PATH).
     *
     * USER_NOTIF CONTINUE on the original pathname is TOCTOU: a sibling can mutate
     * the buffer after write-back. If open, ADDFD, or register rewrite fails, deny.
     */
    context(arena: NativeArena)
    private fun handleSecureExecve(
        id: Long,
        nr: Int,
        args: LongArray,
        pathStr: String?,
        jvmPath: String?,
        resp: ManagedSegment,
        tid: Tid,
        traceeArch: io.mazewall.core.Arch,
    ): Boolean {
        fun abort(errno: Int, message: String): Boolean {
            logger.severe(message)
            requestParentRegisterRewrite(tid, -1, 0, 0, 0, 0)
            sendSeccompError(id, errno, resp)
            return true
        }
        val openPath = when (val plan = planExecRewrite(traceeArch, pathStr, jvmPath)) {
            is ExecRewritePlan.MissingPath ->
                return abort(NativeConstants.EPERM, "[SUPERVISOR-DIAGNOSTIC] Refusing execve without an inspectable path (fail closed).")
            is ExecRewritePlan.UnsupportedArch ->
                return abort(NativeConstants.EPERM, "[SUPERVISOR-DIAGNOSTIC] execve fd emulation requires x86_64 register rewrite. Denying.")
            is ExecRewritePlan.Ready -> plan.path
        }

        val pathSeg = arena.allocateFrom(openPath)
        val localFd = signedErrno(
            engine.fileSystem.open(
                pathSeg,
                OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC),
            ),
        )
        if (localFd < 0) {
            return abort(-localFd, "[SUPERVISOR-DIAGNOSTIC] open of exec target failed errno=${-localFd} path=$openPath")
        }
        return SafeLocalFd(localFd).use {
            val addfd = SeccompNotifAddFdSegment.of(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
            addfd.managed.fill(0)
            addfd.setId(id)
            addfd.setFlags(0)
            addfd.setSrcfd(localFd)
            addfd.setNewfdFlags(NativeConstants.O_CLOEXEC)
            val addfdRes = engine.raw.ioctl(
                listenerFd,
                IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD,
                addfd.managed.typed<IoctlPayload.SeccompNotifAddFd>(),
            )
            val injectedFd = when (addfdRes) {
                is LinuxNative.SyscallResult.Success -> addfdRes.value.toInt()
                is LinuxNative.SyscallResult.Error -> {
                    abort(NativeConstants.EPERM, "[SUPERVISOR-DIAGNOSTIC] SECCOMP_IOCTL_NOTIF_ADDFD failed errno=${addfdRes.errno}")
                    return@use true
                }
            }
            if (injectedFd < 0) {
                abort(NativeConstants.EPERM, "[SUPERVISOR-DIAGNOSTIC] SECCOMP_IOCTL_NOTIF_ADDFD returned invalid fd")
                return@use true
            }

            val pathAddr = if (nr == traceeArch.execve) args[0] else args[1]
            val argv = if (nr == traceeArch.execve) args[1] else args[2]
            val envp = if (nr == traceeArch.execve) args[2] else args[3]
            val emptyPathAddr = TraceeReadOnlyNul.find(tid) ?: pathAddr
            val rewritten = requestParentRegisterRewrite(
                tid,
                injectedFd,
                emptyPathAddr,
                argv,
                envp,
                traceeArch.execveat.toLong(),
            )
            if (!rewritten) {
                logger.severe("[SUPERVISOR-DIAGNOSTIC] Failed to retarget execve at injected fd. Denying.")
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return@use true
            }
            sendSeccompContinue(id, resp)
            true
        }
    }

    context(arena: NativeArena)
    private fun requestParentRegisterRewrite(
        tid: Tid,
        injectedFd: Int,
        emptyPathAddr: Long,
        argv: Long,
        envp: Long,
        execveatNr: Long,
    ): Boolean {
        val buf = arena.allocate(48)
        val net = NetworkOrderBuffer(buf.native)
        net.writeLong(0, injectedFd.toLong())
        net.writeLong(8, emptyPathAddr)
        net.writeLong(16, argv)
        net.writeLong(24, envp)
        net.writeLong(32, execveatNr)
        net.writeLong(40, tid.value.toLong())
        val writeRes = io.mazewall.core.SocketIo.writeFully(engine.memory, socketFd, buf, 48)
        if (writeRes is LinuxNative.SyscallResult.Error) {
            return false
        }
        val ack = arena.allocate(1)
        val readRes = io.mazewall.core.SocketIo.readFully(engine.memory, socketFd, ack, 1)
        return readRes is LinuxNative.SyscallResult.Success && ack.readByte(0) == 1.toByte()
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
            localFdValue = when (injectTarget(SupervisorNotificationMachine.classify(nr, traceeArch))) {
                is InjectTarget.Open -> {
                    if (pathStr == null) {
                        sendSeccompError(id, NativeConstants.EPERM, resp)
                        return true
                    }
                    openFileInSupervisor(nr, args, pathStr, traceeArch, tid)
                }
                is InjectTarget.Connect -> {
                    if (sockaddrBytes == null) {
                        sendSeccompError(id, NativeConstants.EPERM, resp)
                        return true
                    }
                    connectSocketInSupervisor(sockaddrBytes)
                }
                is InjectTarget.Accept -> {
                    handleAcceptAsync(id, nr, args, tid, traceeArch)
                    return true
                }
                is InjectTarget.Unsupported -> -NativeConstants.EPERM
            }

            if (localFdValue < 0) {
                logger.warning { "[SUPERVISOR-DEBUG] localFdValue is negative error: $localFdValue. Sending seccomp error." }
                sendSeccompError(id, -localFdValue, resp)
                return true
            }

            val addfd = SeccompNotifAddFdSegment.of(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
            addfd.managed.fill(0)
            addfd.setId(id)
            addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
            addfd.setSrcfd(localFdValue)
            addfd.setNewfdFlags(NativeConstants.O_CLOEXEC)

            val addfdManaged = addfd.managed
            var success = false
            while (true) {
                val ioctlRes = engine.raw.ioctl(listenerFd, IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD, addfdManaged.typed<IoctlPayload.SeccompNotifAddFd>())
                logger.info { "[SUPERVISOR-DEBUG] ioctl SECCOMP_IOCTL_NOTIF_ADDFD res=$ioctlRes" }
                if (ioctlRes is LinuxNative.SyscallResult.Success<*, *>) {
                    success = true
                    break
                } else if (ioctlRes is LinuxNative.SyscallResult.Error<*> && ioctlRes.errno == NativeConstants.EINTR) {
                    continue
                } else {
                    break
                }
            }

            if (!success) {
                logger.severe { "[SUPERVISOR-DEBUG] ioctl SECCOMP_IOCTL_NOTIF_ADDFD failed. Sending EPERM." }
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return true
            }
            return true
        } finally {
            if (localFdValue >= 0) {
                closeLocalFd(localFdValue)
            }
        }
    }

    context(arena: NativeArena)
    private fun openFileInSupervisor(
        nr: Int,
        args: LongArray,
        pathStr: String,
        arch: io.mazewall.core.Arch,
        tid: Tid,
    ): Int {
        val flags = if (nr == arch.open) args[1].toInt() else args[2].toInt()
        val pathSeg = arena.allocateFrom(pathStr)
        val dirfd = if (nr == arch.open || pathStr.startsWith("/")) AT_FDCWD else args[0].toInt()
        if (dirfd == AT_FDCWD || pathStr.startsWith("/")) {
            return signedErrno(engine.fileSystem.open(pathSeg, io.mazewall.core.OpenFlags(flags)))
        }
        return openatRelativeToTraceeDirfd(tid, dirfd, pathSeg, flags)
    }

    /**
     * Imports [traceeDirfd] into this process with pidfd_getfd(2), then openat(2).
     * Never treats the tracee's integer as a local file descriptor.
     */
    context(arena: NativeArena)
    private fun openatRelativeToTraceeDirfd(
        tid: Tid,
        traceeDirfd: Int,
        pathSeg: ManagedSegment,
        flags: Int,
    ): Int {
        val tgid = getTgid(tid.value)
        val pidfdRes = engine.process.pidfdOpen(tgid, 0)
        val pidfdVal = when (pidfdRes) {
            is LinuxNative.SyscallResult.Success -> pidfdRes.value.toInt()
            is LinuxNative.SyscallResult.Error -> return -pidfdRes.errno
        }
        return SafeLocalFd(pidfdVal).use { pidfdSafe ->
            val pidfd = FileDescriptor.pid(pidfdSafe.fd)
            val importedRes = engine.process.pidfdGetFd(pidfd, traceeDirfd, 0)
            val importedVal = when (importedRes) {
                is LinuxNative.SyscallResult.Success -> importedRes.value.toInt()
                is LinuxNative.SyscallResult.Error -> return@use -importedRes.errno
            }
            SafeLocalFd(importedVal).use { importedSafe ->
                val imported = FileDescriptor.oPath(importedSafe.fd)
                signedErrno(engine.fileSystem.openat(imported, pathSeg, io.mazewall.core.OpenFlags(flags)))
            }
        }
    }

    private fun signedErrno(res: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>): Int {
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

        val socketRes = {
            val res = engine.networking.socket(domain, 1, 0) // SOCK_STREAM = 1
            when (res) {
                is LinuxNative.SyscallResult.Success -> res.value.toInt()
                is LinuxNative.SyscallResult.Error -> -res.errno
            }
        }()
        if (socketRes < 0) return socketRes

        val addr = arena.allocate(sockaddrBytes.size.toLong())
        ManagedSegment.copy(sockaddrBytes, 0, addr, 0L, sockaddrBytes.size)

        val connectErr = {
            val res = engine.networking.connect(
                FileDescriptor.unixSocket(socketRes),
                addr,
                sockaddrBytes.size
            )
            when (res) {
                is LinuxNative.SyscallResult.Success -> 0
                is LinuxNative.SyscallResult.Error -> res.errno
            }
        }()
        if (connectErr != 0) {
            closeLocalFd(socketRes)
            return -connectErr
        }
        return socketRes
    }

    private fun closeLocalFd(fd: Int) {
        val token = FileDescriptor.generic(fd)
        if (token.isLiveForIo()) {
            engine.fileSystem.close(token)
        }
    }

    private inner class SafeLocalFd(
        val handle: FileDescriptor<*, FdState.Open>,
    ) : AutoCloseable {
        constructor(fd: Int) : this(FileDescriptor.generic(fd))

        val fd: Int get() = handle.value

        override fun close() {
            if (handle.isLiveForIo()) {
                engine.fileSystem.close(handle)
            }
        }
    }

    private fun sendSeccompContinue(id: Long, resp: ManagedSegment) {
        io.mazewall.platform.seccomp.UserNotifReply.encodeContinue(resp, id)
        io.mazewall.platform.seccomp.UserNotifReply.send(engine.raw, listenerFd, resp)
    }

    private fun sendSeccompError(id: Long, errorNr: Int, resp: ManagedSegment) {
        io.mazewall.platform.seccomp.UserNotifReply.encodeError(resp, id, errorNr)
        io.mazewall.platform.seccomp.UserNotifReply.send(engine.raw, listenerFd, resp)
    }

    context(arena: NativeArena)
    private fun readStringFromProcess(tid: Tid, remoteAddr: Long): String? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readString(tid, remoteAddr, MAX_PATH_LEN)
    }

    context(arena: NativeArena)
    private fun readExecPath(tid: Tid, remoteAddr: Long): String? {
        return try {
            readStringFromProcess(tid, remoteAddr)
        } catch (e: ContainmentViolationException) {
            logger.warning("[SUPERVISOR-DIAGNOSTIC] Cannot inspect exec path for tid=${tid.value}: ${e.message}")
            null
        }
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
                NativeArena.ofConfined().use { arena ->
                    with(arena) {
                        val tgid = getTgid(tid.value)
                        logger.info { "[SUPERVISOR-DEBUG] Async accept worker started for tid=${tid.value} (tgid=$tgid), targetFd=${args[0].toInt()}" }
                        val pidfdRes: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                            engine.process.pidfdOpen(tgid, 0)
                        val pidfd = when (pidfdRes) {
                            is LinuxNative.SyscallResult.Success -> pidfdRes.value.toInt()
                            is LinuxNative.SyscallResult.Error -> {
                                logger.severe { "[SUPERVISOR-DEBUG] pidfd_open failed for tid=${tid.value} with errno ${pidfdRes.errno}" }
                                sendSeccompError(id, pidfdRes.errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                                return@use
                            }
                        }

                        SafeLocalFd(pidfd).use { pidfdSafe ->
                            val targetFd = args[0].toInt()
                            logger.info { "[SUPERVISOR-DEBUG] pidfd_open success. pidfd=${pidfdSafe.fd}. Duplicating fd $targetFd..." }
                            val dupRes: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                                engine.process.pidfdGetFd(FileDescriptor.pid(pidfdSafe.fd), targetFd, 0)

                            val dupFd = when (dupRes) {
                                is LinuxNative.SyscallResult.Success -> dupRes.value.toInt()
                                is LinuxNative.SyscallResult.Error -> {
                                    logger.severe { "[SUPERVISOR-DEBUG] pidfd_getfd failed for targetFd=$targetFd with errno ${dupRes.errno}" }
                                    sendSeccompError(id, dupRes.errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                                    return@use
                                }
                            }

                            SafeLocalFd(dupFd).use { dupFdSafe ->
                                logger.info { "[SUPERVISOR-DEBUG] pidfd_getfd success. dupFd=${dupFdSafe.fd}. Starting accept..." }

                                val localAddr = arena.allocate(128)
                                val localAddrLen = arena.allocate(4)
                                localAddrLen.writeInt(0, 128)

                                val flags = if (nr == traceeArch.accept4) args[3].toInt() else 0

                                val acceptRes =
                                    engine.networking.accept4(
                                        FileDescriptor.adopt(dupFdSafe.fd, FileDescriptorRole.Generic),
                                        localAddr,
                                        localAddrLen,
                                        flags
                                    )

                                val clientFd = when (acceptRes) {
                                    is LinuxNative.SyscallResult.Success -> acceptRes.value.toInt()
                                    is LinuxNative.SyscallResult.Error -> {
                                        sendSeccompError(id, acceptRes.errno, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                                        return@use
                                    }
                                }

                                SafeLocalFd(clientFd).use { clientFdSafe ->
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
                                            ManagedSegment.copy(localAddr, 0L, addrBytes, 0, writeLen)
                                            io.mazewall.ffi.memory.SupervisorProcessMemoryWriter.writeBytes(tid, traceeAddrPtr, addrBytes)
                                        }

                                        val lenBytes = byteArrayOf(
                                            (actualLen and 0xFF).toByte(),
                                            ((actualLen shr 8) and 0xFF).toByte(),
                                            ((actualLen shr 16) and 0xFF).toByte(),
                                            ((actualLen shr 24) and 0xFF).toByte()
                                        )
                                        io.mazewall.ffi.memory.SupervisorProcessMemoryWriter.writeBytes(tid, traceeAddrLenPtr, lenBytes)
                                    }

                                    // Inject accepted FD
                                    val addfd = SeccompNotifAddFdSegment.of(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
                                    addfd.managed.fill(0)
                                    addfd.setId(id)
                                    addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
                                    addfd.setSrcfd(clientFdSafe.fd)
                                    addfd.setNewfdFlags(NativeConstants.O_CLOEXEC)

                                    val addfdManaged = addfd.managed
                                    var injectSuccess = false
                                    while (true) {
                                        val ioctlRes = engine.raw.ioctl(listenerFd, IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD, addfdManaged.typed<IoctlPayload.SeccompNotifAddFd>())
                                        if (ioctlRes is LinuxNative.SyscallResult.Success<*, *>) {
                                            injectSuccess = true
                                            break
                                        } else if (ioctlRes is LinuxNative.SyscallResult.Error<*> && ioctlRes.errno == NativeConstants.EINTR) {
                                            continue
                                        } else {
                                            break
                                        }
                                    }

                                    if (!injectSuccess) {
                                        sendSeccompError(id, NativeConstants.EPERM, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.log(java.util.logging.Level.SEVERE, "Error in async accept worker for notification $id", e)
                try {
                    NativeArena.ofConfined().use { arena ->
                        sendSeccompError(id, NativeConstants.EPERM, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                    }
                } catch (ignored: Exception) {}
            }
        }.apply {
            isDaemon = true
            name = "supervisor-accept-worker-$id"
            start()
        }
    }
}
