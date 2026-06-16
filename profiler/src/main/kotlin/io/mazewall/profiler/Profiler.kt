package io.mazewall.profiler

import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.Uncompiled
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Pid
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.engine.ProfilerInstaller
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import io.mazewall.profiler.internal.DaemonContext
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.profiler.internal.ProfilerSocket
import io.mazewall.profiler.internal.ProfilerTraceListener
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

// SUPPRESSION JUSTIFICATION: This class is the central coordinator for the unprivileged system call profiler.
// Keeping the socket setup, trace compilation, daemon life cycle, and thread registering functions together
// maintains the architectural cohesion of the diagnostic engine.

/**
 * High-performance Out-of-Process USER_NOTIF Profiler API.
 */
object Profiler {
    private val logger = Logger.getLogger(Profiler::class.java.name)
    val threadRegistry = ConcurrentHashMap<io.mazewall.core.Pid, Thread>()
    private val listeners = CopyOnWriteArrayList<ProfilerTraceListener>()

    /**
     * Profiles [block] on a dedicated OS platform thread under a seccomp
     * USER_NOTIF filter and returns both the block's return value and the
     * complete [BillOfBehavior].
     *
     * The lambda runs synchronously — the caller blocks until it completes.
     *
     * ## Thread isolation
     * A fresh OS thread is created for each call. Syscalls from any threads
     * spawned *inside* the lambda are not captured — seccomp USER_NOTIF is
     * per-thread, and profiling child threads requires explicit opt-in not
     * yet provided by this API.
     *
     * ## Stack traces
     * Per-syscall JVM stack traces are captured on a best-effort basis.
     * When the kernel pauses the worker for USER_NOTIF, its Java stack is
     * frozen. The trace listener captures it via [Thread.stackTrace] before
     * the daemon sends FLAG_CONTINUE. Frames are Java-only. There is a brief
     * window between the daemon sending the TraceEvent and the JVM listener
     * reading it; in practice the worker is still in-kernel during this
     * window, but this is not a formal JMM guarantee. Treat traces as
     * diagnostic, not as hard security evidence.
     *
     * @throws IllegalStateException if the profiling infrastructure cannot
     *         be initialised (e.g. PR_SET_NO_NEW_PRIVS failed).
     * @throws IllegalStateException if called from a Loom virtual thread
     *         (would poison the ForkJoinPool carrier — see AGENTS.md Rule B).
     */
    fun <T> profile(block: () -> T): ProfilingResult<T> {
        if (Thread.currentThread().isVirtual) {
            throw IllegalStateException("Seccomp profiling is not supported on Loom virtual threads.")
        }

        val context = getOrSpawnSharedDaemon()

        val localLogs = CopyOnWriteArrayList<SyscallEvent<SyscallEventState.Resolved>>()
        val localStackProfile =
            ConcurrentHashMap<SyscallEvent<SyscallEventState.Resolved>, MutableList<Array<StackTraceElement>>>()
        val localPathCache = ConcurrentHashMap<String, Long>()

        var workerThread: Thread? = null

        try {
            val blockResult = AtomicReference<Any?>(null)
            val blockError = AtomicReference<Throwable?>(null)

            // Dedicated OS platform thread for block
            val thread =
                Thread {
                    val spid = LinuxNative.process.gettid()
                    threadRegistry[spid] = Thread.currentThread()
                    // SUPPRESSION JUSTIFICATION: We are executing an arbitrary, untrusted user block.
                    // We MUST catch Throwable to ensure we capture any Error or Exception thrown
                    // by the user's workload so we can bubble it up to the calling thread safely.
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        installProfilingFilterForThread(
                            context.socketPath,
                            Policy.PURE_COMPUTE_UNSAFE,
                            localLogs,
                            localStackProfile,
                            localPathCache,
                        ) { workerThread }

                        val res = block()
                        blockResult.set(res)
                    } catch (e: Throwable) {
                        blockError.set(e)
                    } finally {
                        threadRegistry.remove(spid)
                    }
                }

            workerThread = thread
            thread.start()
            thread.join()

            val err = blockError.get()
            if (err != null) throw err

            val behavior =
                BobCompiler.compile(localLogs).copy(
                    stackProfile = localStackProfile.toMap(),
                )

            // SUPPRESSION JUSTIFICATION: blockResult holds the result of the generic `block: () -> T` closure.
            // Because it is stored in an AtomicReference<Any?> to pass across the worker thread boundary,
            // type erasure requires an unchecked cast when retrieving it. This cast is statically safe.
            val finalResult = blockResult.get()
            if (finalResult == null) {
                logger.warning("Profiler.profile: blockResult is null! localLogs size: ${localLogs.size}")
            }
            @Suppress("UNCHECKED_CAST")
            return ProfilingResult(finalResult as T, behavior)
        } finally {
            // No cleanup here — the shared daemon stays alive until JVM shutdown
        }
    }

    private fun getOrSpawnSharedDaemon(): DaemonContext {
        return ProfilerDaemonManager.getOrSpawnSharedDaemon()
    }

    fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper {
        val policy = Policy.combine(*policies)
        val context = getOrSpawnSharedDaemon()
        return ProfilerExecutorWrapper(delegate, policy, context)
    }

    private fun installProfilingFilterForThread(
        socketPath: String,
        policy: Policy<*, Uncompiled>,
        accumulatedLogs: MutableList<SyscallEvent<SyscallEventState.Resolved>>,
        stackTracesMap: MutableMap<SyscallEvent<SyscallEventState.Resolved>, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        workerThreadProvider: () -> Thread?,
    ) {
        ProfilerInstaller.installProfilingFilterForThread(
            socketPath = socketPath,
            policy = policy,
            accumulatedLogs = accumulatedLogs,
            stackTracesMap = stackTracesMap,
            pathCache = pathCache,
            workerThreadProvider = workerThreadProvider,
            startTraceListener = { fd, logs, traces, cache, provider ->
                val listener = ProfilerTraceListener(
                    FileDescriptor.unsafe<FileDescriptorRole.Generic>(fd),
                    logs,
                    traces,
                    cache,
                    provider
                )
                listeners.add(listener)
                listener.start()
            },
        )
    }

    internal fun sendDescriptorInternal(
        socketFd: Int,
        fdToSend: Int,
    ): Boolean = ProfilerSocket.sendDescriptor(socketFd, fdToSend)

    /**
     * Shuts down the profiler daemon and releases all background listeners.
     */
    @JvmStatic
    public fun stop() {
        ProfilerDaemonManager.stop()
        listeners.forEach {
            // SUPPRESSION JUSTIFICATION: We are performing a best-effort cleanup of background components.
            // Any exception during a single component's close() must be logged but not allowed to
            // stop the rest of the teardown process.
            @Suppress("TooGenericExceptionCaught")
            try {
                it.close()
            } catch (e: Exception) {
                logger.warning("Failed to close trace listener: ${e.message}")
            }
        }
        listeners.clear()
    }

    class ProfilerExecutorWrapper(
        private val delegate: ExecutorService,
        private val policy: Policy<*, Uncompiled>,
        private val context: DaemonContext,
    ) : ExecutorService by delegate {
        private val threadApplied = ThreadLocal.withInitial { false }
        val recentLogs = CopyOnWriteArrayList<SyscallEvent<SyscallEventState.Resolved>>()
        val recentStackProfiles =
            ConcurrentHashMap<SyscallEvent<SyscallEventState.Resolved>, MutableList<Array<StackTraceElement>>>()
        private val sharedPathCache = ConcurrentHashMap<String, Long>()

        /**
         * Compiles the captured logs and stack traces into a [BillOfBehavior].
         */
        fun compileBillOfBehavior(): BillOfBehavior =
            BobCompiler.compile(recentLogs).copy(
                stackProfile = recentStackProfiles.toMap(),
            )

        override fun execute(command: Runnable) {
            delegate.execute {
                val spid = LinuxNative.process.gettid()
                threadRegistry[spid] = Thread.currentThread()
                try {
                    ensureApplied()
                    command.run()
                } finally {
                    threadRegistry.remove(spid)
                }
            }
        }

        override fun <T> submit(task: Callable<T>): Future<T> =
            delegate.submit(
                Callable {
                    val spid = LinuxNative.process.gettid()
                    threadRegistry[spid] = Thread.currentThread()
                    try {
                        ensureApplied()
                        task.call()
                    } finally {
                        threadRegistry.remove(spid)
                    }
                },
            )

        override fun <T> submit(
            task: Runnable,
            result: T,
        ): Future<T> =
            delegate.submit({
                val spid = LinuxNative.process.gettid()
                threadRegistry[spid] = Thread.currentThread()
                try {
                    ensureApplied()
                    task.run()
                } finally {
                    threadRegistry.remove(spid)
                }
            }, result)

        override fun submit(task: Runnable): Future<*> =
            delegate.submit {
                val spid = LinuxNative.process.gettid()
                threadRegistry[spid] = Thread.currentThread()
                try {
                    ensureApplied()
                    task.run()
                } finally {
                    threadRegistry.remove(spid)
                }
            }

        override fun close() {
            delegate.close()
        }

        private fun ensureApplied() {
            if (Thread.currentThread().isVirtual) {
                throw IllegalStateException("Seccomp profiling is not supported on Loom virtual threads.")
            }
            if (!threadApplied.get()) {
                val currentThread = Thread.currentThread()
                installProfilingFilterForThread(
                    context.socketPath,
                    policy,
                    recentLogs,
                    recentStackProfiles,
                    sharedPathCache,
                ) { currentThread }

                threadApplied.set(true)
            }
        }

        override fun shutdown() {
            delegate.shutdown()
        }

        override fun shutdownNow(): List<Runnable> {
            return delegate.shutdownNow()
        }
    }
}
