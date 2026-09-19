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
}
