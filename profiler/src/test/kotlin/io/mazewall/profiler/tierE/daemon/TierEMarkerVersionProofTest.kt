package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.MarkerVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierEMarkerVersionProofTest {
    @TempDir lateinit var root: Path

    private fun target() =
        TierERegisteredTarget(
        registrationId = "orders",
        pid = 4242,
        sessionTag = 0x4d5a,
        processIncarnation = 9876,
        markerPath = Path.of("/collector/agent/libmazewall_stack_agent.so"),
        markerOffset = 0x20,
        artifactFileName = "orders.mzse",
        markerProofFileName = "orders.marker-proof",
    )

    private fun proof(
        version: String = "v2",
        incarnation: Long = 9876,
    ) = "version=1\nsession_tag=4d5a\nprocess_starttime=$incarnation\nmarker_version=$version\n"

    @Test fun `bounded agent proof admits exact v2 session and process lifetime`() {
        Files.writeString(root.resolve("orders.marker-proof"), proof())
        val received = TierEDaemonArtifactResolver(root).readMarkerProof(target())
        assertEquals(MarkerVersion.V2, received.markerVersion)
        received.requireSelectedV2(target())
    }

    @Test fun `agent selected v1 or stale incarnation is rejected before engine installation`() {
        val file = root.resolve("orders.marker-proof")
        Files.writeString(file, proof(version = "v1"))
        assertFailsWith<IllegalArgumentException> {
            TierEDaemonArtifactResolver(root).readMarkerProof(target()).requireSelectedV2(target())
        }
        Files.writeString(file, proof(incarnation = 9877))
        assertFailsWith<IllegalArgumentException> {
            TierEDaemonArtifactResolver(root).readMarkerProof(target()).requireSelectedV2(target())
        }
    }

    @Test fun `missing and symlink proof fail closed`() {
        val resolver = TierEDaemonArtifactResolver(root)
        assertFailsWith<IllegalArgumentException> { resolver.readMarkerProof(target()) }
        val other = root.resolve("other.proof")
        Files.writeString(other, proof())
        Files.createSymbolicLink(root.resolve("orders.marker-proof"), other)
        assertFailsWith<IllegalArgumentException> { resolver.readMarkerProof(target()) }
    }
}
