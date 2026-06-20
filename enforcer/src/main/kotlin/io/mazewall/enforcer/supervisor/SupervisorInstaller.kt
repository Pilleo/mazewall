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

    private const val CMSG_RIGHTS_LEN = 20L
    private const val MSG_CONTROL_BUF_SIZE = 24L
    private const val SOL_SOCKET = 1
    private const val SCM_RIGHTS = 1

    @Suppress("LongParameterList")
    public fun installSupervisedFilterForThread(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy
    ) {
        val context = SupervisorDaemonManager.getOrSpawnSharedDaemon()
        val socketFd = connectWithRetry(context.socketPath)
        val arch = Arch.current()

        // Assert not a virtual thread per Loom carrier poisoning rules
        if (Thread.currentThread().isVirtual) {
            throw IllegalStateException("Cannot install seccomp filter directly on a virtual thread.")
        }

        // Mandatory for non-privileged seccomp
        LinuxNative.withTransaction {
            LinuxNative.process.prctl(io.mazewall.core.PrctlCommand.SetNoNewPrivs(true))
        }.getOrThrow("prctl(PR_SET_NO_NEW_PRIVS)")

        val filter = BpfFilter.build(arch, policy)

        // Helper thread to send seccomp listener FD to daemon
        val listenerFdPromise = CompletableFuture<Int>()
        val setupError = AtomicReference<Throwable?>()
        val setupHelper = Thread {
            try {
                val listenerFdValue = listenerFdPromise.get()
                val sent = sendDescriptor(socketFd, listenerFdValue)
                if (!sent) {
                    setupError.set(IllegalStateException("Failed to send seccomp listener FD to daemon"))
                }
            } catch (e: InterruptedException) {
                setupError.set(e)
            } catch (e: java.util.concurrent.ExecutionException) {
                setupError.set(e)
            }
        }.apply {
            isDaemon = true
            name = "supervisor-setup-helper"
            start()
        }

        // Start background JVM listener thread to handle validation requests
        val listener = JVMValidationListener(
            FileDescriptor.unsafe(socketFd),
            scopingPolicy
        )
        listener.start()

        val tid = LinuxNative.process.gettid()
        threadRegistry[tid] = Thread.currentThread()

        try {
            nativeScope {
                val prog = LinuxNative.memory.newSockFProg(filter)
                val r = LinuxNative.withTransaction {
                    LinuxNative.syscall(
                        arch.seccompSyscallNumber.toLong(),
                        NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                        NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER.toLong()),
                        NativeArg.MemoryArg(prog),
                    )
                }
                val listenerFd = r.getFdOrThrow("seccomp(SECCOMP_FILTER_FLAG_NEW_LISTENER)").let { FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(it.value) }

                listenerFdPromise.complete(listenerFd.value)

                setupHelper.join()
                setupError.get()?.let { throw it }
            }
        } finally {
            threadRegistry.remove(tid)
        }
    }

    private fun connectWithRetry(socketPath: String, maxRetries: Int = 500, delayMs: Long = 10L): Int {
        Arena.ofConfined().use { arena ->
            val sockaddrUn = io.mazewall.ffi.memory.SockaddrUnSegment(arena.allocate(Layouts.SOCKADDR_UN))
            sockaddrUn.setSunFamily(1.toShort()) // AF_UNIX = 1
            val pathBytes = socketPath.toByteArray(StandardCharsets.UTF_8)
            val pathSeg = sockaddrUn.getSunPath()
            MemorySegment.copy(pathBytes, 0, pathSeg, ValueLayout.JAVA_BYTE, 0L, pathBytes.size)

            var lastErrno = 0
            for (retry in 0 until maxRetries) {
                val fdRes = LinuxNative.withTransaction {
                    LinuxNative.networking.socket(1, 1, 0) // AF_UNIX = 1, SOCK_STREAM = 1
                }
                if (fdRes is LinuxNative.SyscallResult.Error) {
                    lastErrno = fdRes.errno
                    Thread.sleep(delayMs)
                    continue
                }
                val fdVal = fdRes.getOrThrow("socket").toInt()
                val fd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(fdVal)
                val connRes = LinuxNative.withTransaction {
                    LinuxNative.networking.connect(fd, sockaddrUn.segment, 110)
                }
                if (connRes is LinuxNative.SyscallResult.Success) {
                    return fdVal
                }
                lastErrno = (connRes as LinuxNative.SyscallResult.Error).errno
                LinuxNative.fileSystem.close(fd)

                Thread.sleep(delayMs)
            }
            throw IllegalStateException(
                "Failed to connect to supervisor daemon socket at $socketPath after $maxRetries retries. Last errno=$lastErrno",
            )
        }
    }

    private fun sendDescriptor(socketFd: Int, fdToSend: Int): Boolean {
        Arena.ofConfined().use { arena ->
            val dummyByte = arena.allocate(ValueLayout.JAVA_BYTE)

            val controlBuf = arena.allocate(MSG_CONTROL_BUF_SIZE)
            controlBuf.fill(0)
            val cmsg = io.mazewall.ffi.memory.CmsghdrSegment(controlBuf)
            cmsg.setCmsgLen(CMSG_RIGHTS_LEN)
            cmsg.setCmsgLevel(SOL_SOCKET)
            cmsg.setCmsgType(SCM_RIGHTS)
            cmsg.setDataFd(fdToSend)

            val iov = io.mazewall.ffi.memory.IovecSegment(arena.allocate(Layouts.IOVEC))
            iov.setIovBase(dummyByte)
            iov.setIovLen(1L)

            val msg = io.mazewall.ffi.memory.MsghdrSegment(arena.allocate(Layouts.MSGHDR))
            msg.setMsgIov(iov.segment)
            msg.setMsgIovlen(1L)
            msg.setMsgControl(controlBuf)
            msg.setMsgControllen(MSG_CONTROL_BUF_SIZE)

            val res = LinuxNative.withTransaction {
                LinuxNative.networking.sendmsg(FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(socketFd), msg.segment, 0)
            }
            return res is LinuxNative.SyscallResult.Success
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
