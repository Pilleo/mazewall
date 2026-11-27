package io.mazewall.enforcer.api.execution

import io.mazewall.platform.lifecycle.UsesProcessDescriptors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Path

/**
 * Child-JVM isolation probes for [ConfinedHelperRuntimeDrainTest].
 *
 * Each test runs in its own OS process with an isolated [TempDir] and unique JaCoCo child
 * execution file, touching no shared mutable state in the parent JUnit JVM.
 */
@Execution(ExecutionMode.CONCURRENT)
@UsesProcessDescriptors
class ConfinedHelperRuntimeDrainProbeTest {
    @TempDir lateinit var directory: Path

    private fun runSupportCloseProbe(mode: String) = ConfinedHelperRuntimeDrainTest.runSupportCloseProbe(directory, mode)

    @Test
    fun `transferred live plan reaches exact Ready before B6 refusal`() = runSupportCloseProbe("history_only")

    @Test
    fun `synthetic original worker receipt reaches the confined listener consumer`() = runSupportCloseProbe("b6_handshake")

    @Test
    fun `drain revokes an unsent original worker receipt`() = runSupportCloseProbe("b6_handshake_drained")

    @Test
    fun `exited worker cannot send an unsent original receipt`() = runSupportCloseProbe("b6_handshake_worker_exit")

    @Test
    fun `drain after peer credentials prevents original receipt handoff`() = runSupportCloseProbe("b6_handshake_after_auth")

    @Test
    fun `drain after prelude prevents original receipt handoff`() = runSupportCloseProbe("b6_handshake_after_write")

    @Test
    fun `original Live session denies observation without a task and stays usable`() = runSupportCloseProbe("b6_handshake_observe_empty")

    @Test
    fun `original Live notification observes its owned task and still replies EPERM`() = runSupportCloseProbe("b6_handshake_observe_task")

    @Test
    fun `reactor deadline denies once while Live observation is blocked`() = runSupportCloseProbe("b6_handshake_observe_task_timeout")

    @Test
    fun `lost original Live endpoint fails queued work`() = runSupportCloseProbe("b6_handshake_endpoint_loss")

    @Test
    fun `receipt cannot install endpoint after original worker exits`() = runSupportCloseProbe("b6_handshake_ack_worker_exit")

    @Test
    fun `Live observation accepts a fragmented frame within one deadline`() = runSupportCloseProbe("b6_handshake_frame_short")

    @Test
    fun `Live observation accepts an active prefix lasting beyond one idle interval`() = runSupportCloseProbe("b6_handshake_frame_slow")

    @Test
    fun `Live observation survives idle polling before the next frame`() = runSupportCloseProbe("b6_handshake_frame_idle")

    @Test
    fun `Live observation poisons a timed out partial frame`() = runSupportCloseProbe("b6_handshake_frame_partial")

    @Test
    fun `Live observation poisons a replayed sequence`() = runSupportCloseProbe("b6_handshake_frame_replay")

    @Test
    fun `Live observation poisons an unsupported frame version`() = runSupportCloseProbe("b6_handshake_frame_malformed")

    @Test
    fun `Live observation poisons sequence overflow and reuse`() = runSupportCloseProbe("b6_handshake_frame_overflow")

    @Test
    fun `endpoint loss during Begin ends the exact task without entering its body`() = runSupportCloseProbe("b6_handshake_observe_task_loss_begin")

    @Test
    fun `changed publication between copies denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_changed_publication")

    @Test
    fun `changed invocation between copies denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_changed_invocation")

    @Test
    fun `wrong snapshot binding denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_wrong_binding")

    @Test
    fun `wrong notification TID denies without copying`() = runSupportCloseProbe("b6_handshake_observe_task_wrong_tid")

    @Test
    fun `uninstalled endpoint cannot observe a published task`() = runSupportCloseProbe("b6_handshake_observe_task_notinstalled")

    @Test
    fun `recovered Begin remains unobservable during exact End`() = runSupportCloseProbe("b6_handshake_observe_task_recovered_end")

    @Test
    fun `pending End remains unobservable`() = runSupportCloseProbe("b6_handshake_observe_task_pending_end")

    @Test
    fun `busy snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_busy")

    @Test
    fun `absent snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_absent")

    @Test
    fun `incomplete snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_incomplete")

    @Test
    fun `stale snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_stale")

    @Test
    fun `unsupported snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_unsupported")

    @Test
    fun `foreign task snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_foreign")

    @Test
    fun `changed frame between copies denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_changed_frame")

    @Test
    fun `notification bytes are owned before reactor mutation`() = runSupportCloseProbe("b6_handshake_observe_task_input_mutation")

    @Test
    fun `task End during first copy revokes observation`() = runSupportCloseProbe("b6_handshake_observe_task_copy_end")

    @Test
    fun `cancellation during first copy revokes observation`() = runSupportCloseProbe("b6_handshake_observe_task_copy_cancel")

    @Test
    fun `endpoint loss during first copy prevents a matched result`() = runSupportCloseProbe("b6_handshake_observe_task_copy_loss")

    @Test
    fun `receiver physical exit precedes original domain retirement`() = runSupportCloseProbe("receiver_retirement")

    @Test
    fun `bound receiver without prepared retirement is refused and closes its socket once`() = runSupportCloseProbe("b6_unprepared_receiver")

    @Test
    fun `synthetic installed live invocation begins before its physical body`() = runSupportCloseProbe("task_begin")

    @Test
    fun `recovered live begin failure ends its exact task without entering body`() = runSupportCloseProbe("task_begin_throw")

    @Test
    fun `fatal recovered live begin ends its exact task and retains identity`() = runSupportCloseProbe("task_begin_fatal")

    @Test
    fun `pending live task end closes admission and retains its exact task`() = runSupportCloseProbe("task_begin_pending")

    @Test
    fun `pending live task end retains invalidation failure and typed outcome`() = runSupportCloseProbe("task_begin_pending_throw")

    @Test
    fun `refused live task end closes admission and retains its exact task`() = runSupportCloseProbe("task_begin_refused")

    @Test
    fun `live delivery callback retains canonical context failure before task end`() = runSupportCloseProbe("task_begin_late")

    @Test
    fun `foreign live task lease is neither published nor ended`() = runSupportCloseProbe("task_begin_foreign")

    @Test
    fun `foreign recovered live task is neither published nor ended`() = runSupportCloseProbe("task_begin_foreign_recovered")

    @Test
    fun `live task delivery callback holds its lease until callback returns`() = runSupportCloseProbe("task_begin_callback_blocked")

    @Test
    fun `queued live cancellation invalidates once without task context`() = runSupportCloseProbe("task_begin_cancel")

    @Test
    fun `running live cancellation retains the task until its physical end`() = runSupportCloseProbe("task_begin_cancel_running")

    @Test
    fun `concurrent live cancellations share one invalidation`() = runSupportCloseProbe("task_begin_cancel_concurrent")

    @Test
    fun `live cancellation before worker invocation prevents begin`() = runSupportCloseProbe("task_begin_cancel_before_run")

    @Test
    fun `live cancellation delivers after invalidation throws without replay`() = runSupportCloseProbe("task_begin_cancel_throw")

    @Test
    fun `live dependent stage failure does not become invocation failure`() = runSupportCloseProbe("task_begin_dependent")

    @Test
    fun `live restoration and private inline body retain the same lease`() = runSupportCloseProbe("task_begin_context_inline")

    @Test
    fun `late ordinary failure and refused end retain both outcomes`() = runSupportCloseProbe("task_begin_late_refused")

    @Test
    fun `late ordinary failure and fatal end preserve fatal precedence`() = runSupportCloseProbe("task_begin_late_fatal_end")

    @Test
    fun `late fatal failure and pending end preserve both outcomes`() = runSupportCloseProbe("task_begin_fatal_pending")

    @Test
    fun `first invocation one shot end guard cannot clear the active second task`() = runSupportCloseProbe("task_begin_stale")

    @Test
    fun `revoked task tombstone rejects a different cleanup lease`() = runSupportCloseProbe("task_begin_tombstone")

    @Test
    fun `retained original lease remains endable before slot cleanup record`() = runSupportCloseProbe("task_begin_retained_frontier")

    @Test
    fun `retained slot lease remains endable before publication`() = runSupportCloseProbe("task_begin_retained_slot")

    @Test
    fun `live cancellation rejects wrong permit foreign domain and unregistered task`() = runSupportCloseProbe("task_begin_cancel_identity")

    @Test
    fun `live failure before task claim prevents begin without early invalidation`() = runSupportCloseProbe("task_begin_fail_before_run")

    @Test
    fun `live failure after task claim invalidates and retains task until end`() = runSupportCloseProbe("task_begin_fail_running")

    @Test
    fun `refused live begin and fatal recovery retain both failures without end`() = runSupportCloseProbe("task_begin_refused_recovery")

    @Test
    fun `refused live registration retains ambiguous receipt without invented worker`() = runSupportCloseProbe("worker_refused")

    @Test
    fun `wrong recovered live worker identity retains originals without retirement`() = runSupportCloseProbe("worker_wrong_identity")

    @Test
    fun `pending live worker retirement retains installation and originals without replay`() = runSupportCloseProbe("worker_retire_pending")

    @Test
    fun `refused live worker retirement retains installation and originals without replay`() = runSupportCloseProbe("worker_retire_refused")

    @Test
    fun `live session retirement completes before helper pins and originals`() = runSupportCloseProbe("live_session")

    @Test
    fun `live session retirement failure remains counted without retry`() = runSupportCloseProbe("live_session_failure")

    @Test
    fun `failed support pin close remains counted and retains original resources`() = runSupportCloseProbe("error")

    @Test
    fun `thrown support pin close remains counted and is never repeated`() = runSupportCloseProbe("throw")

    @Test
    fun `fatal support close retains original error and never repeats`() = runSupportCloseProbe("fatal")

    @Test
    fun `interrupted support close is not redispatched on later manager sweep`() = runSupportCloseProbe("interrupt")

    @Test
    fun `postcapture support close failure remains counted and is never repeated`() = runSupportCloseProbe("postcapture")

    @Test
    fun `independent support owner closes once when another close becomes unknown`() = runSupportCloseProbe("multiple")

    @Test
    fun `live carrier retains unknown pin and closes independent pin once`() = runSupportCloseProbe("live_multiple")
}
