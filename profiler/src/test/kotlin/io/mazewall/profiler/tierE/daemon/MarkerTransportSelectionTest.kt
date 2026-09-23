package io.mazewall.profiler.tierE.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MarkerTransportSelectionTest {
    private val note = UsdtProbeNote("mazewall", "stack_marker_v1", "8@%rdi 4@%esi", 0x4001004u, 0x1004, 0u)

    @Test
    fun `auto selects usdt when the versioned note validates`() {
        val selected = selectMarkerTransport(AttachMode.AUTO) { note }

        assertEquals(AttachMode.AUTO, selected.requested)
        assertEquals(AttachMode.USDT, selected.selected)
        assertEquals(note, selected.usdtNote)
        assertNull(selected.fallbackReason)
    }

    @Test
    fun `auto records a compatibility reason when note validation fails`() {
        val selected = selectMarkerTransport(AttachMode.AUTO) {
            throw IllegalArgumentException("missing USDT marker note: mazewall:stack_marker_v1")
        }

        assertEquals(AttachMode.UPROBE, selected.selected)
        assertNull(selected.usdtNote)
        assertEquals("missing USDT marker note: mazewall:stack_marker_v1", selected.fallbackReason)
    }

    @Test
    fun `explicit usdt propagates note validation failure`() {
        assertFailsWith<IllegalArgumentException> {
            selectMarkerTransport(AttachMode.USDT) {
                throw IllegalArgumentException("unsupported USDT marker argument contract")
            }
        }
    }

    @Test
    fun `explicit uprobe does not inspect a usdt note`() {
        val selected = selectMarkerTransport(AttachMode.UPROBE) {
            error("uprobe must not inspect USDT metadata")
        }

        assertEquals(AttachMode.UPROBE, selected.selected)
        assertNull(selected.usdtNote)
        assertNull(selected.fallbackReason)
    }

    @Test
    fun `marker version defaults to v1 and accepts explicit v2`() {
        assertEquals(io.mazewall.profiler.internal.attribution.MarkerVersion.V1, markerVersionFromEnvironment(emptyMap()))
        assertEquals(io.mazewall.profiler.internal.attribution.MarkerVersion.V1, markerVersionFromEnvironment(mapOf("TIER_E_MARKER_VERSION" to "v1")))
        assertEquals(io.mazewall.profiler.internal.attribution.MarkerVersion.V2, markerVersionFromEnvironment(mapOf("TIER_E_MARKER_VERSION" to "v2")))
    }

    @Test
    fun `marker version rejects unknown values`() {
        assertFailsWith<IllegalStateException> {
            markerVersionFromEnvironment(mapOf("TIER_E_MARKER_VERSION" to "v3"))
        }
    }

    @Test
    fun `receipt clock mode defaults to disabled and accepts monotonic`() {
        assertEquals("disabled", receiptClockModeFromEnvironment(emptyMap()))
        assertEquals("disabled", receiptClockModeFromEnvironment(mapOf("TIER_E_COLLECTOR_RECEIPT_CLOCK" to "disabled")))
        assertEquals("monotonic", receiptClockModeFromEnvironment(mapOf("TIER_E_COLLECTOR_RECEIPT_CLOCK" to "monotonic")))
    }

    @Test
    fun `receipt clock mode rejects unknown values`() {
        assertFailsWith<IllegalStateException> {
            receiptClockModeFromEnvironment(mapOf("TIER_E_COLLECTOR_RECEIPT_CLOCK" to "wall"))
        }
    }

    @Test
    fun `recovery mode and map capacity reject unsupported configurations`() {
        assertEquals(io.mazewall.profiler.internal.attribution.CoverageRecoveryMode.DISABLED, coverageRecoveryModeFromEnvironment(emptyMap()))
        assertEquals(
            io.mazewall.profiler.internal.attribution.CoverageRecoveryMode.SHADOW,
            coverageRecoveryModeFromEnvironment(mapOf("TIER_E_COVERAGE_RECOVERY_MODE" to "shadow")),
        )
        assertEquals(4096, recoveryMapCapacityFromEnvironment(emptyMap()))
        assertEquals(8, recoveryMapCapacityFromEnvironment(mapOf("TIER_E_RECOVERY_MAP_CAPACITY" to "8")))
        assertFailsWith<IllegalStateException> { coverageRecoveryModeFromEnvironment(mapOf("TIER_E_COVERAGE_RECOVERY_MODE" to "unknown")) }
        assertFailsWith<IllegalStateException> { recoveryMapCapacityFromEnvironment(mapOf("TIER_E_RECOVERY_MAP_CAPACITY" to "4097")) }
    }
}
