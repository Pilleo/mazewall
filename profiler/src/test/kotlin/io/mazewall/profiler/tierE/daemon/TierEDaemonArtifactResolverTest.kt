package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.SnapshotEnvelope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierEDaemonArtifactResolverTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `resolver derives a root-confined artifact from the attached session`() {
        val resolver = TierEDaemonArtifactResolver(root)

        val artifact = resolver.resolve(AttachedSessionInfo(pid = 4242, sessionTag = 0x4d5a))

        assertEquals(root.resolve("tier-e-4242-4d5a.mzse"), artifact.path)
        assertEquals(SessionEpoch(0x4d5a), artifact.expectedSessionEpoch)
    }

    @Test
    fun `resolver admits only a closed v2 envelope for the expected session`() {
        val resolver = TierEDaemonArtifactResolver(root)
        val artifact = resolver.resolve(AttachedSessionInfo(pid = 4242, sessionTag = 0x4d5a))
        Files.write(
            artifact.path,
            SnapshotEnvelope.encode(
                payload = emptyMZsd(),
                publicationId = 1,
                watermark = 0,
                closed = true,
                sessionEpoch = artifact.expectedSessionEpoch,
            ),
        )

        val contents = resolver.readTerminal(artifact)

        assertEquals(artifact.expectedSessionEpoch, contents.publication?.sessionEpoch)
    }

    @Test
    fun `resolver rejects missing symlinked and stale artifacts`() {
        val resolver = TierEDaemonArtifactResolver(root)
        val artifact = resolver.resolve(AttachedSessionInfo(pid = 4242, sessionTag = 0x4d5a))

        assertFailsWith<IllegalArgumentException> { resolver.readTerminal(artifact) }

        val target = Files.createTempFile("tier-e-artifact-target", ".mzse")
        Files.createSymbolicLink(artifact.path, target)
        assertFailsWith<IllegalArgumentException> { resolver.readTerminal(artifact) }
        Files.delete(artifact.path)

        Files.write(
            artifact.path,
            SnapshotEnvelope.encode(
                payload = emptyMZsd(),
                publicationId = 1,
                watermark = 0,
                closed = true,
                sessionEpoch = SessionEpoch(0x4d5b),
            ),
        )
        assertFailsWith<IllegalArgumentException> { resolver.readTerminal(artifact) }
    }

    private fun emptyMZsd(): ByteArray = byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), 2, 0)
}
