package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InvocationReplayMachineTest {
    @Test
    fun `round trips versioned activation syscall and restoration markers`() {
        val task = TaskIdentity(processEpoch = 1, tid = 42, taskEpoch = 2)
        val events = listOf<InvocationEvent>(
            InvocationEvent.Activate(task, 1, InvocationId(101)),
            InvocationEvent.Syscall(task, 2, 257, listOf(-100, 1, 2, 3, 4, 5)),
            InvocationEvent.EndRestore(task, 3, InvocationId(101), null),
        )

        assertEquals(events, events.map { InvocationProtocol.decode(InvocationProtocol.encode(it)) })
        assertFailsWith<IllegalArgumentException> { InvocationProtocol.decode(byteArrayOf(0)) }
    }

    @Test
    fun `interns only identical complete frame sequences into one stack definition`() {
        val dictionary = StackTraceDictionary()
        val frames = listOf(
            ManagedFrame("loader-a", "app/Service", "read", "()V", 17, ManagedFrameKind.JAVA),
            ManagedFrame("bootstrap", "java/io/FileInputStream", "open0", "(Ljava/lang/String;)V", -1, ManagedFrameKind.NATIVE_METHOD),
        )

        val first = dictionary.intern(frames, StackCaptureQuality.COMPLETE)
        val same = dictionary.intern(frames.toList(), StackCaptureQuality.COMPLETE)
        val differentBci = dictionary.intern(frames.dropLast(1) + frames.last().copy(bytecodeLocation = 18), StackCaptureQuality.COMPLETE)

        assertEquals(first.stackTraceId, same.stackTraceId)
        assertEquals(first, dictionary.resolve(first.stackTraceId))
        assertEquals(null, dictionary.resolve(StackTraceId(99)))
        assertEquals(false, first.stackTraceId == differentBci.stackTraceId)
    }

    @Test
    fun `replays nested invocation restoration without timestamp correlation`() {
        val task = TaskIdentity(processEpoch = 7, tid = 8123, taskEpoch = 3)
        val machine = InvocationReplayMachine()

        val observations = listOf(
            machine.accept(InvocationEvent.Activate(task, sequence = 1, invocationId = InvocationId(101))),
            machine.accept(InvocationEvent.Syscall(task, sequence = 2, syscallNumber = 257, args = listOf(-100, 0, 0, 0, 0, 0))),
            machine.accept(InvocationEvent.Activate(task, sequence = 3, invocationId = InvocationId(202))),
            machine.accept(InvocationEvent.Syscall(task, sequence = 4, syscallNumber = 0, args = List(6) { 0 })),
            machine.accept(InvocationEvent.EndRestore(task, sequence = 5, endedInvocationId = InvocationId(202), restoredInvocationId = InvocationId(101))),
            machine.accept(InvocationEvent.Syscall(task, sequence = 6, syscallNumber = 1, args = List(6) { 0 })),
            machine.accept(InvocationEvent.EndRestore(task, sequence = 7, endedInvocationId = InvocationId(101), restoredInvocationId = null)),
            machine.accept(InvocationEvent.Syscall(task, sequence = 8, syscallNumber = 3, args = List(6) { 0 })),
        ).filterIsInstance<ReplayResult.Syscall>()

        assertEquals(
            listOf(InvocationId(101), null, InvocationId(101), null),
            observations.map { it.observation.invocationId },
        )
        assertEquals(
            listOf(
                AttributionStatus.PENDING_DICTIONARY,
                AttributionStatus.CAUSAL_UNCERTAIN,
                AttributionStatus.PENDING_DICTIONARY,
                AttributionStatus.NO_ACTIVE_INVOCATION,
            ),
            observations.map { it.observation.attributionStatus },
        )
    }

    @Test
    fun `multiple syscalls sharing one marker sequence do not create a false gap`() {
        val task = TaskIdentity(processEpoch = 7, tid = 8123, taskEpoch = 3)
        val machine = InvocationReplayMachine()

        machine.accept(InvocationEvent.Activate(task, sequence = 1, invocationId = InvocationId(101)))
        val first = machine.accept(InvocationEvent.Syscall(task, sequence = 1, syscallNumber = 257, args = List(6) { 0 }))
        val second = machine.accept(InvocationEvent.Syscall(task, sequence = 1, syscallNumber = 0, args = List(6) { 0 }))

        assertEquals(AttributionStatus.PENDING_DICTIONARY, (first as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(AttributionStatus.PENDING_DICTIONARY, (second as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(InvocationId(101), first.observation.invocationId)
        assertEquals(InvocationId(101), second.observation.invocationId)
    }

    @Test
    fun `sequence gap invalidates state and never resurrects an older invocation`() {
        val task = TaskIdentity(processEpoch = 7, tid = 8123, taskEpoch = 3)
        val machine = InvocationReplayMachine()

        machine.accept(InvocationEvent.Activate(task, sequence = 1, invocationId = InvocationId(101)))
        val afterGap = machine.accept(InvocationEvent.Syscall(task, sequence = 3, syscallNumber = 257, args = List(6) { 0 }))
        val afterNewActivation = machine.accept(InvocationEvent.Activate(task, sequence = 4, invocationId = InvocationId(202)))
        val recovered = machine.accept(InvocationEvent.Syscall(task, sequence = 5, syscallNumber = 257, args = List(6) { 0 }))

        assertEquals(
            SyscallAttribution(
                task = task,
                taskSequence = 3,
                syscallNumber = 257,
                args = List(6) { 0 },
                invocationId = null,
                attributionStatus = AttributionStatus.SEQUENCE_GAP,
            ),
            (afterGap as ReplayResult.Syscall).observation,
        )
        assertEquals(
            StateInvalidated(task, expectedSequence = 2, actualSequence = 3, reason = StateInvalidationReason.SEQUENCE_GAP),
            afterGap.invalidated,
        )
        assertEquals(null, afterNewActivation.invalidated)
        assertEquals(InvocationId(202), (recovered as ReplayResult.Syscall).observation.invocationId)
    }

    @Test
    fun `mismatched restore clears state before a later syscall`() {
        val task = TaskIdentity(processEpoch = 7, tid = 8123, taskEpoch = 3)
        val machine = InvocationReplayMachine()

        machine.accept(InvocationEvent.Activate(task, sequence = 1, invocationId = InvocationId(101)))
        val mismatch = machine.accept(InvocationEvent.EndRestore(task, sequence = 2, endedInvocationId = InvocationId(202), restoredInvocationId = null))
        val syscall = machine.accept(InvocationEvent.Syscall(task, sequence = 3, syscallNumber = 257, args = List(6) { 0 }))

        assertEquals(StateInvalidated(task, expectedSequence = 2, actualSequence = 2, reason = StateInvalidationReason.ACTIVATION_MISMATCH), mismatch.invalidated)
        assertEquals(AttributionStatus.NO_ACTIVE_INVOCATION, (syscall as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(null, syscall.observation.invocationId)
    }

    @Test
    fun `process incarnation token change invalidates prior task state and resets reentrancy`() {
        val initialTask = TaskIdentity(processEpoch = 1, tid = 42, taskEpoch = 1)
        val machine = InvocationReplayMachine()

        machine.accept(InvocationEvent.Activate(initialTask, sequence = 1, invocationId = InvocationId(101)))
        assertEquals(ProcessIncarnation(1L), machine.processIncarnationFor(initialTask))
        assertEquals(ReentrantBoundaryState(depth = 1, isReentrant = false), machine.reentrantStateFor(initialTask))

        // New incarnation (e.g. process exec or PID recycled under a new process epoch)
        val recycledTask = TaskIdentity(processEpoch = 2, tid = 42, taskEpoch = 1)
        val syscall = machine.accept(InvocationEvent.Syscall(recycledTask, sequence = 1, syscallNumber = 0, args = List(6) { 0 }))

        assertEquals(ProcessIncarnation(2L), machine.processIncarnationFor(recycledTask))
        assertEquals(ReentrantBoundaryState(depth = 0, isReentrant = false), machine.reentrantStateFor(recycledTask))
        assertEquals(AttributionStatus.NO_ACTIVE_INVOCATION, (syscall as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(null, syscall.observation.invocationId)
    }

    @Test
    fun `nested activations increment reentrancy depth and mark state reentrant`() {
        val task = TaskIdentity(processEpoch = 1, tid = 42, taskEpoch = 1)
        val machine = InvocationReplayMachine()

        machine.accept(InvocationEvent.Activate(task, sequence = 1, invocationId = InvocationId(101)))
        assertEquals(ReentrantBoundaryState(depth = 1, isReentrant = false), machine.reentrantStateFor(task))

        machine.accept(InvocationEvent.Activate(task, sequence = 2, invocationId = InvocationId(202)))
        assertEquals(ReentrantBoundaryState(depth = 2, isReentrant = true), machine.reentrantStateFor(task))

        machine.accept(InvocationEvent.EndRestore(task, sequence = 3, endedInvocationId = InvocationId(202), restoredInvocationId = InvocationId(101)))
        assertEquals(ReentrantBoundaryState(depth = 1, isReentrant = false), machine.reentrantStateFor(task))

        machine.accept(InvocationEvent.EndRestore(task, sequence = 4, endedInvocationId = InvocationId(101), restoredInvocationId = null))
        assertEquals(ReentrantBoundaryState(depth = 0, isReentrant = false), machine.reentrantStateFor(task))
    }

    @Test
    fun `supported native callback unsupported syscall is causal uncertain`() {
        val task = TaskIdentity(processEpoch = 1, tid = 42, taskEpoch = 1)
        val machine = InvocationReplayMachine()

        // Outer scope (depth=1): Activate 101
        machine.accept(InvocationEvent.Activate(task, sequence = 1, invocationId = InvocationId(101)))
        val outer = machine.accept(InvocationEvent.Syscall(task, sequence = 2, syscallNumber = 257, args = List(6) { 0 }))

        // Inner scope (depth=2): callback re-enters
        machine.accept(InvocationEvent.Activate(task, sequence = 3, invocationId = InvocationId(202)))
        val inner = machine.accept(InvocationEvent.Syscall(task, sequence = 4, syscallNumber = 0, args = List(6) { 0 }))

        // Restore inner → outer (depth=1)
        machine.accept(InvocationEvent.EndRestore(task, sequence = 5, endedInvocationId = InvocationId(202), restoredInvocationId = InvocationId(101)))
        val restored = machine.accept(InvocationEvent.Syscall(task, sequence = 6, syscallNumber = 1, args = List(6) { 0 }))

        // Close outer (depth=0)
        machine.accept(InvocationEvent.EndRestore(task, sequence = 7, endedInvocationId = InvocationId(101), restoredInvocationId = null))
        val noActive = machine.accept(InvocationEvent.Syscall(task, sequence = 8, syscallNumber = 3, args = List(6) { 0 }))

        assertEquals(AttributionStatus.PENDING_DICTIONARY, (outer as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(InvocationId(101), outer.observation.invocationId)

        assertEquals(AttributionStatus.CAUSAL_UNCERTAIN, (inner as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(null, inner.observation.invocationId)

        assertEquals(AttributionStatus.PENDING_DICTIONARY, (restored as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(InvocationId(101), restored.observation.invocationId)

        assertEquals(AttributionStatus.NO_ACTIVE_INVOCATION, (noActive as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(null, noActive.observation.invocationId)
    }

    @Test
    fun `inner capture failure in reentrant scope does not leave stale exact attribution`() {
        val task = TaskIdentity(processEpoch = 1, tid = 42, taskEpoch = 1)
        val machine = InvocationReplayMachine()

        // Outer scope (depth=1)
        machine.accept(InvocationEvent.Activate(task, sequence = 1, invocationId = InvocationId(101)))

        // Enter re-entrant inner scope (depth=2)
        machine.accept(InvocationEvent.Activate(task, sequence = 2, invocationId = InvocationId(202)))

        // Syscall inside inner scope (depth=2) → must be CAUSAL_UNCERTAIN, not PENDING_DICTIONARY
        val innerSyscall = machine.accept(InvocationEvent.Syscall(task, sequence = 3, syscallNumber = 0, args = List(6) { 0 }))
        assertEquals(AttributionStatus.CAUSAL_UNCERTAIN, (innerSyscall as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(null, innerSyscall.observation.invocationId)

        // Restore inner → outer (depth=1)
        machine.accept(InvocationEvent.EndRestore(task, sequence = 4, endedInvocationId = InvocationId(202), restoredInvocationId = InvocationId(101)))

        // Syscall in restored outer scope (depth=1) must be PENDING_DICTIONARY with 101, not contaminated by inner
        val outerSyscall = machine.accept(InvocationEvent.Syscall(task, sequence = 5, syscallNumber = 1, args = List(6) { 0 }))
        assertEquals(AttributionStatus.PENDING_DICTIONARY, (outerSyscall as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(InvocationId(101), outerSyscall.observation.invocationId)
    }

    @Test
    fun `detach and exec invalidation require validated re-entry before exact attribution`() {
        val task = TaskIdentity(processEpoch = 1, tid = 42, taskEpoch = 1)
        val machine = InvocationReplayMachine()

        machine.accept(InvocationEvent.Activate(task, 1, InvocationId(101)))
        val detach = machine.accept(InvocationEvent.Invalidate(task, 2, StateInvalidationReason.DETACH))
        val afterDetach = machine.accept(InvocationEvent.Syscall(task, 3, 0, List(6) { 0 })) as ReplayResult.Syscall
        assertEquals(StateInvalidated(task, 2, 2, StateInvalidationReason.DETACH), detach.invalidated)
        assertEquals(AttributionStatus.CAUSAL_UNCERTAIN, afterDetach.observation.attributionStatus)
        assertEquals(null, afterDetach.observation.invocationId)

        machine.accept(InvocationEvent.Activate(task, 4, InvocationId(202)))
        val exact = machine.accept(InvocationEvent.Syscall(task, 5, 1, List(6) { 0 })) as ReplayResult.Syscall
        assertEquals(InvocationId(202), exact.observation.invocationId)

        machine.accept(InvocationEvent.Invalidate(task, 6, StateInvalidationReason.EXEC_TRANSITION))
        machine.terminate()
        val afterTerminal = machine.accept(InvocationEvent.Syscall(task, 7, 2, List(6) { 0 })) as ReplayResult.Syscall
        assertEquals(AttributionStatus.NO_ACTIVE_INVOCATION, afterTerminal.observation.attributionStatus)
    }

    @Test
    fun `pid reuse with new process epoch evicts stale state and resets attribution`() {
        val tid = 42
        val oldTask = TaskIdentity(processEpoch = 1, tid = tid, taskEpoch = 1)
        val newTask = TaskIdentity(processEpoch = 2, tid = tid, taskEpoch = 1)
        val machine = InvocationReplayMachine()

        // Old incarnation: activate + syscall
        machine.accept(InvocationEvent.Activate(oldTask, sequence = 1, invocationId = InvocationId(101)))
        val oldSyscall = machine.accept(InvocationEvent.Syscall(oldTask, sequence = 2, syscallNumber = 257, args = List(6) { 0 }))
        assertEquals(AttributionStatus.PENDING_DICTIONARY, (oldSyscall as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(InvocationId(101), oldSyscall.observation.invocationId)
        assertEquals(ProcessIncarnation(1L), machine.processIncarnationFor(oldTask))

        // PID recycled: new processEpoch=2, same tid — stale state for epoch=1 must be evicted
        val newSyscall = machine.accept(InvocationEvent.Syscall(newTask, sequence = 1, syscallNumber = 0, args = List(6) { 0 }))

        // New task has no active invocation — eviction cleared the old context
        assertEquals(AttributionStatus.NO_ACTIVE_INVOCATION, (newSyscall as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(null, newSyscall.observation.invocationId)
        assertEquals(ProcessIncarnation(2L), machine.processIncarnationFor(newTask))

        // Old incarnation state must no longer be present
        assertEquals(ReentrantBoundaryState(depth = 0, isReentrant = false), machine.reentrantStateFor(oldTask))
    }
}
