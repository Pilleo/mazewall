package io.mazewall.profiler

import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.PolicyDefinition
import io.mazewall.PolicyPresets
import io.mazewall.Uncompiled
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.core.threadLocal
import io.mazewall.enforcer.diagnostics.validateNotVirtual
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.engine.ProfilerInstaller
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.profiler.internal.ProfilerTraceListener
import io.mazewall.profiler.internal.TraceListenerMachine
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level API for system call profiling and Bill of Behavior (SBoB) generation.
 *
 * Tier S Profiler: Uses seccomp USER_NOTIF to trap system calls and notify a background daemon,
 * which resolves arguments (like paths) using `process_vm_readv` before allowing the syscall to continue.
 *
 * ### ⚠️ Security Warning & TOCTOU Limitations:
 * USER_NOTIF-based profiling is inherently subject to **Time-of-Check to Time-of-Use (TOCTOU)** security races
 * when resolving pointer-based system call arguments (such as file paths). A malicious or concurrent thread in the
 * target process can overwrite the path string memory after the profiler daemon reads it via `process_vm_readv`
 * but before the kernel actually dereferences and executes the system call.
 *
 * For robust, race-free, and kernel-enforced filesystem containment, **Landlock LSM** is the preferred and recommended
 * mechanism, as it evaluates and enforces path-based restrictions directly in the kernel space at the inode level,
 * making it completely immune to pointer-dereferencing TOCTOU attacks.
 */
object Profiler {
    private const val WORKER_JOIN_TIMEOUT_MS = 60_000L
    private const val GRACE_PERIOD_MS = 5_000L

    private val listeners = CopyOnWriteArrayList<ProfilerTraceListener>()
    internal val threadRegistry = ConcurrentHashMap<Tid, Thread>()

    internal var daemonManagerProvider: () -> io.mazewall.profiler.internal.ProfilerDaemonManager = {
        io.mazewall.profiler.internal.ProfilerDaemonManager
        .getInstance()
    }
    internal var installerProvider: io.mazewall.profiler.engine.ProfilerInstallerInterface = io.mazewall.profiler.engine.RealProfilerInstaller
    internal var tidProvider: () -> Tid = { LinuxNative.process.gettid() }
    private val runtimeOverride = ThreadLocal<ProfilerRuntime?>()

    internal fun currentRuntime(): ProfilerRuntime = runtimeOverride.get() ?: ProfilerRuntime(daemonManagerProvider, installerProvider, tidProvider)

    internal inline fun <T> withRuntime(
        runtime: ProfilerRuntime,
        block: () -> T,
    ): T {
        val previous = runtimeOverride.get()
        runtimeOverride.set(runtime)
        return try {
            block()
        } finally {
            runtimeOverride.set(previous)
        }
    }

    /**
     * Profiles the given [block] and returns a [BillOfBehavior].
     */
    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally") // session cleanup must preserve worker failures and collect cleanup failures
    @JvmStatic
    @JvmOverloads
    fun <T> profile(
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
        block: () -> T,
    ): ProfilingResult<T> {
        io.mazewall.profiler.ffi.ProfilerNativeCommandWriter
            .preload()
        TraceListenerMachine.preload()
        validateNotVirtual()
        ProfilerAckPreload.ensureLoaded()

        val runtime = currentRuntime()

        val context = runtime.daemonManager().getOrSpawnSharedDaemon()
        val localLogs = CopyOnWriteArrayList<TraceEvent>()
        val localStackProfile = ConcurrentHashMap<TraceEvent, MutableList<Array<StackTraceElement>>>()
        val localPathCache = ConcurrentHashMap<String, Long>()
        val sessionState = ProfilerSessionState()

        val blockResult = AtomicReference<T>()
        val errorRef = AtomicReference<Throwable>()

        // Capture the trace listener created for this session so it can be drained before compile.
        val sessionListener = AtomicReference<ProfilerTraceListener>()
        var sessionCleaned = false

        var tid: io.mazewall.core.Tid? = null

        fun cleanupSession() {
            var cleanupFailure: Throwable? = null
            sessionListener.get()?.let { listener ->
                listeners.remove(listener)
                try {
                    listener.passThrough()
                } catch (failure: Throwable) {
                    cleanupFailure = failure
                }
            }
            tid?.let(sessionState::remove)
            cleanupFailure?.let { throw it }
        }

        val workerThread = Thread {
            val workerTid = runtime.tid()
            tid = workerTid
            sessionState.register(workerTid, Thread.currentThread())
            try {
                // We must use a separate thread because seccomp USER_NOTIF stops the calling thread.
                // The resolver daemon needs to be notified by the kernel, which then notifies
                // our JVM listener thread to record the event.
                try {
                        installProfilingFilterForThread(
                            installer = runtime.installer,
                        context.socketPath,
                        PolicyPresets.PURE_COMPUTE_UNSAFE,
                        localLogs,
                        if (captureStackTraces) localStackProfile else null,
                        localPathCache,
                        processWide,
                        sessionState,
                        onListenerCreated = { sessionListener.set(it) },
                    )

                    val res = block()
                    blockResult.set(res)
                } catch (expectedWorkerFailure: Exception) {
                    errorRef.set(expectedWorkerFailure)
                } catch (expectedWorkerError: Error) {
                    errorRef.set(expectedWorkerError)
                }
            } finally {
                // Do not remove tid here. Wait until listener drains events.
            }
        }.apply {
            name = "mazewall-profiler-worker"
            start()
        }

        var primaryFailure: Throwable? = null
        try {
            // Bounded join (issue-20260823-172000): a wedged reactor must not hang the profiler forever.
            workerThread.join(WORKER_JOIN_TIMEOUT_MS)
            if (workerThread.isAlive) {
                workerThread.interrupt()
                workerThread.join(GRACE_PERIOD_MS)
                errorRef.get()?.let { throw it }
                throw IllegalStateException(
                    "Profiler worker '${workerThread.name}' did not terminate within ${WORKER_JOIN_TIMEOUT_MS}ms",
                )
            }

            errorRef.get()?.let { throw it }

            // Drain the listener before compiling so asynchronously delivered events are stable.
            cleanupSession()
            sessionCleaned = true

            val bob = BobCompiler.compile(localLogs).copy(stackProfile = localStackProfile)
            val observations = localLogs.map {
                io.mazewall.profiler.ProfileObservation
                .fromTraceEvent(it)
            }
            val listener = sessionListener.get()
            val dropped = (listener?.eventQueue?.droppedCount?.toInt() ?: 0) + (listener?.droppedEvents ?: 0)
            val coverage = ProfilingCoverage.infer(
                strategy = ProfileStrategy.USER_NOTIF,
                strategyReason = "Profiler.profile USER_NOTIF session",
                processWide = processWide,
                observations = observations,
                stacks = if (captureStackTraces) StackAttribution.CAPTURED else StackAttribution.SKIPPED,
                droppedEvents = dropped,
                drainComplete = listener?.drainComplete == true,
                environment = ProfileEnvironment(
                    kernelRelease = System.getProperty("os.version") ?: "unknown",
                    ebpfLoad = EbpfCapability.probe(),
                ),
            )
            return ProfilingResult(blockResult.get() as T, bob, localStackProfile, coverage, observations)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (!sessionCleaned) {
                try {
                    cleanupSession()
                } catch (cleanupFailure: Throwable) {
                    primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                }
            }
        }
    }

    /**
     * Java-friendly overload for profiling a [Callable].
     */
    @JvmStatic
    @JvmOverloads
    fun <T> profile(
        callable: Callable<T>,
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
    ): ProfilingResult<T> = profile(processWide, captureStackTraces) { callable.call() }

    /**
     * Java-friendly overload for profiling a [Runnable].
     */
    @JvmStatic
    @JvmOverloads
    fun profile(
        runnable: Runnable,
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
    ): ProfilingResult<Unit> = profile(processWide, captureStackTraces) { runnable.run() }

    /**
     * Wraps an [ExecutorService] to automatically profile all submitted tasks.
     * Use [ProfilerExecutorWrapper.recentLogs] to retrieve the captured behaviors.
     */
    @JvmStatic
    fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper = wrap(delegate, true, *policies)

    /**
     * Wraps an [ExecutorService] to automatically profile all submitted tasks, with optional stacktrace capture.
     */
    @JvmStatic
    fun wrap(
        delegate: ExecutorService,
        captureStackTraces: Boolean,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper {
        val policy =
            if (policies.isEmpty()) {
                PolicyPresets.PURE_COMPUTE_UNSAFE
            } else {
                PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
            }
        val runtime = currentRuntime()
        val context = runtime.daemonManager().getOrSpawnSharedDaemon()
        return ProfilerExecutorWrapper(delegate, policy, context, captureStackTraces, runtime)
    }

    private fun installProfilingFilterForThread(
        installer: io.mazewall.profiler.engine.ProfilerInstallerInterface,
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean,
        sessionState: ProfilerSessionState = ProfilerSessionState(),
        onListenerCreated: ((ProfilerTraceListener) -> Unit)? = null,
    ) {
        installer.installProfilingFilterForThread(
            socketPath = socketPath,
            policy = policy,
            accumulatedLogs = accumulatedLogs,
            stackTracesMap = stackTracesMap,
            pathCache = pathCache,
            processWide = processWide,
            startTraceListener = { fd, logs, traces, cache, readyLatch ->
                val listener = ProfilerTraceListener(
                    FileDescriptor.adopt(fd, FileDescriptorRole.UnixSocket),
                    logs,
                    traces,
                    cache,
                    sessionState::threadFor,
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
    @JvmStatic
    fun shutdown() {
        synchronized(this) {
            listeners.forEach { it.passThrough() }
            listeners.clear()
            // Do not call ProfilerDaemonManager.stop() here.
            // We want the daemon to stay alive in PassThrough mode to service background threads
            // until the parent JVM exits (which triggers the daemon's stdin EOF monitor).
        }
    }

    class ProfilerExecutorWrapper internal constructor(
        private val delegate: ExecutorService,
        private val policy: PolicyDefinition<*>,
        private val context: io.mazewall.profiler.internal.DaemonContext,
        private val captureStackTraces: Boolean = true,
        private val runtime: ProfilerRuntime,
    ) : ExecutorService by delegate {
        private var threadApplied by threadLocal { false }
        val recentLogs = CopyOnWriteArrayList<TraceEvent>()
        val recentStackProfiles =
            ConcurrentHashMap<TraceEvent, MutableList<Array<StackTraceElement>>>()
        private val pathCache = ConcurrentHashMap<String, Long>()
        private val sessionState = ProfilerSessionState()

        override fun <T : Any?> submit(task: Callable<T>): Future<T> {
            return delegate.submit(
                Callable {
                applyProfilingIfNecessary()
                task.call()
            },
            )
        }

        override fun submit(task: Runnable): Future<*> {
            return delegate.submit(
                Runnable {
                applyProfilingIfNecessary()
                task.run()
            },
            )
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
            if (!threadApplied) {
                val currentThread = Thread.currentThread()
                validateNotVirtual()
                sessionState.register(runtime.tid(), currentThread)
                installProfilingFilterForThread(
                    installer = runtime.installer,
                    socketPath = context.socketPath,
                    policy = policy,
                    accumulatedLogs = recentLogs,
                    stackTracesMap = if (captureStackTraces) recentStackProfiles else null,
                    pathCache = pathCache,
                    processWide = false,
                    sessionState = sessionState,
                    onListenerCreated = {},
                )
                threadApplied = true
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
