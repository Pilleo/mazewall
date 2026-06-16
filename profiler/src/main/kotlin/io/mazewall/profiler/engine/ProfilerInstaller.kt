package io.mazewall.profiler.engine

import io.mazewall.BpfFilter
import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.Uncompiled
import io.mazewall.core.Arch
import io.mazewall.core.NativeArg
import io.mazewall.getFdOrThrow
import io.mazewall.onSuccess
import io.mazewall.ffi.NativeConstants
import io.mazewall.seccomp.BpfInstruction
import io.mazewall.profiler.Profiler
import io.mazewall.profiler.internal.ProfilerSocket
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Internal helper for installing seccomp profiling filters.
 */
internal object ProfilerInstaller {
    private const val EINTR = 4

    fun installProfilingFilterForThread(
        socketPath: String,
        policy: Policy<*, Uncompiled>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        workerThreadProvider: () -> Thread?,
        connectWithRetry: (String) -> Int = { path -> ProfilerSocket.connectWithRetry(path) },
        startTraceListener: (Int, MutableList<TraceEvent>, MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?, MutableMap<String, Long>, () -> Thread?) -> Unit,
    ) {
        val session = ProfilerInstallerSession(
            socketPath = socketPath,
            policy = policy,
            accumulatedLogs = accumulatedLogs,
            stackTracesMap = stackTracesMap,
            pathCache = pathCache,
            workerThreadProvider = workerThreadProvider,
            connectWithRetry = connectWithRetry,
            startTraceListener = startTraceListener,
        )
        session.install()
    }
}

internal class ProfilerInstallerSession(
    private val socketPath: String,
    private val policy: Policy<*, Uncompiled>,
    private val accumulatedLogs: MutableList<TraceEvent>,
    private val stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
    private val pathCache: MutableMap<String, Long>,
    private val workerThreadProvider: () -> Thread?,
    private val connectWithRetry: (String) -> Int,
    private val startTraceListener: (Int, MutableList<TraceEvent>, MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?, MutableMap<String, Long>, () -> Thread?) -> Unit,
) {
    private val installLatch = CountDownLatch(1)
    private val proceedLatch = CountDownLatch(1)
    private var listenerFd: LinuxNative.FileDescriptor = LinuxNative.FileDescriptor.INVALID

    @Volatile
    var state: ProfilerInstallerState = ProfilerInstallerState.Uninitialized
        private set

    fun install() {
        state = ProfilerInstallerState.InstallingBpf

        val coordinatorThread =
            Thread {
                runCoordinatorLogic()
            }.apply {
                isDaemon = true
                name = "profiler-coordinator"
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                    this@ProfilerInstallerSession.state = ProfilerInstallerState.Failed(e)
                    proceedLatch.countDown()
                }
            }

        coordinatorThread.start()

        try {
            ensureNoNewPrivs()
            val filters = BpfFilter.build(Arch.current(), policy, profilingMode = true)
            Arena.ofConfined().use { arena ->
                with(arena) {
                    listenerFd = installProfilingBpf(filters)
                }
            }
        } catch (e: IOException) {
            state = ProfilerInstallerState.Failed(e)
            proceedLatch.countDown()
        } catch (e: IllegalStateException) {
            state = ProfilerInstallerState.Failed(e)
            proceedLatch.countDown()
        } finally {
            installLatch.countDown()
        }

        proceedLatch.await()
        val finalState = state
        if (finalState is ProfilerInstallerState.Failed) {
            throw finalState.error
        }
    }

    private fun runCoordinatorLogic() {
        installLatch.await()
        val fd = listenerFd
        if (fd.isInvalid) {
            val finalState = state
            val err = if (finalState is ProfilerInstallerState.Failed) {
                finalState.error
            } else {
                IllegalStateException("Failed to install seccomp filter")
            }
            throw err
        }

        state = ProfilerInstallerState.Connecting(fd)
        var socketFd: LinuxNative.FileDescriptor = LinuxNative.FileDescriptor.INVALID
        var success = false
        try {
            socketFd = LinuxNative.FileDescriptor(connectWithRetry(socketPath))
            state = ProfilerInstallerState.SendingDescriptor(fd, socketFd)
            val sent = Profiler.sendDescriptorInternal(socketFd.value, fd.value)
            if (!sent) {
                throw IllegalStateException("Failed to send seccomp listener FD to daemon")
            }

            state = ProfilerInstallerState.VerifyingAck(fd, socketFd)
            Arena.ofConfined().use { arena ->
                with(arena) {
                    verifyDaemonAck(socketFd)
                }
            }

            // Start listener thread for this socket to receive TraceEvents
            startTraceListener(socketFd.value, accumulatedLogs, stackTracesMap, pathCache, workerThreadProvider)
            state = ProfilerInstallerState.Active(fd, socketFd)
            success = true
            proceedLatch.countDown()
        } catch (e: IOException) {
            state = ProfilerInstallerState.Failed(e)
            proceedLatch.countDown()
        } catch (e: IllegalStateException) {
            state = ProfilerInstallerState.Failed(e)
            proceedLatch.countDown()
        } finally {
            if (!success) {
                if (socketFd.isValid) {
                    LinuxNative.fileSystem.close(socketFd)
                }
            }
            LinuxNative.fileSystem.close(fd)
        }
    }

    context(arena: Arena)
    private fun verifyDaemonAck(socketFd: LinuxNative.FileDescriptor) {
        // Wait for ACK byte from daemon
        val ackBuf = arena.allocate(1)
        while (true) {
            val res = LinuxNative.withTransaction {
                LinuxNative.memory.read(socketFd, ackBuf, 1)
            }
            when (res) {
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value == 1L && ackBuf.get(ValueLayout.JAVA_BYTE, 0) == 0xAC.toByte()) {
                        return
                    }
                }

                is LinuxNative.SyscallResult.Error -> {
                    if (res.errno == EINTR) {
                        continue
                    }
                }
            }
            throw IllegalStateException("Daemon failed to ACK listener receipt")
        }
    }

    private fun ensureNoNewPrivs() {
        val r = LinuxNative.withTransaction {
            LinuxNative.process.prctl(
                NativeConstants.PR_SET_NO_NEW_PRIVS,
                NativeArg.IntArg(1),
                NativeArg.NullArg,
                NativeArg.NullArg,
                NativeArg.NullArg,
            )
        }
        r.getOrThrow("prctl(PR_SET_NO_NEW_PRIVS)")
    }

    context(arena: Arena)
    private fun installProfilingBpf(
        filters: List<BpfInstruction>,
    ): LinuxNative.FileDescriptor {
        val arch = Arch.current()
        val prog = LinuxNative.memory.newSockFProg(filters)
        val r = LinuxNative.withTransaction {
            LinuxNative.syscall(
                arch.seccompSyscallNumber.toLong(),
                NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER),
                NativeArg.MemoryArg(prog),
            )
        }

        return r.getFdOrThrow("seccomp(SECCOMP_FILTER_FLAG_NEW_LISTENER)")
    }
}
