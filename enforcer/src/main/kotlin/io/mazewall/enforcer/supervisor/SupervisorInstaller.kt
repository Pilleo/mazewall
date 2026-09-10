package io.mazewall.enforcer.supervisor

import io.mazewall.BpfFilter
import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.Arch
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeArg
import io.mazewall.core.Pid
import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.diagnostics.validateNotVirtual
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorProcessMemoryReader
import io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
import io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller
import io.mazewall.ffi.networking.SupervisorValidationChannel
import io.mazewall.getFdOrThrow
import io.mazewall.onFailure
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

public object SupervisorInstaller {
    private val logger = Logger.getLogger(SupervisorInstaller::class.java.name)
    private val installations = ThreadLocal.withInitial { mutableListOf<SupervisorInstallationLifetime>() }

    internal fun requireReusableInstallations() {
        installations.get().forEach { it.requireReusable() }
    }

    @Suppress("TooGenericExceptionCaught") // rollback releases acquired leases, then preserves the original failure
    internal fun acquireTaskSession(scopingPolicy: StacktraceScopingPolicy): SupervisorSession {
        val tid = LinuxNative.process.gettid()
        val owners = installations.get()
        owners.forEach { it.requireReusable() }
        check(owners.all { it.scopingPolicy === scopingPolicy }) { "Cannot replace the scoping policy of a permanent supervised filter; use a new platform thread" }
        val runtimes = owners.map { it.runtime }
        check(runtimes.isNotEmpty()) { "Supervised filter has no installation runtime on this thread" }
        val acquired = mutableListOf<SupervisorRuntimeContext>()
        try {
            runtimes.forEach {
                it.register(tid)
                acquired.add(it)
            }
        } catch (failure: Throwable) {
            acquired.forEach { it.unregister(tid) }
            throw failure
        }
        return SupervisorSession { acquired.forEach { it.unregister(tid) } }
    }

    public fun installSupervisedFilterForThread(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
        onFilterApplied: () -> Unit = {},
    ): SupervisorSession {
        requireReusableInstallations()
        ValidationListenerPreload.ensureLoaded()
        val context = SupervisorDaemonManager.getInstance().getOrSpawnSharedDaemon()
        val arch = Arch.current()

        // Assert not a virtual thread per Loom carrier poisoning rules
        validateNotVirtual()

        val filter = BpfFilter.build(arch, policy)
        val tid = LinuxNative.process.gettid()
        val ownedInstallations = installations.get()
        val runtime = SupervisorRuntimeContext()
        val lifetime = SupervisorInstallationLifetime(Thread.currentThread(), runtime, scopingPolicy)
        var previousLease: SupervisorSession? = null
        val session = SupervisorSession {
            runtime.unregister(tid)
            previousLease?.close()
        }
        var applied = false
        try {
            previousLease = if (ownedInstallations.isEmpty()) null else acquireTaskSession(scopingPolicy)
            lifetime.start()
            runtime.register(tid)
            SupervisorSeccompNotifInstaller.install(
                socketPath = context.socketPath,
                filter = filter,
                processWide = false,
                onFilterApplied = {
                    applied = true
                    onFilterApplied()
                },
            ) { socketFd, readyLatch ->
                val listener = JVMValidationListener(
                    FileDescriptor.adopt(socketFd, FileDescriptorRole.UnixSocket),
                    scopingPolicy,
                    runtime,
                    onUnexpectedTermination = { cause -> lifetime.fail(cause) },
                )
                lifetime.attach(AutoCloseable { listener.requestStop() })
                listener.start(readyLatch)
            }
            ownedInstallations.add(lifetime)
            return session
        } catch (expectedInstallFailure: Exception) {
            if (applied) {
                ownedInstallations.add(lifetime)
                lifetime.fail(expectedInstallFailure)
            } else {
                lifetime.close()
            }
            session.close()
            throw expectedInstallFailure
        } catch (expectedInstallError: Error) {
            if (applied) {
                ownedInstallations.add(lifetime)
                lifetime.fail(expectedInstallError)
            } else {
                lifetime.close()
            }
            session.close()
            throw expectedInstallError
        }
    }
}

internal class JVMValidationListener(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val scopingPolicy: StacktraceScopingPolicy,
    private val runtime: SupervisorRuntimeContext = SupervisorRuntimeContext(),
    private val onUnexpectedTermination: (Throwable) -> Unit = {},
) {
    private val closed = AtomicBoolean(false)
    private val activeChannel = AtomicReference<SupervisorValidationChannel?>()
    private val logger = Logger.getLogger(JVMValidationListener::class.java.name)

    companion object {
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()

        private const val SLOW_INSPECT_THRESHOLD_MS = 500L
        private const val SLOW_AUTH_THRESHOLD_MS = 500L
        private const val SLOW_TOTAL_THRESHOLD_MS = 1000L
    }

    fun start(readyLatch: CountDownLatch) {
        val channel = SupervisorValidationChannel(socketFd)
        activeChannel.set(channel)
        if (closed.get()) channel.requestStop()

        Thread {
            runValidationReactor(channel, readyLatch)
        }.apply {
            isDaemon = true
            name = "supervisor-validation-listener-${socketFd.value}"
            start()
        }
    }

    fun requestStop() {
        closed.set(true)
        activeChannel.get()?.requestStop()
    }

    private fun runValidationReactor(
        channel: SupervisorValidationChannel,
        readyLatch: CountDownLatch,
    ) {
        System.err.println("[JVM-VALIDATION] validation reactor thread started")
        var failure: Throwable? = null
        try {
            val dis = DataInputStream(BufferedInputStream(channel.inputStream))
            if (!acceptHandshake(dis, readyLatch)) return

            while (!closed.get()) {
                val id = try {
                    dis.readLong()
                } catch (ignored: java.io.EOFException) {
                    break
                }
                val pidVal = dis.readInt()
                val archVal = dis.readInt()
                val ppidVal = dis.readInt()
                val nr = dis.readInt()
                val argCount = dis.readInt()

                val argsList = when (val decoded = SupervisorValidationDecoder.arguments(dis, argCount)) {
                    is SupervisorValidationDecodeResult.Arguments -> decoded.values
                    is SupervisorValidationDecodeResult.ProtocolViolation -> {
                        logger.warning(decoded.reason)
                        break
                    }
                    SupervisorValidationDecodeResult.HandshakeAccepted -> error("argument decoder accepted a handshake")
                }
                System.err.println("[JVM-VALIDATION] Received request: id=$id, pid=$pidVal, arch=$archVal, ppid=$ppidVal, nr=$nr, args=$argsList")

                val traceeArch = Arch.fromAudit(archVal)
                val syscallResolution = SupervisorSyscallResolver.resolve(nr, traceeArch)

                val startMs = System.currentTimeMillis()
                val targetThread = runtime.resolve(Tid(pidVal))
                val inspection = inspectWithSpawnAuthorization(nr, argsList, targetThread, ppidVal, traceeArch)

                val inspectMs = System.currentTimeMillis() - startMs
                if (inspectMs > SLOW_INSPECT_THRESHOLD_MS) {
                    logger.warning("[SUPERVISOR-DIAGNOSTIC] JvmStackInspector.inspect took ${inspectMs}ms for syscall nr=$nr, args=$argsList")
                }

                val isAllowed = inspection.spawnContextAccepted &&
                    authorizeValidation(inspection.state, syscallResolution, pidVal, nr, traceeArch, targetThread)

                val totalMs = System.currentTimeMillis() - startMs
                if (totalMs > SLOW_TOTAL_THRESHOLD_MS) {
                    logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM Validation total processing took ${totalMs}ms for syscall nr=$nr")
                }

                sendValidationResponse(dis, channel, id, nr, argsList, pidVal, traceeArch, syscallResolution, isAllowed)
            }
        } catch (expectedStop: java.io.IOException) {
            if (!closed.get()) failure = expectedStop
        } catch (expectedStop: IllegalStateException) {
            if (!closed.get()) failure = expectedStop
        } finally {
            runtime.close()
            channel.close()
            if (!closed.get()) {
                onUnexpectedTermination(failure ?: IllegalStateException("validation reactor exited unexpectedly"))
            }
        }
    }

    private fun acceptHandshake(
        dis: DataInputStream,
        readyLatch: CountDownLatch,
    ): Boolean {
        try {
            // Read handshake ACK
            System.err.println("[JVM-VALIDATION] reading handshake ACK...")
            val ack = dis.readUnsignedByte()
            System.err.println("[JVM-VALIDATION] handshake ACK received: $ack")
            when (val handshake = SupervisorValidationDecoder.handshake(ack)) {
                SupervisorValidationDecodeResult.HandshakeAccepted -> Unit
                is SupervisorValidationDecodeResult.ProtocolViolation -> {
                    logger.warning(handshake.reason)
                    return false
                }
                is SupervisorValidationDecodeResult.Arguments -> error("handshake decoder returned arguments")
            }
        } finally {
            readyLatch.countDown()
        }
        return true
    }

    private data class ValidationInspection(
        val state: ScopingValidationState,
        val spawnContextAccepted: Boolean,
    )

    private fun inspectWithSpawnAuthorization(
        nr: Int,
        argsList: List<Any>,
        targetThread: Thread?,
        ppidVal: Int,
        traceeArch: Arch,
    ): ValidationInspection {
        var validationState = JvmStackInspector.inspect(nr, argsList, targetThread)
        var spawnContextAccepted = true

        // --- STACKTRACE PROPAGATION LOGIC ---
        if (nr == traceeArch.execve || nr == traceeArch.execveat) {
            if (validationState is ScopingValidationState.SafeToValidate && validationState.rawStack.isEmpty()) {
                // Empty stack trace on execve suggests a child process.
                // Consume this session's authorization for the parent's exact process lifetime.
                when (val authorization = runtime.consumeSpawn(Pid(ppidVal))) {
                    is SpawnAuthorizationResult.Authorized -> {
                        validationState = ScopingValidationState.SafeToValidate.create(authorization.stack.toTypedArray(), nr, argsList)
                    }
                    is SpawnAuthorizationResult.Rejected -> {
                        logger.warning("Child exec authorization rejected for parent pid=$ppidVal: ${authorization.reason}")
                        spawnContextAccepted = false
                    }
                }
            }
        }
        return ValidationInspection(validationState, spawnContextAccepted)
    }

    private fun authorizeValidation(
        validationState: ScopingValidationState,
        syscallResolution: SupervisorSyscallResolution,
        pidVal: Int,
        nr: Int,
        traceeArch: Arch,
        targetThread: Thread?,
    ): Boolean =
        when (validationState) {
            is ScopingValidationState.SafeToValidate -> {
                val stackTrace = validationState.rawStack.toList()

                val sb = StringBuilder()
                sb.append("Validation stack for nr=${validationState.nr}, targetThread=$targetThread:\n")
                if (stackTrace.isEmpty()) {
                    sb.append("  (empty stack trace)\n")
                } else {
                    stackTrace.forEach { sb.append("  $it\n") }
                }
                ValidationLog.logs.add(sb.toString())
                when (syscallResolution) {
                    is SupervisorSyscallResolution.Known -> {
                        val authStartMs = System.currentTimeMillis()
                        val handler = scopingPolicy.handlers[syscallResolution.syscall]
                        val res = handler?.invoke(Tid(pidVal), validationState.argsList, stackTrace) ?: true
                        val authMs = System.currentTimeMillis() - authStartMs
                        if (authMs > SLOW_AUTH_THRESHOLD_MS) {
                            logger.warning("[SUPERVISOR-DIAGNOSTIC] scopingPolicy.authorize took ${authMs}ms for syscall ${syscallResolution.syscall}")
                        }
                        if (res && (nr == traceeArch.fork || nr == traceeArch.vfork || nr == traceeArch.clone)) {
                            val tgid = getTgid(pidVal)
                            stackTrace.isNotEmpty() &&
                                tgid != null &&
                                runtime.registerSpawn(Pid(tgid), stackTrace) == SpawnRegistrationResult.REGISTERED
                        } else {
                            res
                        }
                    }
                    is SupervisorSyscallResolution.Unknown -> {
                        logger.warning(
                            "[SUPERVISOR-DIAGNOSTIC] Refusing unmapped syscall " +
                                "nr=${syscallResolution.number.nr} arch=${syscallResolution.arch}",
                        )
                        false
                    }
                }
            }
        }

    private fun sendValidationResponse(
        dis: DataInputStream,
        channel: SupervisorValidationChannel,
        id: Long,
        nr: Int,
        argsList: List<Any>,
        pidVal: Int,
        traceeArch: Arch,
        syscallResolution: SupervisorSyscallResolution,
        isAllowed: Boolean,
    ) {
        val kind = io.mazewall.platform.seccomp.SupervisedKind
            .classify(io.mazewall.core.SyscallNumber(nr), traceeArch)
        val verdict = if (syscallResolution is SupervisorSyscallResolution.Unknown || !isAllowed) {
            JvmVerdict.Deny(NativeConstants.EPERM)
        } else if (kind is io.mazewall.platform.seccomp.SupervisedKind.Accept) {
            JvmVerdict.InjectFd
        } else {
            JvmVerdict.Allow
        }

        val execPath = if (isAllowed && kind is io.mazewall.platform.seccomp.SupervisedKind.Exec) {
            resolveExecTargetPath(nr, argsList, pidVal, traceeArch)
        } else {
            null
        }
        if (execPath != null) {
            System.err.println("[JVM-VALIDATION] sending exec path to daemon: $execPath")
        }
        channel.sendResponse(id, verdict, execPath)
        if (isAllowed && (nr == traceeArch.execve || nr == traceeArch.execveat)) {
            completeParentExecRewrite(dis, channel)
        }
    }

    private fun completeParentExecRewrite(
        dis: DataInputStream,
        channel: SupervisorValidationChannel,
    ) {
        try {
            dis.readLong()
            dis.readLong()
            dis.readLong()
            dis.readLong()
            dis.readLong()
            dis.readLong()
            // Do not ptrace a task blocked in USER_NOTIF (deadlocks). CONTINUE uses the
            // original execve pathname; the parent-supplied path was opened and ADDFD'd
            // for identity. Register rewrite is not yet implemented (issue-20260817-033800),
            // so always deny exec rewrite requests.
            channel.sendExecRewriteAck(false)
        } catch (expectedRewriteFailure: Exception) {
            logger.warning("parent exec register rewrite failed: ${expectedRewriteFailure.message}")
            try {
                channel.sendExecRewriteAck(false)
            } catch (expectedDenyAckTransportFailure: Exception) {
            }
        }
    }

    private fun resolveExecTargetPath(
        nr: Int,
        argsList: List<Any>,
        pidVal: Int,
        traceeArch: Arch,
    ): String? {
        val fromDaemon = argsList.filterIsInstance<String>().firstOrNull()
        if (!fromDaemon.isNullOrEmpty() && !fromDaemon.startsWith("<YAMA_ERROR")) {
            return canonicalizeExecPath(fromDaemon)
        }
        val pathPtr = when {
            nr == traceeArch.execve && argsList.isNotEmpty() && argsList[0] is Long -> argsList[0] as Long
            nr == traceeArch.execveat && argsList.size > 1 && argsList[1] is Long -> argsList[1] as Long
            else -> return null
        }
        if (pathPtr == 0L) {
            return null
        }
        val raw = try {
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val path = SupervisorProcessMemoryReader.readString(Tid(pidVal), pathPtr, 4096)
                    path
                }
            }
        } catch (expectedTraceeReadFailure: Exception) {
            logger.warning("JVM could not read exec path from pid=$pidVal: ${expectedTraceeReadFailure.message}")
            null
        }
        return raw?.let { canonicalizeExecPath(it) }
    }

    private fun canonicalizeExecPath(path: String): String? {
        if (path.startsWith("/proc/self/fd/") || path.startsWith("/proc/thread-self/fd/")) {
            return null
        }
        if (path.startsWith("/")) {
            return path
        }
        val search = ArrayList<String>()
        System
            .getenv("PATH")
            ?.split(':')
            ?.filter { it.isNotEmpty() }
            ?.let { search.addAll(it) }
        search.add("/usr/bin")
        search.add("/bin")
        for (dir in search) {
            val candidate = java.io.File(dir, path)
            if (candidate.isFile) {
                return candidate.absolutePath
            }
        }
        return path
    }

    private fun getTgid(tid: Int): Int? =
        try {
            java.io.File("/proc/$tid/status").useLines { lines ->
                lines
                    .firstOrNull { it.startsWith("Tgid:") }
                    ?.substringAfter("Tgid:")
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}

public object ValidationLog {
    public val logs: java.util.concurrent.ConcurrentLinkedQueue<String> = java.util.concurrent.ConcurrentLinkedQueue<String>()
}
