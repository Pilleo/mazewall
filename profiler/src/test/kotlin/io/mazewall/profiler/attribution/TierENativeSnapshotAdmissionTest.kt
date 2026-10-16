package io.mazewall.profiler.internal.attribution

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TierENativeSnapshotAdmissionTest {
    private val session = SessionEpoch(0x4d5a)
    private val stack = StackDefinition(StackTraceId(7), emptyList(), StackCaptureQuality.COMPLETE)
    private val invocation = InvocationDefinition(InvocationId(55), null, stack.stackTraceId)

    private fun contents(
        publicationId: Long = 1,
        watermark: Long = 55,
        digest: String = "a".repeat(64),
        sessionEpoch: SessionEpoch = session,
        stacks: List<StackDefinition> = listOf(stack),
        invocations: List<InvocationDefinition> = listOf(invocation),
    ) = AgentDefinitionFile.Contents(
        stacks,
        invocations,
        0,
        publication = SnapshotPublication(publicationId, watermark, false, sessionEpoch),
        sourceByteLength = 100,
        sourceSha256 = digest,
    )

    @Test fun `live committed prefix is idempotent and advances only on new publication`() {
        val admission = TierENativeSnapshotAdmission(session)
        val first = admission.prepare(contents())!!
        assertEquals(1, first.stacks.size)
        assertEquals(1, first.invocations.size)
        admission.commit(first)
        assertNull(admission.prepare(contents()))
        val next = admission.prepare(contents(publicationId = 2, digest = "b".repeat(64)))!!
        assertEquals(0, next.stacks.size)
        assertEquals(0, next.invocations.size)
        admission.commit(next)
        assertEquals(2, admission.publicationCount)
    }

    @Test fun `stale session, replay conflict, missing stack dependency, and regressed watermark reject before admission`() {
        val admission = TierENativeSnapshotAdmission(session)
        assertFailsWith<IllegalArgumentException> { admission.prepare(contents(sessionEpoch = SessionEpoch(1))) }
        assertFailsWith<IllegalArgumentException> { admission.prepare(contents(stacks = emptyList())) }
        assertFailsWith<IllegalArgumentException> { admission.prepare(contents(watermark = 54)) }
        admission.commit(admission.prepare(contents())!!)
        assertFailsWith<IllegalArgumentException> { admission.prepare(contents(digest = "z".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { admission.prepare(contents(publicationId = 2, watermark = 54)) }
        val altered = StackDefinition(stack.stackTraceId, listOf(ManagedFrame("app", "Foo", "other", "()V", 1, ManagedFrameKind.JAVA)), StackCaptureQuality.COMPLETE)
        assertFailsWith<IllegalArgumentException> { admission.prepare(contents(publicationId = 2, digest = "b".repeat(64), stacks = listOf(altered))) }
    }
}
