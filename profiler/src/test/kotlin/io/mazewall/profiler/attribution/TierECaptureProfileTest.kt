package io.mazewall.profiler.internal.attribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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

    @Test
    fun `application presets use only canonical manifest bindings`() {
        assertEquals(
            "capture-bindings=unix-open0+unix-openat0+unix-close0+unix-unlink0",
            TierECaptureProfilePreset.FILESYSTEM_METADATA.profile.agentOption,
        )
        assertEquals(
            "capture-bindings=socket-read0+socket-write0+net-socket0+net-connect0+net-accept",
            TierECaptureProfilePreset.NETWORK_IO.profile.agentOption,
        )
    }

    @Test
    fun `profile identity is deterministic and selector-order independent`() {
        val first = TierECaptureProfile.selected(TierECaptureBinding.NET_CONNECT0, TierECaptureBinding.UNIX_OPENAT0)
        val second = TierECaptureProfile.selected(TierECaptureBinding.UNIX_OPENAT0, TierECaptureBinding.NET_CONNECT0)

        assertEquals(first.profileId, second.profileId)
        assertEquals(first.selectorDigest, second.selectorDigest)
        assertNotEquals(TierECaptureProfile.Full.selectorDigest, first.selectorDigest)
    }

    @Test
    fun `binding frame matching uses the exact native manifest signature`() {
        assertEquals(
            true,
            TierECaptureBinding.NET_CONNECT0.matchesFrame(
                "sun.nio.ch.Net",
                "connect0",
                "(ZLjava/io/FileDescriptor;Ljava/net/InetAddress;I)I",
            ),
        )
        assertEquals(
            false,
            TierECaptureBinding.NET_CONNECT0.matchesFrame(
                "sun.nio.ch.Net",
                "connect0",
                "(Ljava/io/FileDescriptor;Ljava/net/InetAddress;I)I",
            ),
        )
    }
}
