package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val EPERM = 1
private const val EACCES = 13

internal enum class ConfinedDelivery { Direct, Relay }

internal class ConfinedHelperRejection(
    val reason: ConfinedRejectReason,
    val errno: Int?,
    cause: Throwable? = null,
) : IOException("Confined helper rejected: $reason errno=$errno", cause) {
    companion object {
        fun nativeFailure(
            operation: String,
            errno: Int,
        ): ConfinedHelperRejection = ConfinedHelperRejection(nativeReason(errno), errno, IOException("$operation failed with errno=$errno"))

        fun classify(
            cause: Throwable,
            fallback: ConfinedRejectReason,
        ): ConfinedHelperRejection =
            when (cause) {
            is ConfinedHelperRejection -> cause
            is SupervisorCloseFailure -> ConfinedHelperRejection(nativeReason(cause.errno), cause.errno, cause)
            is java.nio.file.AccessDeniedException -> ConfinedHelperRejection(ConfinedRejectReason.Permission, EACCES, cause)
            is TimeoutException -> ConfinedHelperRejection(ConfinedRejectReason.Timeout, null, cause)
            else -> ConfinedHelperRejection(fallback, null, cause)
        }

        private fun nativeReason(errno: Int): ConfinedRejectReason = if (errno == EPERM || errno == EACCES) ConfinedRejectReason.Permission else ConfinedRejectReason.RuntimeClosure
    }
}

internal sealed interface ConfinedSetupResult {
    data class Ready(
        val binding: ConfinedBinding,
    ) : ConfinedSetupResult

    data class Rejected(
        val reason: ConfinedRejectReason,
        val errno: Int? = null,
        val cause: Throwable? = null,
    ) : ConfinedSetupResult
}

internal sealed interface ConfinedStage {
    data class SetupAccepted(
        val binding: ConfinedBinding,
    ) : ConfinedStage

    data class RootAdopted(
        val binding: ConfinedBinding,
        val index: Int,
    ) : ConfinedStage

    data class Quarantined(
        val binding: ConfinedBinding,
        val id: Long,
    ) : ConfinedStage

    data class Forwarded(
        val binding: ConfinedBinding,
        val id: Long,
    ) : ConfinedStage

    data class UpstreamAccepted(
        val binding: ConfinedBinding,
        val id: Long,
    ) : ConfinedStage

    data class IntermediateCommitted(
        val binding: ConfinedBinding,
        val id: Long,
    ) : ConfinedStage

    data class Done(
        val binding: ConfinedBinding,
        val id: Long,
    ) : ConfinedStage

    data class Denied(
        val binding: ConfinedBinding,
        val id: Long,
        val errno: Int,
    ) : ConfinedStage

    data class Failed(
        val cause: Throwable,
    ) : ConfinedStage
}

/** Heap-only staged lease; the existing pool worker is its sole helper I/O owner. */
internal class ConfinedHelperJob(
    val manifest: ConfinedManifest,
    val delivery: ConfinedDelivery,
    initialDeadline: ConfinedDeadline? = null,
) {
    sealed interface Command {
        data class BeginSetup(
            val manifest: ConfinedManifest,
            val deadline: ConfinedDeadline,
        ) : Command

        data class OfferRoot(
            val index: Int,
            val owner: SupervisorOwnedFd,
            val deadline: ConfinedDeadline,
        ) : Command

        data class FinishSetup(
            val count: Int,
            val deadline: ConfinedDeadline,
        ) : Command

        data class BeginRead(
            val request: ConfinedReadRequest,
            val deadline: ConfinedDeadline,
        ) : Command

        data class ResultForwarded(
            val id: Long,
            val deadline: ConfinedDeadline,
        ) : Command

        data class UpstreamReceived(
            val id: Long,
            val deadline: ConfinedDeadline,
        ) : Command

        data class IntermediateClosed(
            val id: Long,
            val deadline: ConfinedDeadline,
        ) : Command

        data class Cancel(
            val cause: Throwable,
        ) : Command
    }

    class Ticket internal constructor(
        val command: Command,
        val read: ConfinedReadExchange? = null,
    ) {
        val reply = read?.stage ?: CompletableFuture<ConfinedStage>()
    }

    private enum class RelayCommit { Unclaimed, Claimed, Forwarded, UpstreamAccepted, IntermediateCommitted }

    private class Claim(
        val id: Long,
        val handle: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    ) {
        val quiesced = CountDownLatch(1)
    }

    private val gate = Any()
    private var state: ConfinedHelperState = ConfinedHelperState.Unsealed
    private var setupDeadline = initialDeadline
    private var mailbox: Ticket? = null
    private var current: Ticket? = null
    private val reads = ArrayDeque<Ticket>(2)
    private var active: ConfinedReadExchange? = null
    private var lastAdmitted = 0L
    private var quarantine: SupervisorOwnedFd? = null
    private var claim: Claim? = null
    private var relayCommit = RelayCommit.Unclaimed
    private var terminal: Throwable? = null
    private var ioEnded = false
    private val ioQuiesced = CountDownLatch(1)

    val setup = CompletableFuture<ConfinedSetupResult>()

    /** OfferRoot consumes its owner even when admission refuses it. No native effect runs under gate. */
    fun submit(command: Command): CompletableFuture<ConfinedStage> {
        if (command is Command.BeginRead) return beginRead(command.request, command.deadline).stage
        if (command is Command.Cancel) {
            cancel(command.cause)
            return CompletableFuture.completedFuture(ConfinedStage.Failed(command.cause))
        }
        val ticket = Ticket(command)
        val rejected = synchronized(gate) {
            terminal ?: if (mailbox != null || ioEnded) {
                RejectedExecutionException("Confined stage mailbox unavailable")
            } else {
                mailbox = ticket
                null
            }
        }
        if (rejected != null) {
            val cleanup = SocketCleanup(rejected)
            if (command is Command.OfferRoot) cleanup.attempt(command.owner::close)
            ticket.reply.complete(ConfinedStage.Failed(requireNotNull(cleanup.failure)))
        }
        return ticket.reply
    }

    fun beginRead(
        request: ConfinedReadRequest,
        deadline: ConfinedDeadline,
    ): ConfinedReadExchange {
        val exchange = ConfinedReadExchange(request, deadline)
        val rejected = synchronized(gate) {
            terminal ?: when {
                state !is ConfinedHelperState.Ready || request.binding != manifest.binding || request.id <= lastAdmitted ->
                    IllegalStateException("Confined read has no matching ready generation or fresh identity")
                reads.size + (if (active == null) 0 else 1) >= 2 -> RejectedExecutionException("Confined read queue is full")
                else -> {
                    lastAdmitted = request.id
                    reads.addLast(Ticket(Command.BeginRead(request, deadline), exchange))
                    null
                }
            }
        }
        if (rejected != null) fail(exchange, rejected)
        return exchange
    }

    /** Invoked by the sole I/O loop, including idle steps while an earlier read awaits a relay. */
    fun poll(now: Long): Ticket? {
        tick(now)
        return synchronized(gate) {
            if (terminal != null || ioEnded || current != null) return@synchronized null
            val ticket = mailbox?.also { mailbox = null } ?: if (active == null && claim?.quiesced?.count != 1L) reads.removeFirstOrNull() else null
            if (ticket == null) return@synchronized null
            current = ticket
            if (requireNotNull(ticket.command.deadline()).nanos <= now) throw TimeoutException("Confined command expired")
            admit(ticket)
            ticket
        }
    }

    /** Also called at transport poll boundaries; expires queued work without taking a stage. */
    fun tick(now: Long) {
        val expired = synchronized(gate) {
            reads.filter { requireNotNull(it.read).deadline.nanos <= now }.also { reads.removeAll(it.toSet()) }
        }
        expired.forEach { fail(requireNotNull(it.read), TimeoutException("Queued confined read expired")) }
        synchronized(gate) {
            if (terminal != null || ioEnded) return
            val inFlight = current?.command?.deadline() ?: active?.deadline ?: setupDeadline
            if (inFlight != null && inFlight.nanos <= now) {
                terminal = TimeoutException("Confined operation expired")
            }
        }
    }

    fun acknowledge(
        ticket: Ticket,
        stage: ConfinedStage,
    ) {
        synchronized(gate) {
            requireCurrent(ticket)
            val binding = manifest.binding
            when (val command = ticket.command) {
                is Command.BeginSetup -> {
                    check(stage == ConfinedStage.SetupAccepted(binding))
                    step(ConfinedHelperEvent.SetupValidated(binding, command.deadline))
                }
                is Command.OfferRoot -> {
                    check(stage == ConfinedStage.RootAdopted(binding, command.index))
                    check(!command.owner.handle.isLiveForIo()) { "Root receipt requires committed intermediate close" }
                    step(ConfinedHelperEvent.RootAdopted(binding, command.index, command.deadline))
                }
                is Command.ResultForwarded -> {
                    requireRead(command.id, command.deadline)
                    check(stage == ConfinedStage.Forwarded(binding, command.id) && relayCommit == RelayCommit.Claimed)
                    relayCommit = RelayCommit.Forwarded
                }
                is Command.UpstreamReceived -> {
                    requireRead(command.id, command.deadline)
                    check(stage == ConfinedStage.UpstreamAccepted(binding, command.id) && relayCommit == RelayCommit.Forwarded)
                    relayCommit = RelayCommit.UpstreamAccepted
                }
                is Command.IntermediateClosed -> {
                    requireRead(command.id, command.deadline)
                    check(stage == ConfinedStage.IntermediateCommitted(binding, command.id) && relayCommit == RelayCommit.UpstreamAccepted)
                    check(claim?.handle?.isLiveForIo() == false) { "Relay intermediate still live" }
                    relayCommit = RelayCommit.IntermediateCommitted
                }
                is Command.BeginRead, is Command.FinishSetup, is Command.Cancel -> error("Wrong confined completion kind")
            }
            current = null
        }
        ticket.reply.complete(stage)
    }

    fun ready(ticket: Ticket) {
        synchronized(gate) {
            requireCurrent(ticket)
            val command = ticket.command as? Command.FinishSetup ?: error("Not completing confined setup")
            step(ConfinedHelperEvent.Sealed(manifest.binding, command.deadline))
            setupDeadline = null
            current = null
        }
        setup.complete(ConfinedSetupResult.Ready(manifest.binding))
    }

    /** Ownership transfers on entry; failed/late receipt is disposed by this I/O owner. */
    fun quarantine(
        ticket: Ticket,
        owner: SupervisorOwnedFd,
    ) {
        var adopted = false
        try {
            val read = synchronized(gate) {
                requireCurrent(ticket)
                val read = requireNotNull(ticket.read)
                requireRead(read.request.id, read.deadline)
                check(quarantine == null)
                step(ConfinedHelperEvent.Opened(manifest.binding, read.request.id, read.deadline))
                step(ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Result(ConfinedHelperProtocol.ResultTag.Expected, manifest.binding, read.request.id, read.deadline)))
                step(ConfinedHelperEvent.ResultSent(manifest.binding, read.request.id, read.deadline))
                quarantine = owner
                adopted = true
                current = null
                read
            }
            read.stage.complete(ConfinedStage.Quarantined(manifest.binding, read.request.id))
        } finally {
            if (!adopted) owner.close()
        }
    }

    fun commitResult(
        id: Long,
        deadline: ConfinedDeadline,
    ) = synchronized(gate) {
        requireRead(id, deadline)
        when (delivery) {
            ConfinedDelivery.Direct -> {
                checkNotNull(quarantine) { "Check failed." }
            }
            ConfinedDelivery.Relay -> check(relayCommit == RelayCommit.IntermediateCommitted)
        }
        step(ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Result(ConfinedHelperProtocol.ResultTag.Received, manifest.binding, id, deadline)))
    }

    fun done(
        id: Long,
        deadline: ConfinedDeadline,
    ) {
        val (read, owner) = synchronized(gate) {
            val read = requireRead(id, deadline)
            step(ConfinedHelperEvent.ReadCompleted(manifest.binding, id, deadline))
            val owner = quarantine
            quarantine = null
            active = null
            read to owner
        }
        when (delivery) {
            ConfinedDelivery.Direct -> {
                val descriptor = requireNotNull(owner)
                if (!read.result.complete(SupervisorDescriptorResult.Opened(descriptor))) descriptor.close()
            }
            ConfinedDelivery.Relay -> check(owner == null)
        }
        read.completed.complete(ConfinedStage.Done(manifest.binding, id))
    }

    fun denied(
        ticket: Ticket,
        errno: Int,
    ) {
        val read = synchronized(gate) {
            requireCurrent(ticket)
            val read = requireNotNull(ticket.read)
            step(ConfinedHelperEvent.Denied(manifest.binding, read.request.id, errno, read.deadline))
            active = null
            current = null
            read
        }
        val denial = ConfinedStage.Denied(manifest.binding, read.request.id, errno)
        read.stage.complete(denial)
        read.completed.complete(denial)
        if (delivery == ConfinedDelivery.Direct) read.result.complete(SupervisorDescriptorResult.Denied(errno))
    }

    fun cancel(cause: Throwable) =
        synchronized(gate) {
        if (terminal == null || cause is Error) terminal = CleanupFailures.merge(terminal, cause)
    }

    /** Called only after the actual helper I/O loop stops, never by a concurrent canceller. */
    fun quiesce() {
        val disposition = synchronized(gate) {
            if (ioEnded) return
            ioEnded = true
            val cause = terminal ?: IllegalStateException("Confined I/O loop ended").also { terminal = it }
            val tickets = listOfNotNull(current, mailbox) + reads
            current = null
            mailbox = null
            reads.clear()
            val read = active
            active = null
            val owner = quarantine
            quarantine = null
            Disposition(tickets, read, owner, cause)
        }
        val (tickets, read, owner) = disposition
        val cause = disposition.cause
        val cleanup = SocketCleanup(cause)
        try {
            tickets.forEach { ticket -> (ticket.command as? Command.OfferRoot)?.owner?.let { cleanup.attempt(it::close) } }
            owner?.let { cleanup.attempt(it::close) }
            val failure = requireNotNull(cleanup.failure)
            tickets.forEach { ticket ->
                ticket.reply.complete(ConfinedStage.Failed(failure))
                ticket.read?.let { fail(it, failure) }
            }
            read?.let { fail(it, failure) }
            val rejection = ConfinedHelperRejection.classify(cause, ConfinedRejectReason.Stopped)
            setup.complete(ConfinedSetupResult.Rejected(rejection.reason, rejection.errno, failure))
        } finally {
            ioQuiesced.countDown()
        }
        cleanup.failure?.let { if (it is Error) throw it }
    }

    fun claimQuarantined(id: Long): SupervisorOwnedFd? =
        synchronized(gate) {
        if (delivery != ConfinedDelivery.Relay || terminal != null || active?.request?.id != id || relayCommit != RelayCommit.Unclaimed) return@synchronized null
        val owner = quarantine ?: return@synchronized null
        check(claim?.quiesced?.count != 1L)
        quarantine = null
        claim = Claim(id, owner.handle)
        relayCommit = RelayCommit.Claimed
        owner
    }

    /** The claimant calls this only after its native send loop and local disposition end. */
    fun releaseClaim(
        id: Long,
        failure: Throwable? = null,
    ) {
        val owner = synchronized(gate) {
            val owner = requireNotNull(claim)
            check(owner.id == id && !owner.handle.isLiveForIo()) { "Claimed intermediate has not been disposed" }
            if (failure != null && terminal == null) terminal = failure
            owner
        }
        owner.quiesced.countDown()
    }

    fun awaitQuiescence(remainingNanos: () -> Long) {
        if (ioQuiesced.count != 0L) SocketCleanup.await(remainingNanos()) { ioQuiesced.await(it, TimeUnit.NANOSECONDS) }
        val owner = synchronized(gate) { claim }
        if (owner != null && owner.quiesced.count != 0L) SocketCleanup.await(remainingNanos()) { owner.quiesced.await(it, TimeUnit.NANOSECONDS) }
    }

    val cancelled: Boolean get() = synchronized(gate) { terminal != null }

    val operationDeadline: ConfinedDeadline? get() = synchronized(gate) { current?.command?.deadline() ?: active?.deadline ?: setupDeadline }

    fun checkActive(now: Long) {
        tick(now)
        synchronized(gate) { terminal?.let { throw it } }
    }

    private data class Disposition(
        val tickets: List<Ticket>,
        val read: ConfinedReadExchange?,
        val owner: SupervisorOwnedFd?,
        val cause: Throwable,
    )

    private fun admit(ticket: Ticket) {
        val binding = manifest.binding
        when (val command = ticket.command) {
            is Command.BeginSetup -> {
                check(command.manifest.binding == binding)
                check(setupDeadline == null || setupDeadline == command.deadline) { "Confined setup deadline changed after reservation" }
                step(ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Setup(command.manifest, command.deadline)))
                setupDeadline = command.deadline
            }
            is Command.OfferRoot -> step(ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Offer, binding, command.index, command.deadline)))
            is Command.FinishSetup -> step(ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Complete, binding, command.count, command.deadline)))
            is Command.BeginRead -> {
                step(ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Read(command.request, command.deadline)))
                active = requireNotNull(ticket.read)
                relayCommit = RelayCommit.Unclaimed
            }
            is Command.ResultForwarded -> {
                requireRead(command.id, command.deadline)
                check(delivery == ConfinedDelivery.Relay && relayCommit == RelayCommit.Claimed)
            }
            is Command.UpstreamReceived -> {
                requireRead(command.id, command.deadline)
                check(delivery == ConfinedDelivery.Relay && relayCommit == RelayCommit.Forwarded)
            }
            is Command.IntermediateClosed -> {
                requireRead(command.id, command.deadline)
                check(delivery == ConfinedDelivery.Relay && relayCommit == RelayCommit.UpstreamAccepted && claim?.handle?.isLiveForIo() == false)
            }
            is Command.Cancel -> error("Cancellation is terminal admission, not queued I/O")
        }
    }

    private fun step(event: ConfinedHelperEvent) {
        val next = ConfinedHelperMachine.evaluate(state, event)
        if (next.state is ConfinedHelperState.Terminal) throw requireNotNull(next.state.cause)
        state = next.state
    }

    private fun requireCurrent(ticket: Ticket) {
        terminal?.let { throw it }
        check(!ioEnded && current === ticket) { "Wrong confined stage owner" }
    }

    private fun requireRead(
        id: Long,
        deadline: ConfinedDeadline,
    ): ConfinedReadExchange {
        terminal?.let { throw it }
        return requireNotNull(active).also { check(it.request.id == id && it.deadline == deadline) { "Confined read identity or deadline mismatch" } }
    }

    private fun fail(
        read: ConfinedReadExchange,
        failure: Throwable,
    ) {
        read.stage.complete(ConfinedStage.Failed(failure))
        read.completed.complete(ConfinedStage.Failed(failure))
        if (delivery == ConfinedDelivery.Direct) read.result.completeExceptionally(failure)
    }

    private fun Command.deadline(): ConfinedDeadline? =
        when (this) {
        is Command.BeginSetup -> deadline
        is Command.OfferRoot -> deadline
        is Command.FinishSetup -> deadline
        is Command.BeginRead -> deadline
        is Command.ResultForwarded -> deadline
        is Command.UpstreamReceived -> deadline
        is Command.IntermediateClosed -> deadline
        is Command.Cancel -> null
    }
}

internal class ConfinedReadExchange internal constructor(
    val request: ConfinedReadRequest,
    val deadline: ConfinedDeadline,
) {
    val stage = CompletableFuture<ConfinedStage>()
    val completed = CompletableFuture<ConfinedStage>()
    val result = CompletableFuture<SupervisorDescriptorResult>()
}
