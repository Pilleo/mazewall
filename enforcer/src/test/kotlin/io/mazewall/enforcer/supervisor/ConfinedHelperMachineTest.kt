package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.internal.supervisor.ConfinedHelperTestData.manifest
import io.mazewall.ffi.networking.ConfinedDeadline
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfinedHelperMachineTest {
    private val manifest = manifest()
    private val binding = manifest.binding
    private val deadline = ConfinedDeadline(19)
    private val machine = ConfinedHelperMachine

    private fun frame(
        state: ConfinedHelperState,
        frame: ConfinedHelperProtocol.Frame,
    ) = machine.evaluate(state, ConfinedHelperEvent.ParentFrame(frame))

    private fun ready(): ConfinedHelperState.Ready = ConfinedHelperState.Ready(manifest)

    private fun read(id: Long = 1) = ConfinedReadRequest(binding, id, HelperPurpose.Application, 0, "file")

    @Test
    fun `setup requires validated metadata each root adoption and real seal before ready`() {
        val setup = frame(ConfinedHelperState.Unsealed, ConfinedHelperProtocol.Frame.Setup(manifest, deadline))
        assertEquals(listOf(ConfinedHelperEffect.Validate(manifest, deadline)), setup.effects)
        assertIs<ConfinedHelperState.Receiving>(setup.state)
        val accepted = machine.evaluate(setup.state, ConfinedHelperEvent.SetupValidated(binding, deadline))
        assertEquals(listOf(ConfinedHelperEffect.Send(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, binding, deadline))), accepted.effects)
        var receiving = accepted.state
        for (index in 0..1) {
            val offer = frame(receiving, ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Offer, binding, index, deadline))
            assertEquals(
                listOf(
                ConfinedHelperEffect.Send(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Expected, binding, index, deadline)),
                ConfinedHelperEffect.ReceiveRoot(manifest.capabilities[index], deadline),
            ),
                offer.effects,
            )
            val adopted = machine.evaluate(offer.state, ConfinedHelperEvent.RootAdopted(binding, index, deadline))
            assertEquals(listOf(ConfinedHelperEffect.Send(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Received, binding, index, deadline))), adopted.effects)
            receiving = adopted.state
            assertEquals(index + 1, assertIs<ConfinedHelperState.Receiving>(receiving).nextIndex)
        }
        val complete = frame(receiving, ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Complete, binding, 2, deadline))
        assertIs<ConfinedHelperState.Confining>(complete.state)
        assertEquals(listOf(ConfinedHelperEffect.Seal(manifest, deadline)), complete.effects)
        val sealed = machine.evaluate(complete.state, ConfinedHelperEvent.Sealed(binding, deadline))
        assertIs<ConfinedHelperState.Ready>(sealed.state)
        assertEquals(listOf(ConfinedHelperEffect.Send(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, binding, deadline))), sealed.effects)
    }

    @Test
    fun `result receipt alone cannot publish done before local close or staged relay commit`() {
        val started = frame(ready(), ConfinedHelperProtocol.Frame.Read(read(), deadline))
        assertEquals(listOf(ConfinedHelperEffect.Open(read(), deadline)), started.effects)
        val opened = machine.evaluate(started.state, ConfinedHelperEvent.Opened(binding, 1, deadline))
        assertEquals(listOf(ConfinedHelperEffect.Send(ConfinedHelperProtocol.Frame.Result(ConfinedHelperProtocol.ResultTag.Opened, binding, 1, deadline))), opened.effects)
        val expected = frame(opened.state, ConfinedHelperProtocol.Frame.Result(ConfinedHelperProtocol.ResultTag.Expected, binding, 1, deadline))
        assertEquals(listOf(ConfinedHelperEffect.SendResult(1, deadline)), expected.effects)
        val sent = machine.evaluate(expected.state, ConfinedHelperEvent.ResultSent(binding, 1, deadline))
        assertTrue(sent.effects.isEmpty())
        val received = frame(sent.state, ConfinedHelperProtocol.Frame.Result(ConfinedHelperProtocol.ResultTag.Received, binding, 1, deadline))
        assertEquals(listOf(ConfinedHelperEffect.CommitResult(1, deadline)), received.effects)
        assertEquals(ConfinedTransfer.AwaitDone, assertIs<ConfinedHelperState.Ready>(received.state).active?.phase)
        val done = machine.evaluate(received.state, ConfinedHelperEvent.ReadCompleted(binding, 1, deadline))
        assertEquals(ready().copy(lastRequest = 1), done.state)
        assertEquals(listOf(ConfinedHelperEffect.Send(ConfinedHelperProtocol.Frame.Result(ConfinedHelperProtocol.ResultTag.Done, binding, 1, deadline))), done.effects)
    }

    @Test
    fun `denial preserves errno and consumes request identity without exporting a descriptor`() {
        val started = frame(ready(), ConfinedHelperProtocol.Frame.Read(read(7), deadline))
        val denied = machine.evaluate(started.state, ConfinedHelperEvent.Denied(binding, 7, 13, deadline))
        assertEquals(ready().copy(lastRequest = 7), denied.state)
        assertEquals(listOf(ConfinedHelperEffect.Send(ConfinedHelperProtocol.Frame.Denied(binding, 7, 13, deadline))), denied.effects)
        assertIs<ConfinedHelperState.Terminal>(frame(denied.state, ConfinedHelperProtocol.Frame.Read(read(7), deadline)).state)
    }

    @Test
    fun `premature stale duplicate and wrong deadline events terminally reject without effects of authority`() {
        val receiving = ConfinedHelperState.Receiving(manifest, deadline)
        val active = frame(ready(), ConfinedHelperProtocol.Frame.Read(read(), deadline)).state
        val rows = listOf(
            receiving to ConfinedHelperEvent.Sealed(binding, deadline),
            receiving to ConfinedHelperEvent.RootAdopted(binding, 0, deadline),
            receiving to ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Offer, binding, 0, deadline)),
            receiving.copy(setup = ConfinedSetupPhase.Roots) to ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Complete, binding, 2, deadline)),
            active to ConfinedHelperEvent.Opened(binding, 2, deadline),
            active to ConfinedHelperEvent.Opened(binding, 1, ConfinedDeadline(20)),
            active to ConfinedHelperEvent.Opened(binding.copy(manifestDigest = "0".repeat(64)), 1, deadline),
            active to ConfinedHelperEvent.ReadCompleted(binding, 1, deadline),
            active to ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Read(read(2), deadline)),
            ready() to ConfinedHelperEvent.ParentFrame(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, binding, deadline)),
        )
        rows.forEach { (state, event) ->
            val failed = machine.evaluate(state, event)
            assertIs<ConfinedHelperState.Terminal>(failed.state)
            assertEquals(1, failed.effects.size)
            assertIs<ConfinedHelperEffect.Abort>(failed.effects.single())
            assertEquals(ConfinedHelperMachine.Transition(failed.state, emptyList()), machine.evaluate(failed.state, ConfinedHelperEvent.Sealed(binding, deadline)))
        }
    }

    @Test
    fun `cancellation latches original failure and stop waits for actual owner disposition`() {
        val cause = IllegalStateException("close failed")
        val failed = machine.evaluate(ready(), ConfinedHelperEvent.Cancel(cause))
        assertSame(cause, assertIs<ConfinedHelperState.Terminal>(failed.state).cause)
        assertSame(cause, assertIs<ConfinedHelperEffect.Abort>(failed.effects.single()).cause)
        val stop = frame(ready(), ConfinedHelperProtocol.Frame.Stop(ConfinedHelperProtocol.StopTag.Stop, binding, StopReason.Cancel, deadline))
        assertIs<ConfinedHelperState.Stopping>(stop.state)
        assertEquals(listOf(ConfinedHelperEffect.Stop(StopReason.Cancel, deadline)), stop.effects)
        val stopped = machine.evaluate(stop.state, ConfinedHelperEvent.Stopped(binding, deadline))
        assertIs<ConfinedHelperState.Terminal>(stopped.state)
        assertEquals(listOf(ConfinedHelperEffect.Send(ConfinedHelperProtocol.Frame.Stop(ConfinedHelperProtocol.StopTag.LocalStopped, binding, StopReason.Cancel, deadline))), stopped.effects)
    }
}
