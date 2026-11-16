package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.internal.execution.HelperBinding
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannel
import io.mazewall.ffi.networking.SupervisorHelperCredentials
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.seccomp.daemon.LiveListenerAdmission
import io.mazewall.platform.seccomp.daemon.PreludeOutcome
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import java.nio.ByteBuffer
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

private const val CONTROL_PRELUDE = 0xe4
private const val LIVE_PRELUDE = 0xe5
private const val LIVE_PRELUDE_BYTES = 148
private const val LIVE_ATTEMPT_LIMIT = 200
private const val LIVE_DIGEST_BYTES = 32
private const val CONVERSATION_POLL_NANOS = 25_000_000L

/** Private control adapter; the existing accepted-connection owner retains its socket. */
internal class ConfinedHelperControl(
    private val pool: SocketHelperPool,
    private val bootstrap: SocketHelperProcess.ConfinedStartup,
    private val native: NativeEngine,
    private val observations: HelperSecurityObservation = HelperSecurityObservation(),
    private val stopping: () -> Boolean = { false },
    private val retryConnection: (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) -> Unit = {},
) {
    private val helperIdentity = pool.confinedProcessIdentity()
    private val gate = Any()
    private var binding: ConfinedBinding? = null
    private var conversation: Conversation? = null
    private var setupReady = false
    private val liveAttempts = LongArray(LIVE_ATTEMPT_LIMIT)
    private var liveAttemptCount = 0
    private val liveAdmissions = ArrayList<LiveAdmission>(LIVE_ATTEMPT_LIMIT)
    private val reason = AtomicReference<StopReason?>()
    private val retirement = ReentrantLock()
    private val parentPid = bootstrap.parent.identity.pid.value
        .toLong()
    private val parent = ProcessHandle
        .of(parentPid)
        .orElseThrow()

    private class Conversation(
        val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) {
        val cancelled = AtomicBoolean()
        val ended = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val failureReported = AtomicBoolean()
    }

    internal inner class LiveAdmission(
        private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) : LiveListenerAdmission {
        private var active = true
        private var attached = false
        private var inFlight = false
        private var sequence = 0L

        fun attach(candidate: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
            synchronized(gate) {
                check(active && setupReady && reason.get() == null && socket === candidate && !attached) { "Live listener admission is no longer current" }
                attached = true
            }
        }

        fun begin(candidate: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): Long? =
            synchronized(gate) {
                val sessionCurrent = active && attached && setupReady && reason.get() == null
                if (!sessionCurrent || socket !== candidate || inFlight || sequence == Long.MAX_VALUE) {
                    active = false
                    return@synchronized null
                }
                inFlight = true
                ++sequence
            }

        fun finish(
            serial: Long,
            success: Boolean,
        ): Boolean =
            synchronized(gate) {
                if (!inFlight || sequence != serial) {
                    active = false
                    return@synchronized false
                }
                inFlight = false
                val accepted = success && active && attached && setupReady && reason.get() == null
                if (!accepted) active = false
                accepted
            }

        override fun close() {
            synchronized(gate) { active = false }
        }
    }

    private fun revokeLiveAdmissions() {
        setupReady = false
        liveAdmissions.forEach { it.close() }
    }

    fun connectionClosed(
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        outcome: SeccompCleanupOutcome,
    ) {
        val owner = synchronized(gate) { conversation?.takeIf { it.socket === socket } } ?: return
        when (outcome) {
            SeccompCleanupOutcome.Complete -> owner.closed.countDown()
            is SeccompCleanupOutcome.Incomplete -> {
                val cleanup = SocketCleanup()
                outcome.failures.forEach { failure -> cleanup.attempt { throw failure } }
                owner.closeFailure.compareAndSet(null, requireNotNull(cleanup.failure))
                if (outcome.retryableOwnership.isEmpty()) owner.closed.countDown()
            }
        }
    }

    fun handle(
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        deadline: Deadline,
    ): PreludeOutcome {
        var owner: Conversation? = null
        val channel = ConfinedHelperChannel(native, socket, {
            stopping() || Thread.currentThread().isInterrupted || owner?.let { it.ended.count != 0L && it.cancelled.get() } == true
        }, parent::isAlive)
        val cap = ConfinedDeadline.mint(native) { TimeUnit.MILLISECONDS.toNanos(deadline.remainingMillis().toLong()) }
        when (channel.peekByte(cap)) {
            0 -> return PreludeOutcome.LegacyListener
            CONTROL_PRELUDE -> Unit
            LIVE_PRELUDE -> return acceptLiveListener(socket, channel, cap)
            else -> throw IllegalArgumentException("Unknown confined control prelude")
        }
        authenticate(socket)
        check(channel.receive(1, cap).single() == CONTROL_PRELUDE.toByte()) { "Confined control marker changed" }
        val received = channel.receiveFrame(cap)
        val frame = received.frame
        check(frame.binding.owner.ownerPid == bootstrap.parent.identity.pid && frame.binding.owner.ownerStartTicks == bootstrap.parent.identity.startTimeTicks) {
            "Control owner incarnation differs"
        }
        if (frame is ConfinedHelperProtocol.Frame.Setup) {
            val accepted = synchronized(gate) {
                check(binding == null) { "Confined setup is already spent" }
                binding = frame.binding
                Conversation(socket).also { conversation = it }
            }
            owner = accepted
            converse(channel, received, accepted)
        } else {
            retire(channel, received)
        }
        return PreludeOutcome.Handled
    }

    private fun acceptLiveListener(
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        channel: ConfinedHelperChannel,
        deadline: ConfinedDeadline,
    ): PreludeOutcome {
        authenticate(socket)
        check(channel.receive(1, deadline).single() == LIVE_PRELUDE.toByte()) { "Live prelude marker changed" }
        val input = ByteBuffer.wrap(channel.receive(LIVE_PRELUDE_BYTES, deadline))
        check(input.int == 1) { "Unsupported Live prelude version" }
        val pid = Pid(input.int)
        val start = input.long
        val generation = input.long

        fun digest(): String = HexFormat.of().formatHex(ByteArray(LIVE_DIGEST_BYTES).also(input::get))
        val resource = digest()
        val policy = digest()
        val manifest = digest()
        val tid = input.int
        val installation = input.long
        val worker = input.long
        val serial = input.long
        check(tid > 0 && installation > 0 && worker > 0 && serial > 0 && !input.hasRemaining()) { "Malformed Live prelude" }
        val requested = ConfinedBinding(HelperBinding(pid, start, generation, resource, policy), manifest)
        val admission = LiveAdmission(socket)
        val result = PreludeOutcome.LiveListener(admission)
        return synchronized(gate) {
            check(setupReady && conversation != null && binding == requested && reason.get() == null) { "Live setup is not active" }
            check(liveAttemptCount < LIVE_ATTEMPT_LIMIT) { "Live listener attempts exhausted" }
            for (index in 0 until liveAttemptCount) check(liveAttempts[index] != serial) { "Live listener attempt is spent" }
            liveAttempts[liveAttemptCount++] = serial
            liveAdmissions.add(admission)
            result
        }
    }

    private fun converse(
        channel: ConfinedHelperChannel,
        received: ConfinedHelperChannel.ReceivedFrame,
        owner: Conversation,
    ) {
        val frame = received.frame as ConfinedHelperProtocol.Frame.Setup
        var relay: ConfinedHelperRelay? = null
        var retirementFrame: ConfinedHelperChannel.ReceivedFrame? = null
        val cleanup = SocketCleanup()
        try {
            cleanup.attempt {
                channel.checkDeadline(received.localDeadline)
                val session = pool.reserveConfined(frame.manifest, frame.deadline)
                val active = ConfinedHelperRelay(channel, session, { manifest ->
                    authenticate(owner.socket)
                    observations.requireSetupOwner(manifest, bootstrap.parent)
                }, { capability, descriptor -> check(observations.observeRoot(descriptor.handle) == capability.identity) { "Confined root identity differs" } }, {
                    synchronized(gate) {
                        check(conversation === owner && !owner.cancelled.get() && reason.get() == null) { "Confined setup was retired" }
                        setupReady = true
                    }
                })
                relay = active
                active.accept(ConfinedRelayEvent.ParentFrame(frame, received.localDeadline))
                retirementFrame = relayFrames(channel, session, active, frame.binding)
            }
            cleanup.failure?.let { failure ->
                synchronized(gate) {
                    reason.compareAndSet(
                        null,
                        when (failure) {
                            is TimeoutException -> StopReason.Timeout
                            is CancellationException, is InterruptedException -> StopReason.Cancel
                            else -> StopReason.Failure
                        },
                    )
                    revokeLiveAdmissions()
                }
            }
            cleanup.attempt { relay?.accept(ConfinedRelayEvent.Cancel(cleanup.failure ?: CancellationException("Confined control ended"))) }
        } finally {
            synchronized(gate) { if (conversation === owner) revokeLiveAdmissions() }
            owner.ended.countDown()
        }
        cleanup.rethrow()
        retirementFrame?.let { retire(channel, it, owner) }
    }

    private fun relayFrames(
        channel: ConfinedHelperChannel,
        session: SocketHelperPool.Session,
        active: ConfinedHelperRelay,
        expected: ConfinedBinding,
    ): ConfinedHelperChannel.ReceivedFrame? {
        while (!active.terminal) {
            session.checkConfinedActive(ConfinedDeadline.now(native))
            active.accept(ConfinedRelayEvent.Tick)
            val next = channel.pollFrame(active.operationDeadline) ?: continue
            if (next.frame is ConfinedHelperProtocol.Frame.Control && next.frame.tag == ConfinedHelperProtocol.ControlTag.Retire) {
                check(next.frame.binding == expected) { "Retirement binding differs" }
                synchronized(gate) {
                    reason.compareAndSet(null, StopReason.Normal)
                    revokeLiveAdmissions()
                }
                return next
            }
            if (next.frame is ConfinedHelperProtocol.Frame.Stop) {
                synchronized(gate) {
                    reason.compareAndSet(null, next.frame.reason)
                    revokeLiveAdmissions()
                }
            }
            active.accept(ConfinedRelayEvent.ParentFrame(next.frame, next.localDeadline))
        }
        return null
    }

    private fun retire(
        channel: ConfinedHelperChannel,
        received: ConfinedHelperChannel.ReceivedFrame,
        current: Conversation? = null,
    ) {
        val frame = received.frame
        require(frame is ConfinedHelperProtocol.Frame.Control && frame.tag == ConfinedHelperProtocol.ControlTag.Retire) { "Expected setup or cleanup retirement frame" }
        val old = synchronized(gate) {
            check(binding == null || binding == frame.binding) { "Retirement cannot replace the spent binding" }
            revokeLiveAdmissions()
            binding = frame.binding
            conversation?.also {
                if (it.ended.count != 0L && it !== current) {
                    reason.compareAndSet(null, StopReason.Cancel)
                    it.cancelled.set(true)
                }
            }
        }
        reason.compareAndSet(null, StopReason.Normal)
        val remaining = received.localDeadline.remainingNanos(channel.checkDeadline(received.localDeadline))
        if (!retirement.tryLock(remaining, TimeUnit.NANOSECONDS)) throw TimeoutException("Confined retirement is already in progress")
        try {
            channel.checkDeadline(received.localDeadline)
            old?.let { awaitConversation(it, current, channel, received.localDeadline) }
            pool.close()
        } finally {
            retirement.unlock()
        }
        channel.sendFrame(ConfinedHelperProtocol.Frame.Retired(frame.binding, requireNotNull(reason.get()), helperIdentity.pid, helperIdentity.startTimeTicks, frame.deadline), received.localDeadline)
    }

    private fun awaitConversation(
        old: Conversation,
        current: Conversation?,
        channel: ConfinedHelperChannel,
        deadline: ConfinedDeadline,
    ) {
        var retried = false
        while (true) {
            val now = channel.checkDeadline(deadline)
            old.closeFailure.get()?.let { failure -> if (old.failureReported.compareAndSet(false, true)) throw failure }
            if (!retried && old.ended.count == 0L && old.closed.count != 0L && old.closeFailure.get() != null) {
                retried = true
                retryConnection(old.socket)
            }
            val pending = when {
                old.ended.count != 0L -> old.ended
                old !== current && old.closed.count != 0L -> old.closed
                else -> return
            }
            pending.await(minOf(CONVERSATION_POLL_NANOS, deadline.remainingNanos(now)), TimeUnit.NANOSECONDS)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Authentication boundary retains permission errno and original observation failures.
    private fun authenticate(socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        try {
            observations.requirePeer(SupervisorHelperCredentials.observe(native, socket), bootstrap.parent)
            val self = observations.observeAncestry(bootstrap.child.identity.pid, native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
            check(self.identity == bootstrap.child.identity) { "Daemon incarnation changed" }
            self.requireLineage(bootstrap.child, HelperSecurityObservation.Lineage.Bootstrap)
            val parent = observations.observeAncestry(bootstrap.parent.identity.pid, Tid(bootstrap.parent.identity.pid.value), HelperSecurityObservation.Lineage.Bootstrap)
            check(parent.identity == bootstrap.parent.identity) { "Parent incarnation changed" }
            if (parent.ancestry.canonical == bootstrap.parent.ancestry.canonical) {
                parent.requireLineage(bootstrap.parent, HelperSecurityObservation.Lineage.Bootstrap)
            } else {
                // The label names the expected known layer; every observed field is still checked.
                val tier1 = HelperAncestry.parse(parent.ancestry.canonical.removeSuffix("lineage=bootstrap-v1\n") + "lineage=tier1-v1\n")
                parent.copy(ancestry = tier1).requireLineage(bootstrap.parent, HelperSecurityObservation.Lineage.Tier1)
            }
        } catch (observationFailure: Exception) {
            throw ConfinedHelperRejection.classify(observationFailure, ConfinedRejectReason.Ancestry)
        }
    }
}
