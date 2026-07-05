package io.mazewall.enforcer.supervisor

import io.mazewall.BpfFilter
import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.Arch
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeArg
import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.getFdOrThrow
import io.mazewall.onFailure
import io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

public object SupervisorInstaller {
    private val logger = Logger.getLogger(SupervisorInstaller::class.java.name)
    internal val threadRegistry = ConcurrentHashMap<Tid, Thread>()

    public fun registerThread(tid: Tid) {
        threadRegistry[tid] = Thread.currentThread()
    }

    public fun unregisterThread(tid: Tid) {
        threadRegistry.remove(tid)
    }

    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    public fun installSupervisedFilterForThread(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy
    ): SupervisorSession {
        val context = SupervisorDaemonManager.getOrSpawnSharedDaemon()
        val arch = Arch.current()

        // Assert not a virtual thread per Loom carrier poisoning rules
        if (Thread.currentThread().isVirtual) {
            throw IllegalStateException("Cannot install seccomp filter directly on a virtual thread.")
        }

        val filter = BpfFilter.build(arch, policy)
        val tid = LinuxNative.process.gettid()
        registerThread(tid)
        try {
            SupervisorSeccompNotifInstaller.install(
                socketPath = context.socketPath,
                filterInstructions = filter,
                processWide = false
            ) { socketFd, readyLatch ->
                val listener = JVMValidationListener(
                    FileDescriptor.unsafe(socketFd),
                    scopingPolicy
                )
                listener.start(readyLatch)
            }
            return SupervisorSession(tid)
        } catch (t: Throwable) {
            unregisterThread(tid)
            throw t
        }
    }
}

internal class JVMValidationListener(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    private val scopingPolicy: StacktraceScopingPolicy
) {
    private val closed = AtomicBoolean(false)
    private val logger = Logger.getLogger(JVMValidationListener::class.java.name)

    companion object {
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
        private const val SYS_OPEN = 2
        private const val SYS_CONNECT = 42
        private const val SYS_ACCEPT = 43
        private const val SYS_OPENAT = 257
        private const val SYS_ACCEPT4 = 288
        private const val SYS_OPENAT2 = 437

        private const val SLOW_INSPECT_THRESHOLD_MS = 500L
        private const val SLOW_AUTH_THRESHOLD_MS = 500L
        private const val SLOW_TOTAL_THRESHOLD_MS = 1000L
    }

    fun start(readyLatch: CountDownLatch) {
        val arena = Arena.ofShared()
        val inputStream = SupervisorSocketInputStream(socketFd, arena)

        Thread {
            runValidationReactor(inputStream, arena, readyLatch)
        }.apply {
            isDaemon = true
            name = "supervisor-validation-listener-${socketFd.value}"
            start()
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun runValidationReactor(inputStream: SupervisorSocketInputStream, arena: Arena, readyLatch: CountDownLatch) {
        System.err.println("[JVM-VALIDATION] validation reactor thread started")
        try {
            val dis = DataInputStream(BufferedInputStream(inputStream))
            try {
                // Read handshake ACK
                System.err.println("[JVM-VALIDATION] reading handshake ACK...")
                val ack = dis.readByte()
                System.err.println("[JVM-VALIDATION] handshake ACK received: $ack")
                if (ack != PROTOCOL_ACK_BYTE) {
                    logger.warning("Invalid handshake ACK: $ack")
                }
            } finally {
                readyLatch.countDown()
            }

            val responseSegment = with(arena) { io.mazewall.ffi.memory.SupervisorResponseSegment.allocate() }

            val arch = Arch.current()
            val forkNr = arch.fork
            val vforkNr = arch.vfork
            val cloneNr = arch.clone

            while (!closed.get()) {
                val id = try {
                    dis.readLong()
                } catch (ignored: java.io.EOFException) {
                    break
                }
                val pidVal = dis.readInt()
                val ppidVal = dis.readInt()
                val nr = dis.readInt()
                val argCount = dis.readInt()

                val argsList = readRequestArgs(dis, argCount)
                System.err.println("[JVM-VALIDATION] Received request: id=$id, pid=$pidVal, ppid=$ppidVal, nr=$nr, args=$argsList")

                val startMs = System.currentTimeMillis()
                val targetThread = SupervisorInstaller.threadRegistry[Tid(pidVal)]
                var validationState = JvmStackInspector.inspect(nr, argsList, targetThread)

                // --- STACKTRACE PROPAGATION LOGIC ---
                if (nr == arch.execve || nr == arch.execveat) {
                    if (validationState is ScopingValidationState.SafeToValidate && validationState.rawStack.isEmpty()) {
                        // Empty stack trace on execve suggests a child process.
                        // We resolve the propagated stack trace from the parent thread.
                        val parentStack = PendingSpawnRegistry.get(Tid(ppidVal))
                        if (parentStack != null) {
                            System.err.println("[JVM-VALIDATION] Propagating parent stack trace (TID=$ppidVal) to child process (PID=$pidVal)")
                            validationState = ScopingValidationState.SafeToValidate.create(parentStack.toTypedArray(), nr, argsList)
                        }
                    }
                }

                val inspectMs = System.currentTimeMillis() - startMs
                if (inspectMs > SLOW_INSPECT_THRESHOLD_MS) {
                    logger.warning("[SUPERVISOR-DIAGNOSTIC] JvmStackInspector.inspect took ${inspectMs}ms for syscall nr=$nr, args=$argsList")
                }

                val isAllowed = when (validationState) {
                    is ScopingValidationState.SafeToValidate -> {
                        val stackTrace = validationState.rawStack.toList()

                        // --- SPAWN REGISTRATION LOGIC ---
                        if (nr == forkNr || nr == vforkNr || nr == cloneNr) {
                            if (stackTrace.isNotEmpty()) {
                                System.err.println("[JVM-VALIDATION] Registering pending spawn for TID=$pidVal")
                                PendingSpawnRegistry.register(Tid(pidVal), stackTrace)
                            }
                        }

                        val sb = StringBuilder()
                        sb.append("Validation stack for nr=${validationState.nr}, targetThread=$targetThread:\n")
                        if (stackTrace.isEmpty()) {
                            sb.append("  (empty stack trace)\n")
                        } else {
                            stackTrace.forEach { sb.append("  $it\n") }
                        }
                        ValidationLog.logs.add(sb.toString())
                        val syscall = Syscall.entries.find { it.numberFor(Arch.current()) == validationState.nr } ?: Syscall.OPEN
                        val authStartMs = System.currentTimeMillis()
                        val handler = scopingPolicy.handlers[syscall]
                        val res = handler?.invoke(Tid(pidVal), validationState.argsList, stackTrace) ?: true
                        val authMs = System.currentTimeMillis() - authStartMs
                        if (authMs > SLOW_AUTH_THRESHOLD_MS) {
                            logger.warning("[SUPERVISOR-DIAGNOSTIC] scopingPolicy.authorize took ${authMs}ms for syscall $syscall")
                        }
                        res
                    }
                }

                val totalMs = System.currentTimeMillis() - startMs
                if (totalMs > SLOW_TOTAL_THRESHOLD_MS) {
                    logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM Validation total processing took ${totalMs}ms for syscall nr=$nr")
                }

                // Decision encoding: 0 = Deny, 1 = Allow Continue, 2 = Allow & Inject FD
                val decision: Byte = if (isAllowed) {
                    if (nr == SYS_OPEN || nr == SYS_CONNECT || nr == SYS_OPENAT || nr == SYS_OPENAT2 || nr == SYS_ACCEPT || nr == SYS_ACCEPT4) {
                        2.toByte()
                    } else {
                        1.toByte()
                    }
                } else {
                    0.toByte()
                }

                if (isAllowed && (nr == arch.execve || nr == arch.execveat)) {
                    PendingSpawnRegistry.remove(Tid(ppidVal))
                }

                val errorNr = if (decision.toInt() == 0) NativeConstants.EPERM else 0
                sendResponse(id, decision, errorNr, responseSegment)
            }
        } catch (ignored: java.io.IOException) {
            // Done
        } catch (ignored: IllegalStateException) {
            // Done
        } finally {
            arena.close()
            inputStream.close()
        }
    }

    private fun readRequestArgs(dis: DataInputStream, argCount: Int): List<Any> {
        val argsList = java.util.ArrayList<Any>(argCount)
        for (i in 0 until argCount) {
            val type = dis.readByte()
            when (type.toInt()) {
                0 -> { // Long
                    argsList.add(dis.readLong())
                }
                1 -> { // String
                    val len = dis.readInt()
                    val bytes = ByteArray(len)
                    dis.readFully(bytes)
                    argsList.add(String(bytes, StandardCharsets.UTF_8))
                }
                2 -> { // SockAddr bytes
                    val len = dis.readInt()
                    val bytes = ByteArray(len)
                    dis.readFully(bytes)
                    argsList.add(bytes)
                }
            }
        }
        return argsList
    }

    private fun sendResponse(id: Long, decision: Byte, errorNr: Int, resp: io.mazewall.ffi.memory.SupervisorResponseSegment) {
        resp.setId(id)
        resp.setDecision(decision)
        resp.setErrorNr(errorNr)
        LinuxNative.withTransaction {
            LinuxNative.memory.write(socketFd, resp.segment, Layouts.SUPERVISOR_RESPONSE_SIZE)
        }
    }
}

public object ValidationLog {
    public val logs: java.util.concurrent.ConcurrentLinkedQueue<String> = java.util.concurrent.ConcurrentLinkedQueue<String>()
}
