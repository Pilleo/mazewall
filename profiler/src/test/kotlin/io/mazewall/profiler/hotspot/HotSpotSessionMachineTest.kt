package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotSessionEffect
import io.mazewall.profiler.internal.hotspot.HotSpotSessionEvent
import io.mazewall.profiler.internal.hotspot.HotSpotSessionMachine
import io.mazewall.profiler.internal.hotspot.HotSpotSessionState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HotSpotSessionMachineTest {
    private val machine = HotSpotSessionMachine()
    private val identity = "session"

    @Test
    fun `header is not readiness and user work requires ready`() {
        val starting = start()
        val header = machine.evaluate(starting, HotSpotSessionEvent.Started(identity), 1).state
        assertIs<HotSpotSessionState.Starting>(header)
        assertIs<HotSpotSessionState.Failed>(machine.evaluate(header, HotSpotSessionEvent.BeginWork, 2).state)
        val ready = machine.evaluate(header, HotSpotSessionEvent.Ready(identity), 2)
        assertIs<HotSpotSessionState.Ready>(ready.state)
        assertEquals(listOf(HotSpotSessionEffect.PublishReady), ready.effects)
        assertIs<HotSpotSessionState.Running>(machine.evaluate(ready.state, HotSpotSessionEvent.BeginWork, 3).state)
    }

    @Test
    fun `duplicate finish and close wait on the first owner`() {
        val finish = machine.evaluate(running(), HotSpotSessionEvent.Finish(7), 3)
        val state = assertIs<HotSpotSessionState.Finishing>(finish.state)
        assertEquals(listOf(HotSpotSessionEffect.RequestStop), finish.effects)
        val duplicate = machine.evaluate(state, HotSpotSessionEvent.Finish(9), 4)
        assertSame(state, duplicate.state)
        assertEquals(listOf(HotSpotSessionEffect.AwaitOwner(7)), duplicate.effects)
    }

    @Test
    fun `summary alone and EOF alone never complete`() {
        val finishing = machine.evaluate(running(), HotSpotSessionEvent.Finish(7), 3).state
        val summary = machine.evaluate(finishing, HotSpotSessionEvent.Summary(identity, true), 4).state
        assertIs<HotSpotSessionState.Finishing>(summary)
        val stopped = machine.evaluate(summary, HotSpotSessionEvent.ProcessExited(0), 5).state
        val eof = machine.evaluate(stopped, HotSpotSessionEvent.StdoutEnded, 6).state
        assertIs<HotSpotSessionState.Finishing>(eof)
        val stderr = machine.evaluate(eof, HotSpotSessionEvent.StderrJoined, 7).state
        assertIs<HotSpotSessionState.Finishing>(stderr)
        assertIs<HotSpotSessionState.Completed>(machine.evaluate(stderr, HotSpotSessionEvent.ResourcesJoined, 8).state)
        assertIs<HotSpotSessionState.Failed>(machine.evaluate(finishing, HotSpotSessionEvent.StdoutEnded, 4).state)
    }

    @Test
    fun `partial native stop remains failed after process and pipes join`() {
        val finishing = machine.evaluate(running(), HotSpotSessionEvent.Finish(7), 3).state
        val failed = assertIs<HotSpotSessionState.Failed>(machine.evaluate(finishing, HotSpotSessionEvent.Summary(identity, false), 4).state)
        assertTrue(failed.retainOwner)
        val joined = machine.evaluate(failed, HotSpotSessionEvent.CleanupJoined, 5).state
        assertIs<HotSpotSessionState.Failed>(joined)
        assertFalse(joined.retainOwner)
    }

    @Test
    fun `startup timeout parent cancellation and permission failures abort explicitly`() {
        val expired = machine.evaluate(start(), HotSpotSessionEvent.Tick, 10_000_000_000)
        assertIs<HotSpotSessionState.Failed>(expired.state)
        assertEquals(listOf(HotSpotSessionEffect.Abort), expired.effects)
        val denied = IllegalStateException("EPERM")
        val failed = assertIs<HotSpotSessionState.Failed>(machine.evaluate(running(), HotSpotSessionEvent.Failure(denied), 4).state)
        assertSame(denied, failed.failures.single())
        val cleanup = IllegalStateException("EACCES cleanup")
        val joined = assertIs<HotSpotSessionState.Failed>(machine.evaluate(failed, HotSpotSessionEvent.Failure(cleanup), 5).state)
        assertEquals(listOf(denied, cleanup), joined.failures)
    }

    @Test
    fun `stop timeout retains first owner and terminal states are idempotent`() {
        val finish = machine.evaluate(running(), HotSpotSessionEvent.Finish(7), 3).state
        val expired = assertIs<HotSpotSessionState.Failed>(machine.evaluate(finish, HotSpotSessionEvent.Tick, 2_000_000_004).state)
        assertTrue(expired.retainOwner)
        assertSame(expired, machine.evaluate(expired, HotSpotSessionEvent.Finish(9), 2_000_000_005).state)
    }

    @Test
    fun `mismatched session duplicate readiness and premature summaries fail`() {
        val starting = machine.evaluate(start(), HotSpotSessionEvent.Started(identity), 1).state
        assertIs<HotSpotSessionState.Failed>(machine.evaluate(starting, HotSpotSessionEvent.Ready("different"), 2).state)
        val ready = machine.evaluate(starting, HotSpotSessionEvent.Ready(identity), 2).state
        assertIs<HotSpotSessionState.Failed>(machine.evaluate(ready, HotSpotSessionEvent.Ready(identity), 3).state)
        assertIs<HotSpotSessionState.Failed>(machine.evaluate(starting, HotSpotSessionEvent.Summary(identity, true), 2).state)
    }

    @Test
    fun `short stop window cannot exceed the original session deadline`() {
        val finishing = machine.evaluate(running(), HotSpotSessionEvent.Finish(7), 59_500_000_000).state
        val state = assertIs<HotSpotSessionState.Finishing>(finishing)
        assertEquals(60_000_000_000, state.deadline)
        assertIs<HotSpotSessionState.Failed>(machine.evaluate(state, HotSpotSessionEvent.Tick, 60_000_000_000).state)
    }

    private fun start(): HotSpotSessionState = machine.evaluate(HotSpotSessionState.Created, HotSpotSessionEvent.Start, 0).state

    private fun running(): HotSpotSessionState {
        val header = machine.evaluate(start(), HotSpotSessionEvent.Started(identity), 1).state
        val ready = machine.evaluate(header, HotSpotSessionEvent.Ready(identity), 2).state
        return machine.evaluate(ready, HotSpotSessionEvent.BeginWork, 3).state
    }
}
