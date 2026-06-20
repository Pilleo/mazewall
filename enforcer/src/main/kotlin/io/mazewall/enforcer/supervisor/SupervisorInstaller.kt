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
            ) { socketFd ->
                val listener = JVMValidationListener(
                    FileDescriptor.unsafe(socketFd),
                    scopingPolicy
                )
                listener.start()
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

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
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

                // Read request arguments as raw data structures to avoid classloading.
                // We use primitive arrays here:
                val argTypes = ByteArray(argCount)
                val argValues = arrayOfNulls<Any>(argCount)
                for (i in 0 until argCount) {
                    val type = dis.readByte()
                    argTypes[i] = type
                    when (type.toInt()) {
                        0 -> { // Long
                            argValues[i] = dis.readLong()
                        }
                        1, 2 -> { // String raw bytes or SockAddr bytes
                            val len = dis.readInt()
                            val bytes = ByteArray(len)
                            dis.readFully(bytes)
                            argValues[i] = bytes
                        }
                    }
                }

                // Perform Stacktrace Scoping validation.
                //
                // ORDERING IS CRITICAL: obtain the raw stack array and run the classloader check
                // BEFORE any classloading (no string decoding, no custom classes, no Kotlin lists/lambdas).
                //
                // Fast-path: if the tracee thread is currently executing inside a classloader
                // operation it holds the JVM ClassLoader monitor. Immediately allow the syscall
                // so the tracee can finish class loading and release the lock.
                val targetThread = SupervisorInstaller.threadRegistry[Tid(pidVal)]
                val stackTrace = targetThread?.stackTrace ?: emptyArray()
                val isLoaderActive = JvmStackInspector.isClassloaderActive(stackTrace)

                if (isLoaderActive) {
                    val decision: Byte = if (nr == SYS_OPEN || nr == SYS_CONNECT || nr == SYS_OPENAT || nr == SYS_OPENAT2) {
                        2.toByte()
                    } else {
                        1.toByte()
                    }
                    sendResponse(id, decision, 0)
                    continue
                }

                // If classloader is NOT active, it is 100% safe to perform classloading, decode strings,
                // allocate collections, and run the policy.
                val argsList = java.util.ArrayList<Any>(argCount)
                for (i in 0 until argCount) {
                    val type = argTypes[i]
                    val value = argValues[i] ?: continue
                    when (type.toInt()) {
                        0 -> argsList.add(value as Long)
                        1 -> {
                            val bytes = value as ByteArray
                            argsList.add(String(bytes, StandardCharsets.UTF_8))
                        }
                        2 -> argsList.add(value as ByteArray)
                    }
                }

                val stackTraceList = stackTrace.toList()
                val syscall = Syscall.entries.find { it.numberFor(Arch.current()) == nr } ?: Syscall.OPEN
                val isAllowed = scopingPolicy.authorize(Tid(pidVal), syscall, argsList, stackTraceList)

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
