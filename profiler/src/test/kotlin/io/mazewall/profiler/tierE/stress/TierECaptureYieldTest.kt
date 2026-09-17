package io.mazewall.profiler.tierE.stress

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierECaptureYieldTest {
    @Test
    fun `decodes bounded terminal capture yield records`() {
        val file = Files.createTempFile("tier-e-capture-yield", ".mzcy")
        try {
            Files.write(
                file,
                TierECaptureYield.encodeForTest(
                    0x4d5a,
                    listOf(
                        TierECaptureYield.Record(1, 1, TierECaptureYield.Outcome.CAPTURED),
                        TierECaptureYield.Record(2, 12, TierECaptureYield.Outcome.CAPTURE_FAILED),
                    ),
                ),
            )
            val report = TierECaptureYield.read(file)
            assertEquals(0x4d5a, report.sessionTag)
            assertEquals(2, report.records.size)
            assertEquals(1, report.captured)
            assertEquals(1, report.captureFailed)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rejects a record count that exceeds the bounded file length`() {
        val file = Files.createTempFile("tier-e-capture-yield", ".mzcy")
        try {
            val bytes = TierECaptureYield.encodeForTest(1, emptyList())
            bytes[16] = 1
            Files.write(file, bytes)
            assertFailsWith<IllegalArgumentException> { TierECaptureYield.read(file) }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
