package io.mazewall.profiler.engine

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
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.getFdOrThrow
import io.mazewall.profiler.internal.ProfilerSocket

/**
 * Internal orchestrator for installing profiling filters and initializing the listener.
 */
internal object ProfilerInstaller {
    /**
     * Installs a seccomp profiling filter (SECCOMP_RET_USER_NOTIF) for the current thread.
     */
    fun installProfilingFilterForThread(
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        workerThreadProvider: () -> Thread?,
        connectWithRetry: (String) -> Int = { path -> ProfilerSocket.connectWithRetry(path) },
        startTraceListener: (
            Int,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            () -> Thread?
        ) -> Unit,
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
    private val policy: PolicyDefinition<*>,
    private val accumulatedLogs: MutableList<TraceEvent>,
    private val stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
    private val pathCache: MutableMap<String, Long>,
    private val workerThreadProvider: () -> Thread?,
    private val connectWithRetry: (String) -> Int,
    private val startTraceListener: (
        Int,
        MutableList<TraceEvent>,
        MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        MutableMap<String, Long>,
        () -> Thread?
    ) -> Unit,
) {
    fun install() {
        if (!Platform.featureMatrix.seccompUserNotifSupported) {
            throw UnsupportedKernelFeatureException("Seccomp User Notifications are required for profiling.")
        }

        // Mandatory for non-privileged seccomp
        LinuxNative.withTransaction {
            LinuxNative.process.prctl(io.mazewall.core.PrctlCommand.SetNoNewPrivs(true))
        }.getOrThrow("prctl(PR_SET_NO_NEW_PRIVS)")

        val fd = connectWithRetry(socketPath)
        val arch = Arch.current()

        val filter = BpfFilter.build(arch, policy, profilingMode = true)

        // We must spawn all infrastructure threads BEFORE installing the seccomp filter on the current thread.
        // Seccomp filters are thread-scoped and inherited by children. If we spawn these threads AFTER
        // sandboxing the current thread, they will also be sandboxed and trapped, leading to a deadlock
        // when they try to communicate with the daemon (which is blocked waiting for setup to finish).
        val listenerFdPromise = java.util.concurrent.CompletableFuture<Int>()
        val setupError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val setupHelper = Thread {
            @Suppress("TooGenericExceptionCaught")
            try {
                val listenerFdValue = listenerFdPromise.get()
                val sent = ProfilerSocket.sendDescriptor(fd, listenerFdValue)
                if (!sent) {
                    setupError.set(IllegalStateException("Failed to send seccomp listener FD to daemon"))
                }
            } catch (e: Exception) {
                setupError.set(e)
            }
        }.apply {
            isDaemon = true
            name = "profiler-setup-helper"
            start()
        }

        // Start background thread to listen for events from the daemon (uncontained)
        startTraceListener(fd, accumulatedLogs, stackTracesMap, pathCache, workerThreadProvider)

        nativeScope {
            val prog = LinuxNative.memory.newSockFProg(filter)
            val listenerFd = installListener(arch, prog)

            // Release the helper to send the descriptor
            listenerFdPromise.complete(listenerFd.value)

            // Wait for setup to finish to ensure daemon is ready before workload starts
            setupHelper.join()
            setupError.get()?.let { throw it }
        }
    }

    private fun installListener(
        arch: Arch,
        prog: java.lang.foreign.MemorySegment,
    ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open> {
        val r = LinuxNative.withTransaction {
            LinuxNative.syscall(
                arch.seccompSyscallNumber.toLong(),
                NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER.toLong()),
                NativeArg.MemoryArg(prog),
            )
        }

        return r.getFdOrThrow("seccomp(SECCOMP_FILTER_FLAG_NEW_LISTENER)").let { FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(it.value) }
    }
}
