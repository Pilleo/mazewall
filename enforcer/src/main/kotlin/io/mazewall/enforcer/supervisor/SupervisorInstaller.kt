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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

public object SupervisorInstaller {
    private val logger = Logger.getLogger(SupervisorInstaller::class.java.name)
    internal val threadRegistry = ConcurrentHashMap<Tid, Thread>()

    @Suppress("LongParameterList")
    public fun installSupervisedFilterForThread(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy
    ) {
        val context = SupervisorDaemonManager.getOrSpawnSharedDaemon()
        val arch = Arch.current()

        // Assert not a virtual thread per Loom carrier poisoning rules
        if (Thread.currentThread().isVirtual) {
            throw IllegalStateException("Cannot install seccomp filter directly on a virtual thread.")
        }

        val filter = BpfFilter.build(arch, policy)
        val tid = LinuxNative.process.gettid()
        threadRegistry[tid] = Thread.currentThread()

        try {
            SupervisorSeccompNotifInstaller.install(
                socketPath = context.socketPath,
                filterInstructions = filter,
                processWide = false
            ) { socketFd, _ ->
                val listener = JVMValidationListener(
                    FileDescriptor.unsafe(socketFd),
                    scopingPolicy
                )
                listener.start()
            }
        } finally {
            threadRegistry.remove(tid)
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
        private const val SYS_OPENAT = 257
        private const val SYS_OPENAT2 = 437
    }

    fun start() {
        val arena = Arena.ofShared()
        val inputStream = SupervisorSocketInputStream(socketFd, arena)

        Thread {
            runValidationReactor(inputStream, arena)
        }.apply {
            isDaemon = true
            name = "supervisor-validation-listener-${socketFd.value}"
            start()
        }
    }

    private fun runValidationReactor(inputStream: SupervisorSocketInputStream, arena: Arena) {
        try {
            val dis = DataInputStream(BufferedInputStream(inputStream))
            // Read handshake ACK
            val ack = dis.readByte()
            if (ack != PROTOCOL_ACK_BYTE) {
                logger.warning("Invalid handshake ACK: $ack")
            }

            while (!closed.get()) {
                val id = try {
                    dis.readLong()
                } catch (ignored: java.io.EOFException) {
                    break
                }
                val pidVal = dis.readInt()
                val nr = dis.readInt()
                val argCount = dis.readInt()

                val argsList = readRequestArgs(dis, argCount)

                // Perform Stacktrace Scoping validation
                val targetThread = SupervisorInstaller.threadRegistry[Tid(pidVal)]
                val stackTrace = targetThread?.stackTrace?.toList() ?: emptyList()

                val syscall = Syscall.entries.find { it.numberFor(Arch.current()) == nr } ?: Syscall.OPEN
                val isAllowed = scopingPolicy.authorize(Tid(pidVal), syscall, argsList, stackTrace)

                // Decision encoding: 0 = Deny, 1 = Allow Continue, 2 = Allow & Inject FD
                val decision: Byte = if (isAllowed) {
                    if (nr == SYS_OPEN || nr == SYS_CONNECT || nr == SYS_OPENAT || nr == SYS_OPENAT2) {
                        2.toByte()
                    } else {
                        1.toByte()
                    }
                } else {
                    0.toByte()
                }

                val errorNr = if (decision.toInt() == 0) NativeConstants.EPERM else 0
                sendResponse(id, decision, errorNr)
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
        val argsList = mutableListOf<Any>()
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

    private fun sendResponse(id: Long, decision: Byte, errorNr: Int) {
        Arena.ofConfined().use { responseArena ->
            val resp = with(responseArena) { io.mazewall.ffi.memory.SupervisorResponseSegment.allocate() }
            resp.setId(id)
            resp.setDecision(decision)
            resp.setErrorNr(errorNr)
            LinuxNative.withTransaction {
                LinuxNative.memory.write(socketFd, resp.segment, Layouts.SUPERVISOR_RESPONSE_SIZE)
            }
        }
    }
}
