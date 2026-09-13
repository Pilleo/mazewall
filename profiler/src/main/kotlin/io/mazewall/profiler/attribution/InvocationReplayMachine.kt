package io.mazewall.profiler.attribution

/** Typed process-incarnation identity used to reject PID reuse. */
@JvmInline
public value class ProcessIncarnationId(
    public val value: Long,
)

/** Typed lifetime identity for one kernel task instance. */
@JvmInline
public value class TaskLifetimeId(
    public val value: Long,
)

/** Typed profile-session epoch used to separate successive process lifetimes. */
@JvmInline
public value class SessionEpoch(
    public val value: Long,
)

/** Identifies one Linux task for one lifetime within a profiled process. */
public data class TaskIdentity(
    val processEpoch: Long,
    val tid: Int,
    val taskEpoch: Long,
) {
    public val processIncarnation: ProcessIncarnationId
        get() = ProcessIncarnationId(processEpoch)

    public val taskLifetime: TaskLifetimeId
        get() = TaskLifetimeId(taskEpoch)

    public companion object {
        public fun from(
            process: ProcessIncarnationId,
            tid: Int,
            lifetime: TaskLifetimeId,
        ): TaskIdentity = TaskIdentity(process.value, tid, lifetime.value)
    }
}

/** A session-local native-invocation identity; zero is deliberately invalid. */
@JvmInline
public value class InvocationId(
    public val value: Long,
) {
    init {
        require(value > 0) { "invocation id must be positive" }
    }
}

/** Process incarnation token to guard against PID reuse across process epochs. */
@JvmInline
public value class ProcessIncarnation(
    public val value: Long,
)

/** Represents re-entrancy depth and boundary status for an active task. */
public data class ReentrantBoundaryState(
    val depth: Int = 0,
    val isReentrant: Boolean = depth > 1,
) {
    init {
        require(depth >= 0) { "reentrant depth cannot be negative" }
    }
}

/** Reason a syscall could not receive a validated managed-stack attribution. */
public enum class AttributionStatus {
    PENDING_DICTIONARY,
    NO_ACTIVE_INVOCATION,
    SEQUENCE_GAP,
    ACTIVATION_MISMATCH,
    KERNEL_CAPTURE_FAILED,
    CAUSAL_UNCERTAIN,
    STALE_LIFECYCLE_IDENTITY,
}

/** A raw syscall plus the invocation identity that was valid at its ordered capture point. */
public class SyscallAttribution(
    public val task: TaskIdentity,
    public val taskSequence: Long,
    public val syscallNumber: Int,
    args: List<Long>,
    public val invocationId: InvocationId?,
    public val attributionStatus: AttributionStatus,
    public val captureFlags: Int = 0,
) {
    public val args: List<Long> = java.util.List.copyOf(args)

    init {
        require(taskSequence > 0) { "task sequence must be positive" }
        require(args.size == ARGUMENT_COUNT) { "syscall observations need exactly $ARGUMENT_COUNT arguments" }
    }

    public fun copy(
        task: TaskIdentity = this.task,
        taskSequence: Long = this.taskSequence,
        syscallNumber: Int = this.syscallNumber,
        args: List<Long> = this.args,
        invocationId: InvocationId? = this.invocationId,
        attributionStatus: AttributionStatus = this.attributionStatus,
        captureFlags: Int = this.captureFlags,
    ): SyscallAttribution = SyscallAttribution(task, taskSequence, syscallNumber, args, invocationId, attributionStatus, captureFlags)

    override fun equals(other: Any?): Boolean =
        other is SyscallAttribution &&
            task == other.task &&
            taskSequence == other.taskSequence &&
            syscallNumber == other.syscallNumber &&
            args == other.args &&
            invocationId == other.invocationId &&
            attributionStatus == other.attributionStatus &&
            captureFlags == other.captureFlags

    override fun hashCode(): Int = listOf(task, taskSequence, syscallNumber, args, invocationId, attributionStatus, captureFlags).hashCode()

    override fun toString(): String =
        "SyscallAttribution(task=$task, taskSequence=$taskSequence, syscallNumber=$syscallNumber, args=$args, invocationId=$invocationId, attributionStatus=$attributionStatus, captureFlags=$captureFlags)"

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

    /** Explicitly invalidates all exact attribution for this task. */
    public data class Invalidate(
        override val task: TaskIdentity,
        override val sequence: Long,
        val reason: StateInvalidationReason,
    ) : InvocationEvent

    public class Syscall(
        override val task: TaskIdentity,
        override val sequence: Long,
        public val syscallNumber: Int,
        args: List<Long>,
    ) : InvocationEvent {
        public val args: List<Long> = java.util.List.copyOf(args)

        override fun equals(other: Any?): Boolean = other is Syscall && task == other.task && sequence == other.sequence && syscallNumber == other.syscallNumber && args == other.args

        override fun hashCode(): Int = listOf(task, sequence, syscallNumber, args).hashCode()
    }
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
    DETACH,
    CALLBACK_SUPPRESSION,
    EXEC_TRANSITION,
    TERMINAL_SESSION,
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

    /** Maps tid → the most recently seen TaskIdentity for that tid, for stale-epoch eviction. */
    private val latestTaskByTid = mutableMapOf<Int, TaskIdentity>()

    public fun accept(event: InvocationEvent): ReplayResult {
        require(event.sequence > 0) { "event sequence must be positive" }
        evictStaleIncarnation(event.task)
        val previous = states[event.task]
        val invalidated = previous?.takeIf { sequenceGap(event, it.lastSequence) }?.let {
            StateInvalidated(event.task, it.lastSequence + 1, event.sequence, StateInvalidationReason.SEQUENCE_GAP)
        }
        val state = if (invalidated == null) {
            previous ?: TaskState(processIncarnation = event.task.processIncarnation)
        } else {
            TaskState(processIncarnation = event.task.processIncarnation, valid = false)
        }
        val sequencedState = state.copy(lastSequence = event.sequence)

        return when (event) {
            is InvocationEvent.Activate -> {
                val nextState = sequencedState.copy(
                    activeInvocationId = event.invocationId,
                    reentrantDepth = sequencedState.reentrantDepth + 1,
                    valid = true,
                )
                states[event.task] = nextState
                latestTaskByTid[event.task.tid] = event.task
                ReplayResult.StateChanged(invalidated)
            }

            is InvocationEvent.EndRestore -> endRestore(event, sequencedState, invalidated)
            is InvocationEvent.Invalidate -> invalidate(event, sequencedState, invalidated)
            is InvocationEvent.Syscall -> syscall(event, sequencedState, invalidated)
        }
    }

    /**
     * Evicts any TaskState stored under a stale processEpoch for the same tid.
     * Called before any event is processed so that state from a dead/recycled process
     * can never contaminate attribution for the new incarnation.
     */
    private fun evictStaleIncarnation(task: TaskIdentity) {
        val latest = latestTaskByTid[task.tid] ?: return
        if (latest.processEpoch != task.processEpoch) {
            // New process epoch for this tid — evict all states keyed under the old identity
            states.keys.removeAll { it.tid == task.tid && it.processEpoch != task.processEpoch }
            latestTaskByTid[task.tid] = task
        }
    }

    public fun processIncarnationFor(task: TaskIdentity): ProcessIncarnation = ProcessIncarnation(states[task]?.processIncarnation?.value ?: task.processIncarnation.value)

    public fun reentrantStateFor(task: TaskIdentity): ReentrantBoundaryState = states[task]?.let { ReentrantBoundaryState(depth = it.reentrantDepth) } ?: ReentrantBoundaryState()

    /** Invalidates every active task at terminal session shutdown. */
    public fun terminate() {
        states.clear()
        latestTaskByTid.clear()
    }

    private fun invalidate(
        event: InvocationEvent.Invalidate,
        state: TaskState,
        invalidated: StateInvalidated?,
    ): ReplayResult {
        states[event.task] = state.copy(activeInvocationId = null, reentrantDepth = 0, valid = false)
        return ReplayResult.StateChanged(
            invalidated ?: StateInvalidated(event.task, event.sequence, event.sequence, event.reason),
        )
    }

    private fun endRestore(
        event: InvocationEvent.EndRestore,
        state: TaskState,
        invalidated: StateInvalidated?,
    ): ReplayResult {
        if (!state.valid || state.activeInvocationId != event.endedInvocationId) {
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
        val nextState = state.copy(
            reentrantDepth = (state.reentrantDepth - 1).coerceAtLeast(0),
            activeInvocationId = event.restoredInvocationId,
        )
        states[event.task] = nextState
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
            !state.valid -> AttributionStatus.CAUSAL_UNCERTAIN
            state.activeInvocationId == null -> AttributionStatus.NO_ACTIVE_INVOCATION
            state.reentrantDepth > 1 -> AttributionStatus.CAUSAL_UNCERTAIN
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

    private fun sequenceGap(
        event: InvocationEvent,
        lastSequence: Long,
    ): Boolean =
        when (event) {
            is InvocationEvent.Syscall -> event.sequence < lastSequence || event.sequence > lastSequence + 1
            else -> event.sequence != lastSequence + 1
        }

    private data class TaskState(
        val lastSequence: Long = 0,
        val activeInvocationId: InvocationId? = null,
        val reentrantDepth: Int = 0,
        val processIncarnation: ProcessIncarnationId = ProcessIncarnationId(0),
        val valid: Boolean = true,
    )
}
