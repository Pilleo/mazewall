package io.mazewall.profiler.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkRawSyscallDiagnosticsTest {
    @Test
    fun `first argument admission retains only configured benchmark oracle records`() {
        val capture = BenchmarkRawSyscallDiagnostics.begin(
            awaitFirstArgumentAdmission = true,
            accepts = { it == "OPENAT" },
        )
        try {
            assertEquals(null, BenchmarkRawSyscallDiagnostics.record(41, "OPENAT", listOf(72, 2, 3)))

            capture.admitFirstArguments(listOf(73))

            assertEquals(null, BenchmarkRawSyscallDiagnostics.record(41, "OPENAT", listOf(72, 2, 3)))
            assertTrue(BenchmarkRawSyscallDiagnostics.record(41, "OPENAT", listOf(73, 2, 3)) != null)
            assertEquals(0, capture.droppedCount)
        } finally {
            capture.finish()
        }
    }

    @Test
    fun `capture correlates a shared operation with listener and return timing`() {
        val capture = BenchmarkRawSyscallDiagnostics.begin { it == "OPENAT" }
        try {
            capture.operationStarted(73)
            val record = checkNotNull(BenchmarkRawSyscallDiagnostics.record(41, "OPENAT", listOf(73, 2, 3)))
            capture.listenerProcessed(record, stackCaptureNanos = 11, queueNanos = 7)
            capture.listenerAcknowledged(record)
            capture.operationReturned(73)

            val timing = capture.timing(record)

            assertTrue(timing.operationStartedNanos > 0)
            assertTrue(timing.listenerReceivedNanos >= timing.operationStartedNanos)
            assertEquals(11, timing.stackCaptureNanos)
            assertEquals(7, timing.queueNanos)
            assertTrue(timing.acknowledgedNanos >= timing.listenerReceivedNanos)
            assertTrue(timing.operationReturnedNanos >= timing.operationStartedNanos)
        } finally {
            capture.finish()
        }
    }
}
