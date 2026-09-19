package io.mazewall.profiler.internal.attribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierECaptureProfileTest {
    @Test
    fun `full capture omits the native agent option`() {
        assertEquals(emptyList(), TierECaptureProfile.Full.bindings)
        assertEquals(null, TierECaptureProfile.Full.agentOption)
    }

    @Test
    fun `selected bindings render in manifest order`() {
        val profile = TierECaptureProfile.selected(
            TierECaptureBinding.NET_CONNECT0,
            TierECaptureBinding.UNIX_OPENAT0,
        )

        assertEquals(
            "capture-bindings=unix-openat0+net-connect0",
            profile.agentOption,
        )
        assertEquals(
            listOf("unix-openat0", "net-connect0"),
            profile.bindings.map(TierECaptureBinding::wireName),
        )
    }

    @Test
    fun `legacy narrow profile retains its compatible agent spelling`() {
        val profile = TierECaptureProfile.fromProperty("jdk25-unix-openat0")

        assertEquals("capture-profile=jdk25-unix-openat0", profile.agentOption)
        assertEquals(listOf(TierECaptureBinding.UNIX_OPENAT0), profile.bindings)
    }

    @Test
    fun `profile decoder rejects empty duplicate and unknown selections`() {
        assertFailsWith<IllegalArgumentException> { TierECaptureProfile.fromProperty("capture-bindings=") }
        assertFailsWith<IllegalArgumentException> {
            TierECaptureProfile.fromProperty("capture-bindings=unix-openat0+unix-openat0")
        }
        assertFailsWith<IllegalArgumentException> { TierECaptureProfile.fromProperty("capture-bindings=unknown") }
    }
}
