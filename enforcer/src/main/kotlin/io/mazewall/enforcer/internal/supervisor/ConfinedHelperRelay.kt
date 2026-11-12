package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.Frame
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannel
import java.util.concurrent.CompletableFuture

internal sealed interface ConfinedRelayEvent {
    data class ParentFrame(
        val frame: ConfinedHelperProtocol.Frame,
        val localDeadline: ConfinedDeadline,
    ) : ConfinedRelayEvent

    data class StageCompleted(
        val stage: ConfinedStage,
    ) : ConfinedRelayEvent

    data object Tick : ConfinedRelayEvent

    data class Cancel(
        val cause: Throwable,
    ) : ConfinedRelayEvent
}

/** Runs on the existing accepted-connection owner; never reads the pool's helper socket. */
internal class ConfinedHelperRelay(
    private val channel: ConfinedHelperChannel,
    private val session: SocketHelperPool.Session,
    private val validateSetup: (ConfinedManifest) -> Unit,
    private val validateRoot: (HelperCapability, SupervisorOwnedFd) -> Unit,
) {
    private var state: ConfinedHelperState = ConfinedHelperState.Unsealed
    private var pendingStage: CompletableFuture<ConfinedStage>? = null
    private var pendingSetup: CompletableFuture<ConfinedSetupResult>? = null
    private var read: ConfinedReadExchange? = null
    private var intermediate: SupervisorOwnedFd? = null
    private var claimId: Long? = null
    private var localDeadline: ConfinedDeadline? = null
    private var pendingReceipt: Frame.Result? = null

    val operationDeadline: ConfinedDeadline? get() = localDeadline
    val terminal: Boolean get() = state is ConfinedHelperState.Terminal

    fun accept(event: ConfinedRelayEvent) {
        try {
            if (event !is ConfinedRelayEvent.Cancel) localDeadline?.let(channel::checkDeadline)
            when (event) {
                is ConfinedRelayEvent.ParentFrame -> {
                    tick()
                    val cap = ConfinedDeadline(minOf(localDeadline?.nanos ?: event.localDeadline.nanos, event.localDeadline.nanos))
                    localDeadline = cap.accept(event.frame.deadline, channel.checkDeadline(cap))
                    val frame = event.frame
                    val receipt = earlyReceipt(frame)
                    if (receipt != null) {
                        check(pendingReceipt == null) { "Duplicate pending confined receipt" }
                        // Descriptor delivery precedes the helper's asynchronous forwarding acknowledgment.
                        pendingReceipt = receipt
                    } else {
                        transition(ConfinedHelperEvent.ParentFrame(frame))
                    }
                }
                is ConfinedRelayEvent.StageCompleted -> completed(event.stage)
                ConfinedRelayEvent.Tick -> tick()
                is ConfinedRelayEvent.Cancel -> terminate(event.cause)
            }
        } catch (expectedRelayFailure: Throwable) {
            val cleanup = SocketCleanup(expectedRelayFailure)
            cleanup.attempt { terminate(expectedRelayFailure) }
            cleanup.rethrow()
        }
    }

    private fun earlyReceipt(frame: Frame): Frame.Result? {
        if (frame !is Frame.Result || frame.tag != ConfinedHelperProtocol.ResultTag.Received) return null
        val ready = state as? ConfinedHelperState.Ready
        val active = ready?.active ?: return null
        return if (active.phase == ConfinedTransfer.AwaitDescriptor && pendingStage != null && intermediate != null) {
            frame.takeIf { ready.manifest.binding == frame.binding && active.request.id == frame.id && active.deadline == frame.deadline }
        } else {
            null
        }
    }

    private fun tick() {
        pendingSetup?.takeIf { it.isDone }?.let { future ->
            pendingSetup = null
            when (val result = future.join()) {
                is ConfinedSetupResult.Ready -> {
                    val confining = state as? ConfinedHelperState.Confining ?: error("Unexpected relay READY")
                    check(result.binding == confining.manifest.binding)
                    transition(ConfinedHelperEvent.Sealed(result.binding, confining.deadline))
                }
                is ConfinedSetupResult.Rejected -> throw (result.cause ?: ConfinedHelperRejection(result.reason, result.errno))
            }
        }
        pendingStage?.takeIf { it.isDone }?.let { completed(it.join()) }
    }

    private fun completed(stage: ConfinedStage) {
        val future = requireNotNull(pendingStage) { "Unsolicited relay stage" }
        check(future.isDone && future.join() == stage) { "Relay stage has no matching completed job command" }
        pendingStage = null
        when (stage) {
            is ConfinedStage.SetupAccepted -> {
                val receiving = state as? ConfinedHelperState.Receiving ?: error("Unexpected setup acceptance")
                transition(ConfinedHelperEvent.SetupValidated(stage.binding, receiving.deadline))
            }
            is ConfinedStage.RootAdopted -> {
                val receiving = state as? ConfinedHelperState.Receiving ?: error("Unexpected root adoption")
                transition(ConfinedHelperEvent.RootAdopted(stage.binding, stage.index, receiving.deadline))
            }
            is ConfinedStage.Quarantined -> {
                val read = requireRead(stage.binding, stage.id)
                check(intermediate == null && claimId == null)
                intermediate = requireNotNull(session.claimQuarantined(stage.id)) { "Confined result claim lost" }
                claimId = stage.id
                transition(ConfinedHelperEvent.Opened(stage.binding, stage.id, read.deadline))
            }
            is ConfinedStage.Forwarded -> {
                val read = requireRead(stage.binding, stage.id)
                transition(ConfinedHelperEvent.ResultSent(stage.binding, stage.id, read.deadline))
                pendingReceipt?.let { receipt ->
                    pendingReceipt = null
                    transition(ConfinedHelperEvent.ParentFrame(receipt))
                }
            }
            is ConfinedStage.UpstreamAccepted -> {
                val read = requireRead(stage.binding, stage.id)
                requireNotNull(intermediate).close()
                pendingStage = session.intermediateClosed(stage.id, read.deadline)
            }
            is ConfinedStage.IntermediateCommitted -> pendingStage = requireRead(stage.binding, stage.id).completed
            is ConfinedStage.Done -> {
                val read = requireRead(stage.binding, stage.id)
                transition(ConfinedHelperEvent.ReadCompleted(stage.binding, stage.id, read.deadline))
                session.releaseClaim(stage.id)
                intermediate = null
                claimId = null
                this.read = null
            }
            is ConfinedStage.Denied -> {
                val read = requireRead(stage.binding, stage.id)
                transition(ConfinedHelperEvent.Denied(stage.binding, stage.id, stage.errno, read.deadline))
                this.read = null
            }
            is ConfinedStage.Failed -> throw stage.cause
        }
    }

    private fun transition(event: ConfinedHelperEvent) {
        val next = ConfinedHelperMachine.evaluate(state, event)
        state = next.state
        next.effects.forEach(::interpret)
        if (next.state is ConfinedHelperState.Ready && next.state.active == null) localDeadline = null
    }

    private fun interpret(effect: ConfinedHelperEffect) {
        val cap = requireNotNull(localDeadline)
        channel.checkDeadline(cap)
        when (effect) {
            is ConfinedHelperEffect.Send -> channel.sendFrame(effect.frame, cap)
            is ConfinedHelperEffect.Validate -> {
                validateSetup(effect.manifest)
                channel.checkDeadline(cap)
                pendingStage = session.beginSetup(effect.manifest, effect.deadline)
            }
            is ConfinedHelperEffect.ReceiveRoot -> {
                val owner = channel.receiveDescriptor(cap)
                var transferred = false
                io.mazewall.platform.fd.ResourceRetirement.runWithRollback({
                    validateRoot(effect.capability, owner)
                    channel.checkDeadline(cap)
                    transferred = true
                    pendingStage = session.offerRoot(effect.capability.index, owner, effect.deadline)
                }, { if (!transferred) owner.close() })
            }
            is ConfinedHelperEffect.Seal -> pendingSetup = session.finishSetup(effect.manifest.capabilities.size, effect.deadline)
            is ConfinedHelperEffect.Open -> {
                val exchange = session.beginRead(effect.request, effect.deadline)
                read = exchange
                pendingStage = exchange.stage
            }
            is ConfinedHelperEffect.SendResult -> {
                channel.sendDescriptor(requireNotNull(intermediate), cap)
                pendingStage = session.resultForwarded(effect.id, effect.deadline)
            }
            is ConfinedHelperEffect.CommitResult -> pendingStage = session.upstreamReceived(effect.id, effect.deadline)
            is ConfinedHelperEffect.Stop -> terminate(java.util.concurrent.CancellationException("Confined parent requested stop: ${effect.reason}"))
            is ConfinedHelperEffect.Abort -> throw effect.cause
        }
    }

    private fun requireRead(
        binding: ConfinedBinding,
        id: Long,
    ): ConfinedReadExchange = requireNotNull(read).also { check(it.request.binding == binding && it.request.id == id) { "Relay read identity mismatch" } }

    private fun terminate(cause: Throwable) {
        state = ConfinedHelperMachine.evaluate(state, ConfinedHelperEvent.Cancel(cause)).state
        val cleanup = SocketCleanup()
        intermediate?.let { cleanup.attempt(it::close) }
        claimId?.let { id -> cleanup.attempt { session.releaseClaim(id, cleanup.failure ?: cause) } }
        cleanup.attempt(session::cancel)
        pendingStage = null
        pendingSetup = null
        pendingReceipt = null
        intermediate = null
        claimId = null
        cleanup.rethrow()
    }
}
