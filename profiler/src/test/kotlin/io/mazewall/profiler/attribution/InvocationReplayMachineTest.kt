package io.mazewall.profiler.attribution

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

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
            listOf(InvocationId(101), InvocationId(202), InvocationId(101), null),
            observations.map { it.observation.invocationId },
        )
        assertEquals(
            listOf(
                AttributionStatus.PENDING_DICTIONARY,
                AttributionStatus.PENDING_DICTIONARY,
                AttributionStatus.PENDING_DICTIONARY,
                AttributionStatus.NO_ACTIVE_INVOCATION,
            ),
            observations.map { it.observation.attributionStatus },
        )
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

        assertEquals(
            StateInvalidated(task, expectedSequence = 2, actualSequence = 2, reason = StateInvalidationReason.ACTIVATION_MISMATCH),
            mismatch.invalidated,
        )
        assertEquals(AttributionStatus.NO_ACTIVE_INVOCATION, (syscall as ReplayResult.Syscall).observation.attributionStatus)
        assertEquals(null, syscall.observation.invocationId)
    }
}
