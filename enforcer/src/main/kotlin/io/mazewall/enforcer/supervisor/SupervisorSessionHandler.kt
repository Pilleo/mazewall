package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeArg
import io.mazewall.core.NewFdFlags
import io.mazewall.core.OpenFlags
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SeccompNotifAddFdSegment
import io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.fill
import io.mazewall.ffi.memory.native
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeIntUnaligned
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.memory.writeLongUnaligned
import io.mazewall.ffi.networking.NetworkOrderBuffer
import io.mazewall.ffi.supervisor.JvmVerdictRequestEncoder
import io.mazewall.ffi.typed
import io.mazewall.onSuccess
import io.mazewall.platform.seccomp.SupervisedKind
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.recover
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

internal data class SyscallArguments(
    val pathStr: String?,
    val sockaddrBytes: ByteArray?,
    val dirfd: TraceeDirFd = TraceeDirFd.CurrentWorkingDirectory,
    val openHow: OpenHow? = null,
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

private fun Logger.log(
    level: java.util.logging.Level,
    msg: String,
    t: Throwable,
) {
    System.err.println("[SUPERVISOR-$level] $msg")
    t.printStackTrace()
}

internal class SupervisorSessionHandler(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    private val engine: io.mazewall.NativeEngine = io.mazewall.LinuxNative,
    private val socketManager: io.mazewall.core.SocketManager = io.mazewall.core.RealSocketManager,
) : io.mazewall.platform.seccomp.daemon.SeccompNotifHandler {
    private val reader = NotificationReader(engine, logger)
    private val terminalRoutes = SupervisorTerminalRoutes(object : SupervisorResponseSender {
        override fun continueNotification(
            id: Long,
            response: ManagedSegment,
        ) = sendSeccompContinue(id, response)

        override fun abortNotification(
            id: Long,
            errno: Int,
            response: ManagedSegment,
        ) = sendSeccompError(id, errno, response)
    })
    private val routeDispatcher = SupervisorRouteDispatcher(terminalRoutes)
    private val routeExecutor = object : SupervisorRouteExecutor {
        context(arena: NativeArena) override fun injectFd(context: SupervisorRouteContext): Boolean = handleInjectFd(context)

        context(arena: NativeArena) override fun secureExec(
            context: SupervisorRouteContext,
            jvmPath: String?,
        ): Boolean = handleSecureExecve(context, jvmPath)
    }

    context(arena: io.mazewall.ffi.memory.NativeArena) override fun processNotification(
        notif: io.mazewall.ffi.memory.ManagedSegment,
        resp: io.mazewall.ffi.memory.ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
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

    context(arena: NativeArena) fun handleActiveListener(
        pollFds: ManagedSegment,
        notif: ManagedSegment,
        resp: ManagedSegment,
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
            val received = reader.recvNotification(listenerFd, notif)
            var ok = false
            if (received) {
                ok = processNotification(notif, resp)
            }
            if (!ok) return LoopAction.Break
        }

        return LoopAction.Continue
    }

    @Suppress("SwallowedException")
    internal fun processNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
    ): Boolean {
        return NativeArena.ofConfined().use { notificationArena ->
            with(notificationArena) {
                val parsed = io.mazewall.platform.seccomp.SeccompNotifications
                    .read(notif)
                val id = parsed.id
                try {
                    processParsedNotification(parsed, resp)
                } catch (e: Exception) {
                    logger.log(java.util.logging.Level.SEVERE, "Fatal error processing notification $id", e)
                    try {
                        sendSeccompError(id, NativeConstants.EPERM, resp)
                    } catch (ignored: Exception) {
                        // Ignore secondary errors during best-effort EPERM response
                    }
                    false
                }
            }
        }
    }

    context(arena: NativeArena) private fun processParsedNotification(
        parsed: io.mazewall.platform.seccomp.SeccompNotification,
        resp: ManagedSegment,
    ): Boolean {
        val id = parsed.id

                    var sessionState = SupervisorSessionMachine
                        .evaluate(
                            SupervisorSessionState.AwaitingNotification,
                            SupervisorSessionEvent.NotificationReceived,
                        ).state
                    val pidVal = parsed.pid
                    val archVal = parsed.arch
                    val nr = parsed.nr
                    val args = parsed.args

                    val tid = Tid(pidVal)
                    val traceeArch = io.mazewall.core.Arch
                        .fromAudit(archVal)
                    val extracted = extractNotificationArgs(nr, tid, args, traceeArch)
                    val header = NotifHeader(nr, tid, traceeArch, archVal, getPpid(pidVal), args)
                    val kind = SupervisorNotificationMachine.classify(io.mazewall.core.SyscallNumber(header.nr), header.arch)
                    logger.info { "[SUPERVISOR-DEBUG] Received syscall notification: id=$id, pid=$pidVal, arch=$archVal, ppid=${header.ppid}, nr=$nr, path=${extracted.pathStr}" }

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
                    val resolvedFastPath = resolveFastPath(pidVal, kind, extracted)
                    val request = JvmVerdictRequest(id, header, extracted.pathStr, extracted.sockaddrBytes)
                    val routeContext = SupervisorRouteContext(request, extracted, resp)
                    val transition = SupervisorSessionMachine.evaluate(
                        sessionState,
                        SupervisorSessionEvent.PathResolved(
                            SupervisorNotificationMachine.evaluateFastPath(kind, resolvedFastPath.path, extracted.pathStr),
                        ),
                    )
                    sessionState = transition.state
                    when (val effect = transition.effect) {
                        is SupervisorSessionEffect.ExecuteRoute -> {
                            when (val route = effect.route) {
                                SupervisorRoute.Continue ->
                                    logger.info { "[SUPERVISOR-DEBUG] Fast-path allow continue resolved=${resolvedFastPath.displayPath}" }
                                is SupervisorRoute.Abort -> logger.severe { "[SUPERVISOR-DEBUG] ${route.reason}" }
                                is SupervisorRoute.AskJvm,
                                SupervisorRoute.InjectFd,
                                SupervisorRoute.SecureExec,
                                -> error("fast-path cannot inject, rewrite, or ask JVM after a terminal transition")
                            }
                            terminalRoutes.execute(effect.route, routeContext)
                            return true
                        }
                        SupervisorSessionEffect.RequestJvmVerdict -> Unit
                        is SupervisorSessionEffect.Abort -> {
                            terminalRoutes.abort(routeContext, effect.errno)
                            return false
                        }
                        SupervisorSessionEffect.ResolvePath,
                        null,
                        -> error("path resolution must produce a terminal route or JVM request")
                    }

                    logger.info { "[SUPERVISOR-DEBUG] Forwarding request to JVM validation listener" }
                    val success = sendRequestToJvm(request)
                    if (!success) {
                        logger.severe { "[SUPERVISOR-DEBUG] Failed to send request to JVM" }
                        return false
                    }

                    val res = readAndHandleJvmResponse(routeContext)
                    logger.info { "[SUPERVISOR-DEBUG] JVM validation handler response result=$res" }
                    return res
    }

    private fun resolveFastPath(
        pid: Int,
        kind: SupervisedKind,
        extracted: SyscallArguments,
    ): ResolvedFastPath {
        try {
            return SupervisorFastPathResolution.resolve(pid, kind, extracted)
        } catch (e: Exception) {
            logger.severe { "[SUPERVISOR-DEBUG] Fast-path check failed with critical error: ${e.message}" }
            throw e
        }
    }

    context(arena: NativeArena) private fun extractNotificationArgs(
        nr: Int,
        tid: Tid,
        args: LongArray,
        arch: io.mazewall.core.Arch,
    ): SyscallArguments =
        SupervisorArgumentExtractor.extract(
        nr,
        tid,
        args,
        arch,
        readString = { readTid, address -> readExecPath(readTid, address) },
        readBytes = { readTid, address, length -> readBytesFromProcess(readTid, address, length) },
    )

    internal fun resolveBypassPath(resolvedPath: java.nio.file.Path): java.nio.file.Path? {
        return resolvedPath.takeIf(BypassPaths::isBypassPath)
    }

    context(arena: NativeArena) internal fun sendRequestToJvm(request: JvmVerdictRequest): Boolean {
        val encoded = JvmVerdictRequestEncoder.encode(
            request.id,
            request.header.tid.value,
            request.header.audit,
            request.header.ppid,
            request.header.nr,
            request.header.args,
            request.path,
            request.sockaddrBytes,
        )
        val writeRes = io.mazewall.core.SocketIo
            .writeFully(engine.memory, socketFd, encoded.buffer, encoded.size)
        return writeRes is LinuxNative.SyscallResult.Success<*, *>
    }

    context(arena: NativeArena) internal fun readAndHandleJvmResponse(context: SupervisorRouteContext): Boolean {
        val (request, _, resp) = context
        val (id, header, pathStr) = request
        val (nr, _, traceeArch) = header
        val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
        pollFd.setFd(socketFd.value)
        pollFd.setEvents(NativeConstants.POLLIN)

        val startMs = System.currentTimeMillis()
        val await = reader.awaitJvmResponse(socketFd, POLL_TIMEOUT_MS.toLong(), SLOW_VALIDATION_THRESHOLD_MS)
        val count = await.revents
        val deadline = Deadline.afterMillis(await.remainingMillis.toLong())
        val framePollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
        framePollFd.setFd(socketFd.value)
        framePollFd.setEvents(NativeConstants.POLLIN)
        val pollFdManaged = framePollFd.managed
        val durationMs = System.currentTimeMillis() - startMs
        if (durationMs > SLOW_VALIDATION_THRESHOLD_MS) {
            logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM policy validation took ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Possible deadlock or slow stack trace resolution.")
        }

        if (count <= 0) {
            logger.severe(
                "[SUPERVISOR-DIAGNOSTIC] JVM validation timed out or failed after ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Closing socket to prevent desynchronization and returning EPERM.",
            )
            try {
                socketManager.close(socketFd)
            } catch (ignored: Exception) {
                }
            sendSeccompError(id, NativeConstants.EPERM, resp)
            return false
        }

        val responseBuf = arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)
        val readRes = io.mazewall.core.SocketIo.readFully(
            engine.memory,
            socketFd,
            responseBuf,
            Layouts.SUPERVISOR_RESPONSE_SIZE,
            deadline,
        ) { timeoutMs -> engine.raw.poll(pollFdManaged, 1L, timeoutMs) }
        if (readRes is LinuxNative.SyscallResult.Error && readRes.errno == NativeConstants.ETIMEDOUT) {
            logger.severe(
                "[SUPERVISOR-DIAGNOSTIC] JVM validation frame timed out after ${durationMs}ms (syscall nr=$nr, path=$pathStr, id=$id). Closing socket to prevent desynchronization and returning EPERM.",
            )
            try {
                socketManager.close(socketFd)
            } catch (ignored: Exception) {
                }
            sendSeccompError(id, NativeConstants.EPERM, resp)
            return false
        }
        if (readRes is LinuxNative.SyscallResult.Success && readRes.value == Layouts.SUPERVISOR_RESPONSE_SIZE) {
            val respSeg = SupervisorResponseSegment.of(responseBuf)
            val respId = respSeg.getId()
            val decision = respSeg.getDecision()
            val errorNr = respSeg.getErrorNr()

            val route = SupervisorJvmVerdictResolver.route(
                id,
                nr,
                traceeArch,
                respId,
                decision.toInt(),
                errorNr,
            )
            if (route == null) {
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return false
            }

            val jvmPath = respSeg.getPath()
            val transition = SupervisorSessionMachine.evaluate(
                SupervisorSessionState.AwaitingJvmVerdict,
                SupervisorSessionEvent.JvmVerdictReceived(route),
            )
            val effect = transition.effect as? SupervisorSessionEffect.ExecuteRoute
                ?: return terminalRoutes.abort(context, NativeConstants.EPERM)
            return routeDispatcher.execute(
                effect.route,
                context,
                jvmPath,
                routeExecutor,
            )
        } else {
            sendSeccompError(id, NativeConstants.EPERM, resp)
            return false
        }
    }

    /**
     * Supervised execve/execveat handling (issue-20260817-033800): opens the validated binary
     * in the supervisor, injects it via SECCOMP_IOCTL_NOTIF_ADDFD, then REQUESTS a parent-side
     * register rewrite to execveat(injected_fd, "", AT_EMPTY_PATH) over a read-only NUL.
     *
     * The parent currently REFUSES all rewrite requests (completeParentExecRewrite ->
     * issue-20260817-033800 pending kernel-supported replacement), so the effective behavior is
     * FAIL-CLOSED EPERM for every supervised exec on every architecture, with parent stack-trace
     * attribution preserved for the resulting violation. USER_NOTIF CONTINUE on the original
     * pathname is never used: a sibling can mutate the buffer after write-back.
     */
    context(arena: NativeArena) private fun handleSecureExecve(
        context: SupervisorRouteContext,
        jvmPath: String?,
    ): Boolean {
        val (request, _, resp) = context
        val (id, header, pathStr) = request
        val (nr, tid, traceeArch, _, _, args) = header

        fun abort(
            errno: Int,
            message: String,
        ): Boolean {
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
            addfd.setNewfdFlags(
                io.mazewall.core.NewFdFlags
                .forExec()
                .value,
            )
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

            val argv = if (nr == traceeArch.execve) args[1] else args[2]
            val envp = if (nr == traceeArch.execve) args[2] else args[3]
            // pathname for execveat(AT_EMPTY_PATH) MUST point at memory the tracee cannot
            // overwrite (issue-20260817-033800): falling back to the original args[0]/args[1]
            // pointer would resurrect the rename-after-approval TOCTOU this design exists to
            // prevent. No read-only NUL -> deny (and release the injected fd).
            val emptyPathAddr = TraceeReadOnlyNul.find(tid)
            if (emptyPathAddr == null) {
                logger.severe("[SUPERVISOR-DIAGNOSTIC] No read-only NUL byte found in tracee mappings; cannot stage a tamper-proof AT_EMPTY_PATH pathname. Denying.")
                closeTraceeFd(tid, injectedFd)
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return@use true
            }
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
                closeTraceeFd(tid, injectedFd)
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return@use true
            }
            sendSeccompContinue(id, resp)
            true
        }
    }

    context(arena: NativeArena) private fun requestParentRegisterRewrite(
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
        val writeRes = io.mazewall.core.SocketIo
            .writeFully(engine.memory, socketFd, buf, 48)
        if (writeRes is LinuxNative.SyscallResult.Error) {
            return false
        }
        val ack = arena.allocate(1)
        val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
        pollFd.setFd(socketFd.value)
        pollFd.setEvents(NativeConstants.POLLIN)
        val pollFdManaged = pollFd.managed
        val readRes = io.mazewall.core.SocketIo.readFully(
            engine.memory,
            socketFd,
            ack,
            1,
            Deadline.afterMillis(POLL_TIMEOUT_MS.toLong()),
        ) { timeoutMs -> engine.raw.poll(pollFdManaged, 1L, timeoutMs) }
        return readRes is LinuxNative.SyscallResult.Success && ack.readByte(0) == 1.toByte()
    }

    context(arena: NativeArena) internal fun handleInjectFd(context: SupervisorRouteContext): Boolean {
        val (request, extracted, resp) = context
        val id = request.id
        val header = request.header
        val nr = header.nr
        val tid = header.tid
        val traceeArch = header.arch
        var localFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>? = null
        var injectFlags = NewFdFlags.NONE
        try {
            val localFdValue = when (val plan = SupervisorInjectionPlan.create(nr, traceeArch, header.args, extracted)) {
                is SupervisorInjectionPlan.Open -> {
                    val req = plan.request
                    injectFlags =
                        when (req) {
                            is SupervisedOpen.Open -> NewFdFlags.forOpen(req.flags)
                            is SupervisedOpen.OpenAt -> NewFdFlags.forOpen(req.flags)
                            is SupervisedOpen.OpenAt2 -> NewFdFlags.forOpen(req.how.flags)
                        }
                    openFileInSupervisor(req, tid)
                }
                is SupervisorInjectionPlan.Connect -> connectSocketInSupervisor(plan.sockaddr)
                SupervisorInjectionPlan.Accept -> {
                    handleAcceptAsync(id, nr, header.args, tid, traceeArch)
                    return true
                }
                is SupervisorInjectionPlan.Deny -> -plan.errno
            }

            if (localFdValue < 0) {
                logger.warning { "[SUPERVISOR-DEBUG] localFdValue is negative error: $localFdValue. Sending seccomp error." }
                sendSeccompError(id, -localFdValue, resp)
                return true
            }
            localFd = FileDescriptor.adopt(localFdValue, FileDescriptorRole.Generic)

            if (!injectFd(id, localFd, injectFlags)) {
                logger.severe { "[SUPERVISOR-DEBUG] ioctl SECCOMP_IOCTL_NOTIF_ADDFD failed. Sending EPERM." }
                sendSeccompError(id, NativeConstants.EPERM, resp)
                return true
            }

            return true
        } finally {
            localFd?.let(::closeLocalFd)
        }
    }

    context(arena: NativeArena) private fun injectFd(
        id: Long,
        localFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        flags: NewFdFlags,
    ): Boolean {
        val addfd = SeccompNotifAddFdSegment.of(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
        addfd.managed.fill(0)
        addfd.setId(id)
        addfd.setFlags(NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())
        addfd.setSrcfd(localFd.value)
        addfd.setNewfdFlags(flags.value)
        while (true) {
            when (val result = engine.raw.ioctl(listenerFd, IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD, addfd.managed.typed<IoctlPayload.SeccompNotifAddFd>())) {
                is LinuxNative.SyscallResult.Success -> return true
                is LinuxNative.SyscallResult.Error -> if (result.errno != NativeConstants.EINTR) {
                    if (result.errno == NativeConstants.EBADF) {
                        logger.severe { "[SUPERVISOR-SECURITY] SECCOMP_IOCTL_NOTIF_ADDFD failed with EBADF (listenerFd=${listenerFd.value}, srcfd=${localFd.value})." }
                    } else {
                        logger.severe { "[SUPERVISOR-DEBUG] SECCOMP_IOCTL_NOTIF_ADDFD failed with errno ${result.errno}." }
                    }
                    return false
                }
            }
        }
    }

    context(arena: NativeArena) internal fun openFileInSupervisor(
        nr: Int,
        args: LongArray,
        pathStr: String,
        arch: io.mazewall.core.Arch,
        tid: Tid,
    ): Int = openFileInSupervisor(nr, args, pathStr, arch, tid, null)

    context(arena: NativeArena) internal fun openFileInSupervisor(
        nr: Int,
        args: LongArray,
        pathStr: String,
        arch: io.mazewall.core.Arch,
        tid: Tid,
        openHow: OpenHow?,
    ): Int {
        val req = SupervisedOpen.parse(nr, args, pathStr, arch, openHow) ?: return -NativeConstants.EPERM
        return openFileInSupervisor(req, tid)
    }

    context(arena: NativeArena) internal fun openFileInSupervisor(
        req: SupervisedOpen,
        tid: Tid,
    ): Int {
        val howSeg = arena.allocate(Layouts.OPEN_HOW_SIZE)

        val flags = when (req) {
            is SupervisedOpen.Open -> req.flags.value.toLong()
            is SupervisedOpen.OpenAt -> req.flags.value.toLong()
            is SupervisedOpen.OpenAt2 ->
                req.how.flags.value
                .toLong()
        }
        val mode = when (req) {
            is SupervisedOpen.Open -> req.mode.toLong()
            is SupervisedOpen.OpenAt -> req.mode.toLong()
            is SupervisedOpen.OpenAt2 -> req.how.mode
        }
        val resolve = when (req) {
            is SupervisedOpen.OpenAt2 -> req.how.resolve or NativeConstants.RESOLVE_BENEATH.toLong()
            is SupervisedOpen.Open,
            is SupervisedOpen.OpenAt,
            -> 0L
        }

        howSeg.writeLong(Layouts.OPEN_HOW_FLAGS_OFFSET, flags)
        howSeg.writeLong(Layouts.OPEN_HOW_MODE_OFFSET, mode)
        howSeg.writeLong(Layouts.OPEN_HOW_RESOLVE_OFFSET, resolve)

        val (isAbsolute, traceeDirfd) = when (req) {
            is SupervisedOpen.Open -> true to TraceeDirFd.CurrentWorkingDirectory
            is SupervisedOpen.OpenAt -> req.path.startsWith("/") to req.dirfd
            is SupervisedOpen.OpenAt2 -> req.path.startsWith("/") to req.dirfd
        }

        val pathSeg = arena.allocateFrom(req.path)

        if (isAbsolute || traceeDirfd == TraceeDirFd.CurrentWorkingDirectory) {
            return signedErrno(
                engine.fileSystem.openat2(
                    FileDescriptor.AT_FDCWD,
                    pathSeg,
                    howSeg,
                    Layouts.OPEN_HOW_SIZE,
                ),
            )
        } else {
            val importedFd = importTraceeFd(tid, traceeDirfd)
            if (importedFd < 0) return importedFd
            return SafeLocalFd(importedFd).use { importedSafe ->
                signedErrno(engine.fileSystem.openat2(importedSafe.handle, pathSeg, howSeg, Layouts.OPEN_HOW_SIZE))
            }
        }
    }

    private fun importTraceeFd(
        tid: Tid,
        traceeDirfd: TraceeDirFd,
    ): Int {
        if (traceeDirfd == TraceeDirFd.CurrentWorkingDirectory) {
            val cwdPath = "/proc/${getTgid(tid.value)}/cwd"
            val fd = NativeArena.ofConfined().use { arena ->
                engine.fileSystem.open(
                    arena.allocateFrom(cwdPath),
                    io.mazewall.core.OpenFlags(NativeConstants.O_PATH or NativeConstants.O_DIRECTORY),
                    0,
                )
            }
            if (fd is LinuxNative.SyscallResult.Success) {
                return fd.value.toInt()
            }
            return -(fd as LinuxNative.SyscallResult.Error).errno
        }
        val tgid = getTgid(tid.value)
        val pidfdRes = engine.process.pidfdOpen(tgid, 0)
        val pidfdVal = when (pidfdRes) {
            is LinuxNative.SyscallResult.Success -> pidfdRes.value.toInt()
            is LinuxNative.SyscallResult.Error -> return -pidfdRes.errno
        }
        return SafeLocalFd(pidfdVal).use { pidfdSafe ->
            val pidfd = FileDescriptor.pid(pidfdSafe.fd)
            val importedRes = engine.process.pidfdGetFd(pidfd, traceeDirfd.value, 0)
            when (importedRes) {
                is LinuxNative.SyscallResult.Success -> importedRes.value.toInt()
                is LinuxNative.SyscallResult.Error -> -importedRes.errno
            }
        }
    }

    private fun signedErrno(res: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>): Int {
        return when (res) {
            is LinuxNative.SyscallResult.Success -> res.value.toInt()
            is LinuxNative.SyscallResult.Error -> -res.errno
        }
    }

    context(arena: NativeArena) internal fun connectSocketInSupervisor(sockaddrBytes: ByteArray): Int {
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
        val socketFd = FileDescriptor.adopt(socketRes, FileDescriptorRole.UnixSocket)

        val addr = arena.allocate(sockaddrBytes.size.toLong())
        ManagedSegment.copy(sockaddrBytes, 0, addr, 0L, sockaddrBytes.size)

        val connectErr = {
            val res = engine.networking.connect(
                socketFd,
                addr,
                sockaddrBytes.size,
            )
            when (res) {
                is LinuxNative.SyscallResult.Success -> 0
                is LinuxNative.SyscallResult.Error -> res.errno
            }
        }()
        if (connectErr != 0) {
            closeLocalFd(socketFd)
            return -connectErr
        }
        return socketRes
    }

    private fun closeLocalFd(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
        if (fd.isLiveForIo()) {
            engine.fileSystem.close(fd)
        }
    }

    private inner class SafeLocalFd(
        val handle: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    ) : AutoCloseable {
        constructor(fd: Int) : this(FileDescriptor.adopt(fd, FileDescriptorRole.Generic))

        val fd: Int get() = handle.value

        override fun close() {
            if (handle.isLiveForIo()) {
                engine.fileSystem.close(handle)
            }
        }
    }

    private val responseWriter by lazy {
        SupervisorResponseWriter(engine, listenerFd)
    }

    private fun sendSeccompContinue(
        id: Long,
        resp: ManagedSegment,
    ) = responseWriter.sendContinue(id, resp)

    private fun sendSeccompError(
        id: Long,
        errorNr: Int,
        resp: ManagedSegment,
    ) = responseWriter.sendError(id, errorNr, resp)

    context(arena: NativeArena) private fun readStringFromProcess(
        tid: Tid,
        remoteAddr: Long,
    ): String? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader
            .readString(tid, remoteAddr, MAX_PATH_LEN)
    }

    context(arena: NativeArena) private fun readExecPath(
        tid: Tid,
        remoteAddr: Long,
    ): String? {
        return try {
            readStringFromProcess(tid, remoteAddr)
        } catch (e: ContainmentViolationException) {
            logger.warning("[SUPERVISOR-DIAGNOSTIC] Cannot inspect exec path for tid=${tid.value}: ${e.message}")
            null
        }
    }

    context(arena: NativeArena) private fun readBytesFromProcess(
        tid: Tid,
        remoteAddr: Long,
        len: Int,
    ): ByteArray? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader
            .readBytes(tid, remoteAddr, len)
    }

    /**
     * Resolves a tracee tid to its thread-group leader pid via /proc. Injectable
     * so tests can supply synthetic tids without touching the live procfs (a
     * tid like 1234 may be a real unrelated process on busy CI runners).
     */
    internal var tgidResolver: (Int) -> Int = ProcFsInspector::getTgid

    private fun getTgid(tid: Int): Int = tgidResolver(tid)

    private fun closeTraceeFd(
        tid: Tid,
        traceeFd: Int,
    ) {
        if (traceeFd < 0) return
        val tgid = getTgid(tid.value)
        val pidfdRes = engine.process.pidfdOpen(tgid, 0)
        val pidfdVal = when (pidfdRes) {
            is LinuxNative.SyscallResult.Success -> pidfdRes.value.toInt()
            is LinuxNative.SyscallResult.Error -> {
                logger.warning { "[SUPERVISOR-DIAGNOSTIC] pidfd_open failed to close tracee fd $traceeFd for tgid=$tgid with errno ${pidfdRes.errno}" }
                return
            }
        }
        SafeLocalFd(pidfdVal).use { pidfdSafe ->
            val pidfd = FileDescriptor.adopt(pidfdSafe.fd, FileDescriptorRole.Pid)
            val dupRes = engine.process.pidfdGetFd(pidfd, traceeFd, 0)
            val dupFd = when (dupRes) {
                is LinuxNative.SyscallResult.Success -> dupRes.value.toInt()
                is LinuxNative.SyscallResult.Error -> {
                    logger.warning { "[SUPERVISOR-DIAGNOSTIC] pidfd_getfd failed to close tracee fd $traceeFd for tgid=$tgid with errno ${dupRes.errno}" }
                    return@use
                }
            }
            SafeLocalFd(dupFd).use { dupFdSafe ->
                engine.fileSystem.close(dupFdSafe.handle)
            }
        }
    }

    private fun getPpid(pid: Int): Int = ProcFsInspector.getPpid(pid)

    internal fun handleAcceptAsync(
        id: Long,
        nr: Int,
        args: LongArray,
        tid: Tid,
        traceeArch: io.mazewall.core.Arch,
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
                                        dupFdSafe.handle,
                                        localAddr,
                                        localAddrLen,
                                        flags,
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
                                        val traceeAddrLenBytes = io.mazewall.ffi.memory.SupervisorProcessMemoryReader
                                            .readBytes(tid, traceeAddrLenPtr, 4)
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
                                            io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
                                                .writeBytes(tid, traceeAddrPtr, addrBytes)
                                        }

                                        val lenBytes = byteArrayOf(
                                            (actualLen and 0xFF).toByte(),
                                            ((actualLen shr 8) and 0xFF).toByte(),
                                            ((actualLen shr 16) and 0xFF).toByte(),
                                            ((actualLen shr 24) and 0xFF).toByte(),
                                        )
                                        io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
                                            .writeBytes(tid, traceeAddrLenPtr, lenBytes)
                                    }

                                    val acceptFlags = if (nr == traceeArch.accept4) args[3].toInt() else 0
                                    if (!injectFd(id, clientFdSafe.handle, NewFdFlags.forAccept(acceptFlags))) {
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
                } catch (ignored: Exception) {
                    }
            }
        }.apply {
            isDaemon = true
            name = "supervisor-accept-worker-$id"
            start()
        }
    }
}
