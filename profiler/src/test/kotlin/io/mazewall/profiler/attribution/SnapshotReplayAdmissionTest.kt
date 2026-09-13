package io.mazewall.profiler.attribution

import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotReplayAdmissionTest {
    @Test
    fun `identical definitions are idempotent and conflicting identities are rejected`() {
        val ledger = SnapshotReplayAdmission()
        val frame = ManagedFrame("app", "Foo", "bar", "()V", 1L, ManagedFrameKind.JAVA)
        val definition = StackDefinition(
            stackTraceId = StackTraceId(7),
            frames = listOf(frame),
            captureQuality = StackCaptureQuality.COMPLETE,
        )
        val conflicting = StackDefinition(
            stackTraceId = StackTraceId(7),
            frames = listOf(ManagedFrame("app", "Foo", "changed", "()V", 1L, ManagedFrameKind.JAVA)),
            captureQuality = StackCaptureQuality.COMPLETE,
        )

        assertEquals(DefinitionAdmission.NEW, ledger.admit(definition))
        assertEquals(DefinitionAdmission.DUPLICATE_IDENTICAL, ledger.admit(definition))
        assertEquals(DefinitionAdmission.CONFLICTING, ledger.admit(conflicting))
    }
}
