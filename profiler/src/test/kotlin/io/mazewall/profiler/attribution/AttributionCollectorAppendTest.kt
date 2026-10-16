package io.mazewall.profiler.internal.attribution

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AttributionCollectorAppendTest {
    @Test
    fun `append path preserves public collector evidence order`() {
        val stackId = StackTraceId(7)
        val invocationId = InvocationId(11)
        val stack = StackDefinition(
            stackId,
            listOf(ManagedFrame("loader", "app/Service", "open", "()V", 9, ManagedFrameKind.JAVA)),
            StackCaptureQuality.COMPLETE,
        )
        val invocation = InvocationDefinition(invocationId, null, stackId)
        val observation = SyscallAttribution(
            task = TaskIdentity(processEpoch = 1, tid = 2, taskEpoch = 3),
            taskSequence = 4,
            syscallNumber = 257,
            args = List(6) { 0 },
            invocationId = invocationId,
            attributionStatus = AttributionStatus.PENDING_DICTIONARY,
        )
        val publicCollector = AttributionCollector()
        val appendCollector = AttributionCollector(knownJoinMode = TierEKnownJoinMode.DIRECT_KNOWN)

        publicCollector.accept(stack)
        publicCollector.accept(invocation)
        appendCollector.accept(stack)
        appendCollector.accept(invocation)

        val expected = publicCollector.accept(observation)
        val actual = ArrayList<ResolvedSyscall>()
        appendCollector.acceptInto(observation, actual)

        assertEquals(expected, actual)
    }
}
