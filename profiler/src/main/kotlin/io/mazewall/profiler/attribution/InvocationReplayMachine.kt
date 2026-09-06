package io.mazewall.profiler.attribution

/** Identifies one Linux task for one lifetime within a profiled process. */
public data class TaskIdentity(
    val processEpoch: Long,
    val tid: Int,
    val taskEpoch: Long,
)

/** A session-local native-invocation identity; zero is deliberately invalid. */
@JvmInline
public value class InvocationId(public val value: Long) {
    init {
        require(value > 0) { "invocation id must be positive" }
    }
}

/** Reason a syscall could not receive a validated managed-stack attribution. */
public enum class AttributionStatus {
    PENDING_DICTIONARY,
    NO_ACTIVE_INVOCATION,
    SEQUENCE_GAP,
    ACTIVATION_MISMATCH,
    KERNEL_CAPTURE_FAILED,
}

/** A raw syscall plus the invocation identity that was valid at its ordered capture point. */
public data class SyscallAttribution(
    val task: TaskIdentity,
    val taskSequence: Long,
    val syscallNumber: Int,
    val args: List<Long>,
    val invocationId: InvocationId?,
    val attributionStatus: AttributionStatus,
    val captureFlags: Int = 0,
) {
    init {
        require(taskSequence > 0) { "task sequence must be positive" }
        require(args.size == ARGUMENT_COUNT) { "syscall observations need exactly $ARGUMENT_COUNT arguments" }
    }

    public companion object {
        public const val ARGUMENT_COUNT: Int = 6
    }
}

/** Input from the shared per-task ordering domain used by marker and syscall events. */
public sealed interface InvocationEvent {
    public val task: TaskIdentity
    public val sequence: Long

    public data class Activate(
        override val task: TaskIdentity,
        override val sequence: Long,
        val invocationId: InvocationId,
    ) : InvocationEvent

    public data class EndRestore(
        override val task: TaskIdentity,
        override val sequence: Long,
        val endedInvocationId: InvocationId,
        val restoredInvocationId: InvocationId?,
    ) : InvocationEvent

    public data class Syscall(
        override val task: TaskIdentity,
        override val sequence: Long,
        val syscallNumber: Int,
        val args: List<Long>,
    ) : InvocationEvent
}

/** An ordering failure invalidates the active task state; it never triggers inference. */
public data class StateInvalidated(
    val task: TaskIdentity,
    val expectedSequence: Long,
    val actualSequence: Long,
    val reason: StateInvalidationReason,
)

public enum class StateInvalidationReason {
    SEQUENCE_GAP,
    ACTIVATION_MISMATCH,
}

/** Result of applying one ordered event. */
public sealed interface ReplayResult {
    public val invalidated: StateInvalidated?

    public data class StateChanged(
        override val invalidated: StateInvalidated? = null,
    ) : ReplayResult

    public data class Syscall(
        val observation: SyscallAttribution,
        override val invalidated: StateInvalidated? = null,
    ) : ReplayResult
}

/**
 * Deterministically reconstructs the invocation active at each syscall.
 *
 * This deliberately relies only on the shared task sequence. A gap clears state before
 * processing the event, so an invocation from before the gap can never be reused.
 */
public class InvocationReplayMachine {
    private val states = mutableMapOf<TaskIdentity, TaskState>()

    public fun accept(event: InvocationEvent): ReplayResult {
        require(event.sequence > 0) { "event sequence must be positive" }
        val previous = states[event.task]
        val invalidated = previous?.takeIf { event.sequence != it.lastSequence + 1 }?.let {
            StateInvalidated(event.task, it.lastSequence + 1, event.sequence, StateInvalidationReason.SEQUENCE_GAP)
        }
        val state = if (invalidated == null) previous ?: TaskState() else TaskState()
        state.lastSequence = event.sequence

        return when (event) {
            is InvocationEvent.Activate -> {
                state.activeInvocationId = event.invocationId
                states[event.task] = state
                ReplayResult.StateChanged(invalidated)
            }

            is InvocationEvent.EndRestore -> endRestore(event, state, invalidated)
            is InvocationEvent.Syscall -> syscall(event, state, invalidated)
        }
    }

    private fun endRestore(
        event: InvocationEvent.EndRestore,
        state: TaskState,
        invalidated: StateInvalidated?,
    ): ReplayResult {
        if (state.activeInvocationId != event.endedInvocationId) {
            states.remove(event.task)
            return ReplayResult.StateChanged(
                invalidated ?: StateInvalidated(
                    event.task,
                    event.sequence,
                    event.sequence,
                    StateInvalidationReason.ACTIVATION_MISMATCH,
                ),
            )
        }
        state.activeInvocationId = event.restoredInvocationId
        states[event.task] = state
        return ReplayResult.StateChanged(invalidated)
    }

    private fun syscall(
        event: InvocationEvent.Syscall,
        state: TaskState,
        invalidated: StateInvalidated?,
    ): ReplayResult.Syscall {
        states[event.task] = state
        val status = when {
            invalidated != null -> AttributionStatus.SEQUENCE_GAP
            state.activeInvocationId == null -> AttributionStatus.NO_ACTIVE_INVOCATION
            else -> AttributionStatus.PENDING_DICTIONARY
        }
        return ReplayResult.Syscall(
            SyscallAttribution(
                task = event.task,
                taskSequence = event.sequence,
                syscallNumber = event.syscallNumber,
                args = event.args,
                invocationId = if (status == AttributionStatus.PENDING_DICTIONARY) state.activeInvocationId else null,
                attributionStatus = status,
            ),
            invalidated,
        )
    }

    private class TaskState(
        var lastSequence: Long = 0,
        var activeInvocationId: InvocationId? = null,
    )
}
