package io.mazewall.profiler

import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.PolicyDefinition
import io.mazewall.PolicyPresets
import io.mazewall.Uncompiled
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.engine.ProfilerInstaller
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.profiler.internal.ProfilerTraceListener
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * High-level API for system call profiling and Bill of Behavior (SBoB) generation.
 *
 * Tier S Profiler: Uses seccomp USER_NOTIF to trap system calls and notify a background daemon,
 * which resolves arguments (like paths) using `process_vm_readv` before allowing the syscall to continue.
 */
object Profiler {
    private val logger = Logger.getLogger(Profiler::class.java.name)
    private val listeners = CopyOnWriteArrayList<ProfilerTraceListener>()
    internal val threadRegistry = ConcurrentHashMap<Tid, Thread>()

    /**
     * Profiles the given [block] and returns a [BillOfBehavior].
     */
    fun <T> profile(
        block: () -> T,
    ): ProfilingResult<T> {
        val context = ProfilerDaemonManager.getOrSpawnSharedDaemon()
        val localLogs = CopyOnWriteArrayList<TraceEvent>()
        val localStackProfile = ConcurrentHashMap<TraceEvent, MutableList<Array<StackTraceElement>>>()
        val localPathCache = ConcurrentHashMap<String, Long>()

        val blockResult = AtomicReference<T>()
        val errorRef = AtomicReference<Throwable>()

        val workerThread = Thread {
            val tid = LinuxNative.process.gettid()
            threadRegistry[tid] = Thread.currentThread()
            try {
                // We must use a separate thread because seccomp USER_NOTIF stops the calling thread.
                // The resolver daemon needs to be notified by the kernel, which then notifies
                // our JVM listener thread to record the event.
                @Suppress("TooGenericExceptionCaught")
                try {
                    installProfilingFilterForThread(
                        context.socketPath,
                        PolicyPresets.PURE_COMPUTE_UNSAFE,
                        localLogs,
                        localStackProfile,
                        localPathCache,
                    ) { Thread.currentThread() }

                    val res = block()
                    blockResult.set(res)
                } catch (e: Throwable) {
                    errorRef.set(e)
                }
            } finally {
                threadRegistry.remove(tid)
            }
        }.apply {
            name = "mazewall-profiler-worker"
            start()
        }

        workerThread.join()

        errorRef.get()?.let { throw it }

        val bob = BobCompiler.compile(localLogs)
        return ProfilingResult(blockResult.get() as T, bob, localStackProfile)
    }

    /**
     * Wraps an [ExecutorService] to automatically profile all submitted tasks.
     * Use [ProfilerExecutorWrapper.recentLogs] to retrieve the captured behaviors.
     */
    fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper {
        val policy = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        val context = ProfilerDaemonManager.getOrSpawnSharedDaemon()
        return ProfilerExecutorWrapper(delegate, policy, context)
    }

    private fun installProfilingFilterForThread(
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
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
                    FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(fd),
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

    /**
     * Shuts down the shared profiler daemon and all active trace listeners.
     */
    fun shutdown() {
        synchronized(this) {
            listeners.forEach { it.close() }
            listeners.clear()
            ProfilerDaemonManager.stop()
        }
    }

    class ProfilerExecutorWrapper(
        private val delegate: ExecutorService,
        private val policy: PolicyDefinition<*>,
        private val context: io.mazewall.profiler.internal.DaemonContext,
    ) : ExecutorService by delegate {
        private val threadApplied = ThreadLocal.withInitial { false }
        val recentLogs = CopyOnWriteArrayList<TraceEvent>()
        val recentStackProfiles =
            ConcurrentHashMap<TraceEvent, MutableList<Array<StackTraceElement>>>()
        private val pathCache = ConcurrentHashMap<String, Long>()

        override fun <T : Any?> submit(task: Callable<T>): Future<T> {
            return delegate.submit(Callable {
                applyProfilingIfNecessary()
                task.call()
            })
        }

        override fun submit(task: Runnable): Future<*> {
            return delegate.submit(Runnable {
                applyProfilingIfNecessary()
                task.run()
            })
        }

        override fun execute(command: Runnable) {
            delegate.execute {
                applyProfilingIfNecessary()
                command.run()
            }
        }

        /** Compiles a [BillOfBehavior] from the events captured so far. */
        fun compileBillOfBehavior(): BillOfBehavior = BobCompiler.compile(recentLogs)

        private fun applyProfilingIfNecessary() {
            if (!threadApplied.get()) {
                val currentThread = Thread.currentThread()
                threadRegistry[LinuxNative.process.gettid()] = currentThread
                installProfilingFilterForThread(
                    context.socketPath,
                    policy,
                    recentLogs,
                    recentStackProfiles,
                    pathCache,
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
