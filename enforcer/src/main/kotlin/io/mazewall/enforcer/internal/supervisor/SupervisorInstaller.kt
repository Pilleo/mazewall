package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.UnsupportedKernelFeatureException
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.diagnostics.validateNotVirtual
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.internal.execution.LiveReceiverRetirement
import io.mazewall.enforcer.internal.execution.LiveValidationTransport
import io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorProcessMemoryReader
import io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannel
import io.mazewall.ffi.networking.SeccompInstallationGate
import io.mazewall.ffi.networking.SupervisorHelperCredentials
import io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller
import io.mazewall.ffi.networking.SupervisorValidationChannel
import io.mazewall.ffi.networking.helperValue
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.native.onFailure
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger

internal object SupervisorInstaller {
    private val logger = Logger.getLogger(SupervisorInstaller::class.java.name)
    private val installations = ThreadLocal.withInitial { mutableListOf<SupervisorInstallationLifetime>() }
    private val liveInstallations = ThreadLocal.withInitial { mutableListOf<LiveInstallationLifetime>() }

    internal fun prepareConfinedHelper(
        plan: PreparedHelperReadPlan,
        context: SupervisorContext,
        native: NativeEngine,
        deadline: ConfinedDeadline,
        retain: (ConfinedHelperSession) -> Unit,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        observations: HelperSecurityObservation = HelperSecurityObservation(),
    ): ConfinedHelperPreparation {
        val session = ConfinedHelperSession(plan, context, native, cancelled, observations)
        retain(session)
        return session.prepare(deadline)
    }

    internal fun requireReusableInstallations() {
        installations.get().forEach { it.requireReusable() }
        liveInstallations.get().forEach { it.requireReusable() }
    }

    @Suppress("TooGenericExceptionCaught") // rollback releases acquired leases, then preserves the original failure
    internal fun acquireTaskSession(scopingPolicy: StacktraceScopingPolicy): SupervisorSession =
        acquireTaskSession(scopingPolicy, LinuxNative.snapshot())

    /** Reuses this thread's installed filters with native authority captured by the caller. */
    internal fun acquireTaskSession(
        scopingPolicy: StacktraceScopingPolicy,
        native: NativeEngine,
    ): SupervisorSession = acquireCapturedTaskSession(CapturedScopingPolicy.capture(scopingPolicy), native)

    @Suppress("TooGenericExceptionCaught") // Rollback must preserve every original failure after unregistering acquired runtimes.
    internal fun acquireCapturedTaskSession(
        capturedScoping: CapturedScopingPolicy,
        native: NativeEngine,
    ): SupervisorSession {
        val tid = native.process.gettid()
        val owners = installations.get()
        owners.forEach { it.requireReusable() }
        check(owners.all { it.capturedScoping.matches(capturedScoping) }) { "Cannot replace the scoping policy of a permanent supervised filter; use a new platform thread" }
        val runtimes = owners.map { it.runtime }
        check(runtimes.isNotEmpty()) { "Supervised filter has no installation runtime on this thread" }
        val acquired = mutableListOf<SupervisorRuntimeContext>()
        try {
            runtimes.forEach {
                it.register(tid)
                acquired.add(it)
            }
        } catch (failure: Throwable) {
            acquired.forEach { it.unregister(tid) }
            throw failure
        }
        return SupervisorSession { acquired.forEach { it.unregister(tid) } }
    }

    public fun installSupervisedFilterForThread(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
        onFilterApplied: () -> Unit = {},
    ): SupervisorSession = installSupervisedFilterForThread(policy, scopingPolicy, onFilterApplied, LinuxNative.snapshot())

    /**
     * Installs a supervised filter using native authority supplied by the caller.
     * The three-argument overload remains the public convenience entry point.
     */
    public fun installSupervisedFilterForThread(
        policy: PolicyDefinition<*>,
        scopingPolicy: StacktraceScopingPolicy,
        onFilterApplied: () -> Unit,
        native: NativeEngine,
        userNotifSupported: Boolean = Platform.featureMatrix.seccompUserNotifSupported,
    ): SupervisorSession =
        installCapturedSupervisedFilterForThread(
            policy,
            CapturedScopingPolicy.capture(scopingPolicy),
            onFilterApplied,
            native,
            userNotifSupported,
        )

    /** Dormant until the production Live dispatcher guard is released. */
    @Suppress("TooGenericExceptionCaught") // A permanent filter retains its lifetime on every failure, including fatal errors.
    internal fun installLiveSupervisedFilterForThread(
        policy: PolicyDefinition<*>,
        receipt: LiveValidationTransport,
        onFilterApplied: () -> Unit,
        native: NativeEngine,
        userNotifSupported: Boolean,
    ): SupervisorSession {
        requireReusableInstallations()
        check(installations.get().isEmpty() && liveInstallations.get().isEmpty()) { "Live supervision requires a fresh platform thread" }
        validateNotVirtual()
        val filter = BpfFilter.build(Arch.current(), policy)
        val lifetime = LiveInstallationLifetime(Thread.currentThread(), native, receipt)
        var applied = false
        try {
            lifetime.start()
            SupervisorSeccompNotifInstaller.installLiveOwned(
                native,
                userNotifSupported,
                receipt.socketPath,
                filter,
                receipt,
                {
                    applied = true
                    onFilterApplied()
                },
                SeccompInstallationGate.Direct,
            ) { socket, ready -> lifetime.attach(socket, ready) }
            lifetime.requireReusable()
            liveInstallations.get().add(lifetime)
            return SupervisorSession { lifetime.closeAndAwait() }
        } catch (failure: Throwable) {
            if (applied) {
                liveInstallations.get().add(lifetime)
                lifetime.fail(failure)
            } else {
                lifetime.close()
            }
            throw failure
        }
    }

    internal fun installCapturedSupervisedFilterForThread(
        policy: PolicyDefinition<*>,
        capturedScoping: CapturedScopingPolicy,
        onFilterApplied: () -> Unit,
        native: NativeEngine,
        userNotifSupported: Boolean,
    ): SupervisorSession {
        requireReusableInstallations()
        check(liveInstallations.get().isEmpty()) { "Cannot install Legacy supervision over a Live listener" }
        check(installations.get().all { it.capturedScoping.matches(capturedScoping) }) {
            "Cannot replace the scoping policy of a permanent supervised filter; use a new platform thread"
        }
        ValidationListenerPreload.ensureLoaded()
        val context = SupervisorDaemonManager.getInstance().getOrSpawnSharedDaemon(native)
        val arch = Arch.current()

        // Assert not a virtual thread per Loom carrier poisoning rules
        validateNotVirtual()

        val filter = BpfFilter.build(arch, policy)
        val tid = native.process.gettid()
        val ownedInstallations = installations.get()
        val runtime = SupervisorRuntimeContext()
        val lifetime = SupervisorInstallationLifetime(Thread.currentThread(), runtime, capturedScoping)
        var previousLease: SupervisorSession? = null
        val session = SupervisorSession {
            runtime.unregister(tid)
            previousLease?.close()
        }
        var applied = false
        try {
            previousLease = if (ownedInstallations.isEmpty()) null else acquireCapturedTaskSession(capturedScoping, native)
            lifetime.start()
            runtime.register(tid)
            SupervisorSeccompNotifInstaller.installOwned(
                native = native,
                userNotifSupported = userNotifSupported,
                socketPath = context.socketPath,
                filter = filter,
                processWide = false,
                onFilterApplied = {
                    applied = true
                    onFilterApplied()
                },
            ) { socketFd, readyLatch ->
                val listener = JVMValidationListener(
                    socketFd,
                    capturedScoping.dispatch,
                    runtime,
                    native,
                    onUnexpectedTermination = { cause -> lifetime.fail(cause) },
                )
                lifetime.attach(AutoCloseable { listener.requestStop() })
                listener.start(readyLatch)
            }
            lifetime.requireReusable()
            ownedInstallations.add(lifetime)
            return session
        } catch (expectedInstallFailure: Exception) {
            if (applied) {
                ownedInstallations.add(lifetime)
                lifetime.fail(expectedInstallFailure)
            } else {
                lifetime.close()
            }
            session.close()
            throw expectedInstallFailure
        } catch (expectedInstallError: Error) {
            if (applied) {
                ownedInstallations.add(lifetime)
                lifetime.fail(expectedInstallError)
            } else {
                lifetime.close()
            }
            session.close()
            throw expectedInstallError
        }
    }
}

internal sealed interface ConfinedHelperPreparation {
    data class Ready(
        val binding: ConfinedBinding,
        val session: ConfinedHelperSession,
    ) : ConfinedHelperPreparation

    data class Rejected(
        val reason: ConfinedRejectReason,
        val cause: Throwable,
    ) : ConfinedHelperPreparation
}

internal class ConfinedHelperSession(
    private val plan: PreparedHelperReadPlan,
    private val context: SupervisorContext,
    private val native: NativeEngine,
    private val cancelled: () -> Boolean,
    private val observations: HelperSecurityObservation = HelperSecurityObservation(),
) {
    private enum class Phase { Unprepared, Preparing, Ready, Stopped, Retired }

    private val lock = ReentrantLock()
    private val stopping = AtomicBoolean()

    @Volatile private var phase = Phase.Unprepared
    private var socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
    private var channel: ConfinedHelperChannel? = null
    private var transfer: SupervisorOwnedFd? = null
    private var receipt: ConfinedHelperProtocol.Frame.Retired? = null
    val retired: Boolean get() = phase == Phase.Retired

    internal fun matchesOriginal(
        plan: PreparedHelperReadPlan,
        context: SupervisorContext,
    ): Boolean = this.plan === plan && this.context === context

    internal fun authenticateLiveSocket(target: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        check(phase == Phase.Ready && !stopping.get()) { "Confined helper is not Ready" }
        plan.validate()
        authenticate(target)
        check(phase == Phase.Ready && !stopping.get()) { "Confined helper was stopped" }
    }

    internal fun sendAuthenticatedLivePrelude(
        target: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        bytes: ByteArray,
        deadline: ConfinedDeadline,
    ) {
        check(phase == Phase.Ready && !stopping.get()) { "Confined helper was stopped" }
        ConfinedHelperChannel(native, target, { stopping.get() || cancelled() }, context.daemonProcess::isAlive).send(bytes, deadline)
        check(phase == Phase.Ready && !stopping.get()) { "Confined helper stopped during Live handoff" }
    }

    @Suppress("TooGenericExceptionCaught") // A failed readiness observation quarantines the session before worker installation.
    fun requireReady(
        binding: ConfinedBinding,
        deadline: ConfinedDeadline,
    ) {
        acquire(deadline)
        try {
            check(phase == Phase.Ready && !stopping.get() && binding == plan.manifest.binding) { "Confined helper is not Ready for this generation" }
            try {
                plan.validate()
                authenticate(requireNotNull(socket))
                val connected = requireNotNull(channel)
                check(connected.pollFrame(deadline) == null) { "Unsolicited confined helper response after Ready" }
                connected.checkDeadline(deadline)
            } catch (failure: Throwable) {
                phase = Phase.Stopped
                val cleanup = SocketCleanup()
                cleanup.attempt { throw failure }
                cleanup.attempt(::disconnect)
                cleanup.rethrow()
            }
        } finally {
            lock.unlock()
        }
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // One ownership rollback retains the original fatal identity before refusing ordinary failures.
    fun prepare(deadline: ConfinedDeadline): ConfinedHelperPreparation {
        try {
            acquire(deadline)
        } catch (failure: Exception) {
            stopping.set(true)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            val rejection = ConfinedHelperRejection.classify(failure, ConfinedRejectReason.RuntimeClosure)
            return ConfinedHelperPreparation.Rejected(rejection.reason, rejection)
        }
        try {
            check(phase == Phase.Unprepared) { "Confined parent setup is already spent" }
            phase = Phase.Preparing
            try {
                plan.validate()
                val connected = connect(deadline, cleanup = false)
                val binding = plan.manifest.binding
                connected.sendFrame(ConfinedHelperProtocol.Frame.Setup(plan.manifest, deadline))
                expect(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, binding, deadline), deadline)
                plan.manifest.capabilities.indices.forEach { index ->
                    connected.checkDeadline(deadline)
                    transfer = plan.duplicateForTransfer(index)
                    connected.sendFrame(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Offer, binding, index, deadline))
                    expect(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Expected, binding, index, deadline), deadline)
                    connected.sendDescriptor(requireNotNull(transfer), deadline)
                    expect(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Received, binding, index, deadline), deadline)
                    closeTransfer()
                }
                connected.sendFrame(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Complete, binding, plan.manifest.capabilities.size, deadline))
                expect(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, binding, deadline), deadline)
                plan.validate()
                connected.checkDeadline(deadline)
                phase = Phase.Ready
                return ConfinedHelperPreparation.Ready(binding, this)
            } catch (failure: Throwable) {
                phase = Phase.Stopped
                val cleanup = SocketCleanup()
                cleanup.attempt { throw failure }
                cleanup.attempt(::closeTransfer)
                cleanup.attempt(::disconnect)
                if (cleanup.failure is Error) cleanup.rethrow()
                val rejection = ConfinedHelperRejection.classify(requireNotNull(cleanup.failure), ConfinedRejectReason.RuntimeClosure)
                return ConfinedHelperPreparation.Rejected(rejection.reason, rejection)
            }
        } finally {
            lock.unlock()
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed receipt never releases the plan; failed socket close retains its exact owner.
    fun retire(deadline: ConfinedDeadline) {
        stopping.set(true)
        acquire(deadline)
        try {
            if (phase == Phase.Retired) return
            val readyChannel = if (phase == Phase.Ready) channel else null
            phase = Phase.Stopped
            try {
                closeTransfer()
                if (receipt == null) {
                    val connected = readyChannel ?: run {
                        disconnect()
                        connect(deadline, cleanup = true)
                    }
                    authenticate(requireNotNull(socket))
                    val binding = plan.manifest.binding
                    connected.sendFrame(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.Retire, binding, deadline))
                    val received = connected.receiveFrame(deadline).frame
                    val helper = requireNotNull(context.confinedHelper).identity
                    check(
                        received is ConfinedHelperProtocol.Frame.Retired &&
                            received.binding == binding &&
                            received.deadline == deadline &&
                            received.helperPid == helper.pid &&
                            received.helperStartTicks == helper.startTimeTicks,
                    ) {
                        "Confined retirement receipt differs from the acquired helper"
                    }
                    receipt = received
                }
                disconnect()
                phase = Phase.Retired
            } catch (failure: Throwable) {
                val cleanup = SocketCleanup()
                cleanup.attempt { throw failure }
                cleanup.attempt(::disconnect)
                cleanup.rethrow()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun acquire(deadline: ConfinedDeadline) {
        if (!lock.tryLock(deadline.remainingNanos(ConfinedDeadline.now(native)), TimeUnit.NANOSECONDS)) throw TimeoutException("Confined parent I/O is still active")
    }

    private fun connect(
        deadline: ConfinedDeadline,
        cleanup: Boolean,
    ): ConfinedHelperChannel {
        check(socket == null)
        deadline.remainingNanos(ConfinedDeadline.now(native))
        val result = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC or NativeConstants.O_NONBLOCK.toInt(), 0)
        result.helperValue("acquire parent helper control socket")
        val acquired = result.getFdOrThrow("acquire parent helper control socket", FileDescriptorRole.UnixSocket)
        socket = acquired
        val connected = ConfinedHelperChannel(native, acquired, {
            Thread.currentThread().isInterrupted || !cleanup && phase != Phase.Stopped && (stopping.get() || cancelled())
        }, context.daemonProcess::isAlive)
        channel = connected
        connected.connect(context.socketPath, deadline)
        authenticate(acquired)
        connected.checkDeadline(deadline)
        connected.send(byteArrayOf(0xe4.toByte()), deadline)
        return connected
    }

    @Suppress("TooGenericExceptionCaught") // Peer and ancestry refusal preserves native permission errno and original observation evidence.
    private fun authenticate(acquired: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        try {
            val bootstrap = requireNotNull(context.confinedBootstrap)
            requireNotNull(context.confinedHelper)
            bootstrap.requireCommon()
            observations.requirePeer(SupervisorHelperCredentials.observe(native, acquired), bootstrap.child)
            val daemon = observations.observeAncestry(bootstrap.child.identity.pid, Tid(bootstrap.child.identity.pid.value), HelperSecurityObservation.Lineage.Bootstrap)
            check(daemon.identity == bootstrap.child.identity) { "Confined daemon incarnation changed" }
            daemon.requireLineage(bootstrap.child, HelperSecurityObservation.Lineage.Bootstrap)
            val lineage = HelperSecurityObservation.Lineage.entries.single { entry ->
                plan.manifest.ancestry.canonical
                    .endsWith("lineage=${entry.wire}\n")
            }
            val parent = observations.observeAncestry(plan.binding.ownerPid, native.process.gettid(), lineage)
            check(
                parent.identity == bootstrap.parent.identity &&
                    parent.identity.startTimeTicks == plan.binding.ownerStartTicks &&
                    parent.ancestry.canonical == plan.manifest.ancestry.canonical,
            ) { "Confined parent ancestry differs" }
            parent.requireLineage(bootstrap.parent, lineage)
        } catch (failure: Exception) {
            throw ConfinedHelperRejection.classify(failure, ConfinedRejectReason.Ancestry)
        }
    }

    private fun expect(
        expected: ConfinedHelperProtocol.Frame,
        deadline: ConfinedDeadline,
    ) {
        val received = requireNotNull(channel).receiveFrame(deadline).frame
        check(received.binding == expected.binding && received.deadline == expected.deadline) { "Confined parent response binding or deadline differs" }
        if (received is ConfinedHelperProtocol.Frame.Rejected) throw ConfinedHelperRejection(received.reason, received.errno)
        check(received == expected) { "Unexpected confined parent response" }
    }

    private fun closeTransfer() {
        transfer?.close()
        transfer = null
    }

    private fun disconnect() {
        channel = null
        socket?.let { SupervisorOwnedFd(it, native.fileSystem).close() }
        socket = null
    }
}

/** Owns the Live validation socket without interpreting Legacy validation requests. */
private class LiveInstallationLifetime(
    private val owner: Thread,
    private val native: NativeEngine,
    private val receipt: LiveValidationTransport?,
) : AutoCloseable {
    constructor(owner: Thread, native: NativeEngine) : this(owner, native, null)

    private companion object {
        const val RETIRE_TIMEOUT_SECONDS = 5L
    }

    private val stopped = AtomicBoolean(false)
    private val completed = CountDownLatch(1)
    private var failure: Throwable? = null
    private var watcher: Thread? = null

    @Volatile private var reactor: Thread? = null

    @Volatile private var endpoint: io.mazewall.enforcer.internal.execution.LiveObservationEndpoint? = null

    @Synchronized
    fun requireReusable() {
        check(!stopped.get() && failure == null) { "Permanent Live supervisor installation failed or retired; use a new platform thread" }
    }

    @Synchronized
    fun start() {
        watcher = Thread({
            try {
                owner.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                close()
            }
        }, "live-supervisor-owner-${owner.threadId()}").apply {
            isDaemon = true
            start()
        }
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // One socket rollback preserves every failure and interrupted status.
    fun attach(
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        ready: CountDownLatch,
    ) {
        var receiver: ReceiverRetirement? = null
        var thread: Thread? = null
        try {
            receiver = ReceiverRetirement()
            receipt?.registerReceiver(receiver, socket)
            thread = Thread({ runReceiver(socket, ready) }, "live-validation-listener-${socket.value}").apply { isDaemon = true }
            reactor = thread
            thread.start()
            receiver.started(thread)
        } catch (problem: Throwable) {
            var retained = problem
            val started = thread != null && thread.state != Thread.State.NEW
            if (!started) {
                try {
                    native.fileSystem.close(socket).getOrThrow("close failed Live listener startup")
                } catch (cleanup: Throwable) {
                    if (cleanup is InterruptedException) Thread.currentThread().interrupt()
                    retained = CleanupFailures.merge(retained, cleanup)
                }
            }
            if (problem is InterruptedException) Thread.currentThread().interrupt()
            try {
                fail(retained)
            } finally {
                ready.countDown()
                if (started) {
                    checkNotNull(receiver).started(checkNotNull(thread))
                } else {
                    completed.countDown()
                    receiver?.unstartedClosed()
                }
            }
            throw retained
        }
    }

    @Suppress("TooGenericExceptionCaught") // The receiver retains all failures before publishing completion.
    private fun runReceiver(
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        ready: CountDownLatch,
    ) {
        var problem: Throwable? = null
        try {
            val channel = ConfinedHelperChannel(native, socket, { false }, { true })
            val ackDeadline = ConfinedDeadline.mint(native) { TimeUnit.SECONDS.toNanos(5) }
            val ack = channel.receive(1, ackDeadline).single().toInt() and 0xff
            check(SupervisorValidationDecoder.handshake(ack) == SupervisorValidationDecodeResult.HandshakeAccepted) { "Invalid Live listener acknowledgement" }
            val installed = receipt?.install(socket)
            endpoint = installed
            if (stopped.get()) installed?.revoke()
            ready.countDown()
            var sequence = 0L
            while (!stopped.get()) {
                val idleCap = ConfinedDeadline.mint(native) { TimeUnit.SECONDS.toNanos(1) }
                if (!channel.pollObservationInput(idleCap)) continue
                // mint clamps this once to the existing maximum; the offered deadline can only shorten it.
                val cap = ConfinedDeadline.mint(native) { Long.MAX_VALUE }
                val prefix = channel.receive(LiveObservationProtocol.PREFIX_BYTES, cap)
                val deadline = cap.accept(LiveObservationProtocol.deadline(prefix), ConfinedDeadline.now(native))
                val request = prefix + channel.receive(LiveObservationProtocol.REQUEST_BYTES - prefix.size, deadline)
                sequence = LiveObservationProtocol.validate(request, sequence)
                val matched = endpoint?.observe(LiveObservationProtocol.tid(request)) ?: false
                channel.send(LiveObservationProtocol.response(request, matched), deadline)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            problem = interrupted
        } catch (caught: Throwable) {
            problem = caught
        } finally {
            try {
                endpoint?.revoke()
            } catch (cleanup: Throwable) {
                problem = CleanupFailures.merge(problem, cleanup)
            }
            try {
                native.fileSystem.close(socket).getOrThrow("close Live validation socket")
            } catch (cleanup: Throwable) {
                problem = CleanupFailures.merge(problem, cleanup)
            }
            problem?.let(::fail)
            ready.countDown()
            completed.countDown()
        }
    }

    private fun requireNotReceiverThread() {
        check(Thread.currentThread() !== reactor) { "Live listener cannot await its own retirement" }
    }

    private inner class ReceiverRetirement : LiveReceiverRetirement {
        private val resolved = CountDownLatch(1)

        @Volatile private var started: Thread? = null

        fun started(thread: Thread) {
            started = thread
            resolved.countDown()
        }

        fun unstartedClosed() = resolved.countDown()

        override fun physicallyExited(): Boolean = resolved.count == 0L && started?.isAlive != true

        override fun failure(): Throwable? = synchronized(this@LiveInstallationLifetime) { failure }

        override fun awaitPhysicalExit() {
            requireNotReceiverThread()
            var interrupted = false
            try {
                while (true) {
                    try {
                        resolved.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                val thread = started ?: return
                check(Thread.currentThread() !== thread) { "Live listener cannot await its own retirement" }
                while (thread.isAlive) {
                    try {
                        thread.join()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    fun fail(cause: Throwable) {
        stopped.set(true)
        val cleanup = runCatching { endpoint?.revoke() }.exceptionOrNull()
        if (cleanup is InterruptedException) Thread.currentThread().interrupt()
        val retained = if (cleanup == null) cause else CleanupFailures.merge(cause, cleanup)
        synchronized(this) { failure = CleanupFailures.merge(failure, retained) }
    }

    fun closeAndAwait() {
        close()
        check(Thread.currentThread() !== reactor) { "Live listener cannot await its own retirement" }
        SocketCleanup.await(TimeUnit.SECONDS.toNanos(RETIRE_TIMEOUT_SECONDS)) { completed.await(it, TimeUnit.NANOSECONDS) }
        synchronized(this) { failure }?.let { throw it }
    }

    override fun close() {
        stopped.set(true)
        try {
            endpoint?.revoke()
        } finally {
            synchronized(this) { watcher }?.interrupt()
        }
    }
}

internal class JVMValidationListener(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val scopingPolicy: StacktraceScopingPolicy,
    private val runtime: SupervisorRuntimeContext = SupervisorRuntimeContext(),
    private val native: NativeEngine,
    private val onUnexpectedTermination: (Throwable) -> Unit = {},
    private val launch: (Thread) -> Unit = Thread::start,
) {
    private val closed = AtomicBoolean(false)
    private val activeChannel = AtomicReference<SupervisorValidationChannel?>()
    private val logger = Logger.getLogger(JVMValidationListener::class.java.name)

    companion object {
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()

        private const val SLOW_INSPECT_THRESHOLD_MS = 500L
        private const val SLOW_AUTH_THRESHOLD_MS = 500L
        private const val SLOW_TOTAL_THRESHOLD_MS = 1000L
    }

    @Suppress("TooGenericExceptionCaught") // The listener owns the socket on entry, including failed startup.
    fun start(readyLatch: CountDownLatch) {
        var ownedChannel: SupervisorValidationChannel? = null
        try {
            val channel = SupervisorValidationChannel(socketFd, native)
            ownedChannel = channel
            activeChannel.set(channel)
            if (closed.get()) channel.requestStop()

            launch(
                Thread {
                    runValidationReactor(channel, readyLatch)
                }.apply {
                    isDaemon = true
                    name = "supervisor-validation-listener-${socketFd.value}"
                },
            )
        } catch (failure: Throwable) {
            try {
                if (ownedChannel == null) native.fileSystem.close(socketFd).getOrThrow("close failed validation startup") else ownedChannel.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun requestStop() {
        closed.set(true)
        activeChannel.get()?.requestStop()
    }

    private fun runValidationReactor(
        channel: SupervisorValidationChannel,
        readyLatch: CountDownLatch,
    ) {
        System.err.println("[JVM-VALIDATION] validation reactor thread started")
        var failure: Throwable? = null
        try {
            val dis = DataInputStream(BufferedInputStream(channel.inputStream))
            if (!acceptHandshake(dis, readyLatch)) return

            while (!closed.get()) {
                val request = readValidationRequest(dis) ?: break
                processValidationRequest(dis, channel, request)
            }
        } catch (expectedStop: java.io.IOException) {
            if (!closed.get()) failure = expectedStop
        } catch (expectedStop: IllegalStateException) {
            if (!closed.get()) failure = expectedStop
        } finally {
            try {
                try {
                    runtime.close()
                } finally {
                    channel.close()
                }
            } finally {
                try {
                    if (!closed.get()) {
                        onUnexpectedTermination(failure ?: IllegalStateException("validation reactor exited unexpectedly"))
                    }
                } finally {
                    // An invalid ACK or startup failure releases the installer only after
                    // the installation lifetime has observed the listener's failed health.
                    readyLatch.countDown()
                }
            }
        }
    }

    private data class ValidationRequest(
        val id: Long,
        val pid: Int,
        val arch: Int,
        val parentPid: Int,
        val syscall: Int,
        val arguments: List<Any>,
    )

    private fun readValidationRequest(dis: DataInputStream): ValidationRequest? {
        val id = try {
            dis.readLong()
        } catch (_: java.io.EOFException) {
            return null
        }
        val pid = dis.readInt()
        val arch = dis.readInt()
        val parentPid = dis.readInt()
        val syscall = dis.readInt()
        val arguments = when (val decoded = SupervisorValidationDecoder.arguments(dis, dis.readInt())) {
            is SupervisorValidationDecodeResult.Arguments -> decoded.values
            is SupervisorValidationDecodeResult.ProtocolViolation -> {
                logger.warning(decoded.reason)
                return null
            }
            SupervisorValidationDecodeResult.HandshakeAccepted -> error("argument decoder accepted a handshake")
        }
        return ValidationRequest(id, pid, arch, parentPid, syscall, arguments)
    }

    private fun processValidationRequest(
        dis: DataInputStream,
        channel: SupervisorValidationChannel,
        request: ValidationRequest,
    ) {
        System.err.println("[JVM-VALIDATION] Received request: id=${request.id}, pid=${request.pid}, arch=${request.arch}, ppid=${request.parentPid}, nr=${request.syscall}, args=${request.arguments}")
        val traceeArch = Arch.fromAudit(request.arch)
        val syscallResolution = SupervisorSyscallResolver.resolve(request.syscall, traceeArch)
        val startMs = System.currentTimeMillis()
        val targetThread = runtime.resolve(Tid(request.pid))
        val inspection = inspectWithSpawnAuthorization(request.syscall, request.arguments, targetThread, request.parentPid, traceeArch)
        logSlowInspection(startMs, request)
        val isAllowed = inspection.spawnContextAccepted &&
            authorizeValidation(inspection.state, syscallResolution, request.pid, request.syscall, traceeArch)
        logSlowValidation(startMs, request.syscall)
        sendValidationResponse(dis, channel, request.id, request.syscall, request.arguments, request.pid, traceeArch, syscallResolution, isAllowed)
    }

    private fun logSlowInspection(
        startMs: Long,
        request: ValidationRequest,
    ) {
        val elapsed = System.currentTimeMillis() - startMs
        if (elapsed > SLOW_INSPECT_THRESHOLD_MS) {
            logger.warning("[SUPERVISOR-DIAGNOSTIC] JvmStackInspector.inspect took ${elapsed}ms for syscall nr=${request.syscall}, args=${request.arguments}")
        }
    }

    private fun logSlowValidation(
        startMs: Long,
        syscall: Int,
    ) {
        val elapsed = System.currentTimeMillis() - startMs
        if (elapsed > SLOW_TOTAL_THRESHOLD_MS) {
            logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM Validation total processing took ${elapsed}ms for syscall nr=$syscall")
        }
    }

    private fun acceptHandshake(
        dis: DataInputStream,
        readyLatch: CountDownLatch,
    ): Boolean {
            // Read handshake ACK
            System.err.println("[JVM-VALIDATION] reading handshake ACK...")
            val ack = dis.readUnsignedByte()
            System.err.println("[JVM-VALIDATION] handshake ACK received: $ack")
            when (val handshake = SupervisorValidationDecoder.handshake(ack)) {
                SupervisorValidationDecodeResult.HandshakeAccepted -> Unit
                is SupervisorValidationDecodeResult.ProtocolViolation -> {
                    logger.warning(handshake.reason)
                    return false
                }
                is SupervisorValidationDecodeResult.Arguments -> error("handshake decoder returned arguments")
            }
        readyLatch.countDown()
        return true
    }

    private data class ValidationInspection(
        val state: ScopingValidationState,
        val spawnContextAccepted: Boolean,
    )

    private fun inspectWithSpawnAuthorization(
        nr: Int,
        argsList: List<Any>,
        targetThread: Thread?,
        ppidVal: Int,
        traceeArch: Arch,
    ): ValidationInspection {
        var validationState = JvmStackInspector.inspect(nr, argsList, targetThread)
        var spawnContextAccepted = true

        // --- STACKTRACE PROPAGATION LOGIC ---
        if (nr == traceeArch.execve || nr == traceeArch.execveat) {
            if (validationState is ScopingValidationState.SafeToValidate && validationState.rawStack.isEmpty()) {
                // Empty stack trace on execve suggests a child process.
                // Consume this session's authorization for the parent's exact process lifetime.
                when (val authorization = runtime.consumeSpawn(Pid(ppidVal))) {
                    is SpawnAuthorizationResult.Authorized -> {
                        validationState = ScopingValidationState.SafeToValidate.create(authorization.stack.toTypedArray(), nr, argsList)
                    }
                    is SpawnAuthorizationResult.Rejected -> {
                        logger.warning("Child exec authorization rejected for parent pid=$ppidVal: ${authorization.reason}")
                        spawnContextAccepted = false
                    }
                }
            }
        }
        return ValidationInspection(validationState, spawnContextAccepted)
    }

    private fun authorizeValidation(
        validationState: ScopingValidationState,
        syscallResolution: SupervisorSyscallResolution,
        pidVal: Int,
        nr: Int,
        traceeArch: Arch,
    ): Boolean =
        when (validationState) {
            is ScopingValidationState.SafeToValidate -> {
                val stackTrace = validationState.rawStack.toList()
                when (syscallResolution) {
                    is SupervisorSyscallResolution.Known -> {
                        val authStartMs = System.currentTimeMillis()
                        val handler = scopingPolicy.handlers[syscallResolution.syscall]
                        val res = handler?.invoke(Tid(pidVal), validationState.argsList, stackTrace) ?: true
                        val authMs = System.currentTimeMillis() - authStartMs
                        if (authMs > SLOW_AUTH_THRESHOLD_MS) {
                            logger.warning("[SUPERVISOR-DIAGNOSTIC] scopingPolicy.authorize took ${authMs}ms for syscall ${syscallResolution.syscall}")
                        }
                        if (res && (nr == traceeArch.fork || nr == traceeArch.vfork || nr == traceeArch.clone)) {
                            val tgid = getTgid(pidVal)
                            stackTrace.isNotEmpty() &&
                                tgid != null &&
                                runtime.registerSpawn(Pid(tgid), stackTrace) == SpawnRegistrationResult.REGISTERED
                        } else {
                            res
                        }
                    }
                    is SupervisorSyscallResolution.Unknown -> {
                        logger.warning(
                            "[SUPERVISOR-DIAGNOSTIC] Refusing unmapped syscall " +
                                "nr=${syscallResolution.number.nr} arch=${syscallResolution.arch}",
                        )
                        false
                    }
                }
            }
        }

    private fun sendValidationResponse(
        dis: DataInputStream,
        channel: SupervisorValidationChannel,
        id: Long,
        nr: Int,
        argsList: List<Any>,
        pidVal: Int,
        traceeArch: Arch,
        syscallResolution: SupervisorSyscallResolution,
        isAllowed: Boolean,
    ) {
        val kind = io.mazewall.platform.seccomp.SupervisedKind
            .classify(
                io.mazewall.platform.lifecycle
                .SyscallNumber(nr),
                    traceeArch,
            )
        val verdict = if (syscallResolution is SupervisorSyscallResolution.Unknown || !isAllowed) {
            JvmVerdict.Deny(NativeConstants.EPERM)
        } else if (kind is io.mazewall.platform.seccomp.SupervisedKind.Accept) {
            JvmVerdict.InjectFd
        } else {
            JvmVerdict.Allow
        }

        val execPath = if (isAllowed && kind is io.mazewall.platform.seccomp.SupervisedKind.Exec) {
            resolveExecTargetPath(nr, argsList, pidVal, traceeArch)
        } else {
            null
        }
        if (execPath != null) {
            System.err.println("[JVM-VALIDATION] sending exec path to daemon: $execPath")
        }
        channel.sendResponse(id, verdict, execPath)
        if (isAllowed && (nr == traceeArch.execve || nr == traceeArch.execveat)) {
            completeParentExecRewrite(dis, channel)
        }
    }

    private fun completeParentExecRewrite(
        dis: DataInputStream,
        channel: SupervisorValidationChannel,
    ) {
        try {
            dis.readLong()
            dis.readLong()
            dis.readLong()
            dis.readLong()
            dis.readLong()
            dis.readLong()
            // Do not ptrace a task blocked in USER_NOTIF (deadlocks). CONTINUE uses the
            // original execve pathname; the parent-supplied path was opened and ADDFD'd
            // for identity. Register rewrite is not yet implemented (issue-20260817-033800),
            // so always deny exec rewrite requests.
            channel.sendExecRewriteAck(false)
        } catch (expectedRewriteFailure: Exception) {
            logger.warning("parent exec register rewrite failed: ${expectedRewriteFailure.message}")
            try {
                channel.sendExecRewriteAck(false)
            } catch (expectedDenyAckTransportFailure: Exception) {
            }
        }
    }

    private fun resolveExecTargetPath(
        nr: Int,
        argsList: List<Any>,
        pidVal: Int,
        traceeArch: Arch,
    ): String? {
        val fromDaemon = argsList.filterIsInstance<String>().firstOrNull()
        if (!fromDaemon.isNullOrEmpty() && !fromDaemon.startsWith("<YAMA_ERROR")) {
            return canonicalizeExecPath(fromDaemon)
        }
        val pathPtr = when {
            nr == traceeArch.execve && argsList.isNotEmpty() && argsList[0] is Long -> argsList[0] as Long
            nr == traceeArch.execveat && argsList.size > 1 && argsList[1] is Long -> argsList[1] as Long
            else -> return null
        }
        if (pathPtr == 0L) {
            return null
        }
        val raw = try {
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val path = SupervisorProcessMemoryReader.readString(Tid(pidVal), pathPtr, 4096)
                    path
                }
            }
        } catch (expectedTraceeReadFailure: Exception) {
            logger.warning("JVM could not read exec path from pid=$pidVal: ${expectedTraceeReadFailure.message}")
            null
        }
        return raw?.let { canonicalizeExecPath(it) }
    }

    private fun canonicalizeExecPath(path: String): String? {
        if (path.startsWith("/proc/self/fd/") || path.startsWith("/proc/thread-self/fd/")) {
            return null
        }
        if (path.startsWith("/")) {
            return path
        }
        val search = ArrayList<String>()
        System
            .getenv("PATH")
            ?.split(':')
            ?.filter { it.isNotEmpty() }
            ?.let { search.addAll(it) }
        search.add("/usr/bin")
        search.add("/bin")
        for (dir in search) {
            val candidate = java.io.File(dir, path)
            if (candidate.isFile) {
                return candidate.absolutePath
            }
        }
        return path
    }

    private fun getTgid(tid: Int): Int? =
        try {
            java.io.File("/proc/$tid/status").useLines { lines ->
                lines
                    .firstOrNull { it.startsWith("Tgid:") }
                    ?.substringAfter("Tgid:")
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}
