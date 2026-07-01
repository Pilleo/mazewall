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
import java.util.concurrent.CountDownLatch
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
    @JvmOverloads
    fun <T> profile(
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
        block: () -> T,
    ): ProfilingResult<T> {
        if (Thread.currentThread().isVirtual) {
            throw IllegalStateException("Cannot run profiler inside virtual threads")
        }

        // Pre-warm classloading to prevent circular classloader deadlocks
        // when seccomp filters intercept file system reads during dynamic class loading.
        @Suppress("TooGenericExceptionCaught")
        try {
            val dummyFile = java.io.File.createTempFile("mazewall_warmup", ".tmp")
            dummyFile.writeText("warmup")
            dummyFile.readText()
            dummyFile.delete()

            // Pre-load all classes and code paths utilized inside ProfilerTraceListener
            val dummyEvent = io.mazewall.profiler.engine.TraceEvent(
                tidValue = 0,
                syscallName = "openat",
                args = longArrayOf(),
                paths = listOf("warmup"),
                stackTrace = null
            )
            dummyEvent.tid
            dummyEvent.syscallName

            // Warm up stack trace retrieval, mapping, and stringification
            Thread.currentThread().stackTrace.map { it.toString() }

            // Warm up BobCompiler and related classes
            val dummyBob = io.mazewall.profiler.compiler.BobCompiler.compile(java.util.concurrent.CopyOnWriteArrayList(listOf(dummyEvent)))

            // Warm up ProfilingResult
            val dummyResult = ProfilingResult(Unit, dummyBob, java.util.concurrent.ConcurrentHashMap())
            dummyResult.toString()

            // Warm up TraceListenerState subclasses
            val s1 = io.mazewall.profiler.internal.TraceListenerState.AwaitingEvent
            val s2 = io.mazewall.profiler.internal.TraceListenerState.ReadingHeader(0)
            val s3 = io.mazewall.profiler.internal.TraceListenerState.ReadingSyscall(0, 0)
            val s4 = io.mazewall.profiler.internal.TraceListenerState.ReadingArguments(0, "", 0)
            val s5 = io.mazewall.profiler.internal.TraceListenerState.ProcessingEvent(dummyEvent)
            val s6 = io.mazewall.profiler.internal.TraceListenerState.Disconnected
            s1.toString(); s2.toString(); s3.toString(); s4.toString(); s5.toString(); s6.toString()

            // Warm up list, map, and sorting operations
            val list = java.util.concurrent.CopyOnWriteArrayList<Array<StackTraceElement>>()
            list.add(emptyArray())
            val pathCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
            pathCache["key"] = System.currentTimeMillis()
            listOf("warmup").sorted().joinToString(",")
        } catch (ignored: Exception) {}

        val context = ProfilerDaemonManager.getOrSpawnSharedDaemon()
        val localLogs = CopyOnWriteArrayList<TraceEvent>()
        val localStackProfile = ConcurrentHashMap<TraceEvent, MutableList<Array<StackTraceElement>>>()
        val localPathCache = ConcurrentHashMap<String, Long>()

        val blockResult = AtomicReference<T>()
        val errorRef = AtomicReference<Throwable>()

        // Capture the trace listener created for this session so it can be drained before compile.
        val sessionListener = AtomicReference<ProfilerTraceListener>()

        var tid: io.mazewall.core.Tid? = null
        val workerThread = Thread {
            tid = LinuxNative.process.gettid()
            threadRegistry[tid!!] = Thread.currentThread()
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
                        if (captureStackTraces) localStackProfile else null,
                        localPathCache,
                        processWide,
                        onListenerCreated = { sessionListener.set(it) },
                    )

                    val res = block()
                    blockResult.set(res)
                } catch (e: Throwable) {
                    errorRef.set(e)
                }
            } finally {
                // Do not remove tid here. Wait until listener drains events.
            }
        }.apply {
            name = "mazewall-profiler-worker"
            start()
        }

        workerThread.join()

        errorRef.get()?.let { throw it }

        // CRITICAL: Drain the trace listener before compiling the Bill of Behavior.
        //
        // The daemon delivers events asynchronously after releasing the tracee (fire-and-forget).
        // After workerThread.join() returns, there may still be events in-flight: the daemon wrote
        // them to the socket but the listener thread has not yet called accumulatedLogs.add().
        //
        // close() sends SHUTDOWN_COMMAND_BYTE to signal the daemon to stop, then waits for the
        // listener thread to drain all remaining socket data until it sees EOF. Only then does
        // BobCompiler.compile() read localLogs, which is now stable and complete.
        sessionListener.get()?.let { listener ->
            listeners.remove(listener)
            listener.passThrough()
        }
        
        if (tid != null) {
            threadRegistry.remove(tid!!)
        }

        val bob = BobCompiler.compile(localLogs).copy(stackProfile = localStackProfile)
        return ProfilingResult(blockResult.get() as T, bob, localStackProfile)
    }

    /**
     * Wraps an [ExecutorService] to automatically profile all submitted tasks.
     * Use [ProfilerExecutorWrapper.recentLogs] to retrieve the captured behaviors.
     */
    fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper = wrap(delegate, true, *policies)

    /**
     * Wraps an [ExecutorService] to automatically profile all submitted tasks, with optional stacktrace capture.
     */
    fun wrap(
        delegate: ExecutorService,
        captureStackTraces: Boolean,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper {
        val policy = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        val context = ProfilerDaemonManager.getOrSpawnSharedDaemon()
        return ProfilerExecutorWrapper(delegate, policy, context, captureStackTraces)
    }

    private fun installProfilingFilterForThread(
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean,
        onListenerCreated: ((ProfilerTraceListener) -> Unit)? = null,
    ) {
        ProfilerInstaller.installProfilingFilterForThread(
            socketPath = socketPath,
            policy = policy,
            accumulatedLogs = accumulatedLogs,
            stackTracesMap = stackTracesMap,
            pathCache = pathCache,
            processWide = processWide,
            startTraceListener = { fd, logs, traces, cache, readyLatch ->
                val listener = ProfilerTraceListener(
                    FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(fd),
                    logs,
                    traces,
                    cache
                )
                listeners.add(listener)
                onListenerCreated?.invoke(listener)
                listener.start(readyLatch)
            },
        )
    }

    /**
     * Shuts down the shared profiler daemon and all active trace listeners.
     */
    fun shutdown() {
        synchronized(this) {
            listeners.forEach { it.passThrough() }
            listeners.clear()
            // Do not call ProfilerDaemonManager.stop() here. 
            // We want the daemon to stay alive in PassThrough mode to service background threads 
            // until the parent JVM exits (which triggers the daemon's stdin EOF monitor).
        }
    }

    class ProfilerExecutorWrapper(
        private val delegate: ExecutorService,
        private val policy: PolicyDefinition<*>,
        private val context: io.mazewall.profiler.internal.DaemonContext,
        private val captureStackTraces: Boolean = true,
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
                if (currentThread.isVirtual) {
                    throw IllegalStateException("Cannot run profiler inside virtual threads")
                }
                threadRegistry[LinuxNative.process.gettid()] = currentThread
                installProfilingFilterForThread(
                    context.socketPath,
                    policy,
                    recentLogs,
                    if (captureStackTraces) recentStackProfiles else null,
                    pathCache,
                    false,
                    { currentThread }
                )
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
