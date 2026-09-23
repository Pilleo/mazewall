package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Resolves definition artifacts from daemon-owned configuration and an attached session.
 *
 * Control-protocol v1 never supplies a filesystem path. Operators configure the native agent to
 * publish into this root using the same `tier-e-<pid>-<session-tag>.mzse` convention.
 */
internal class TierEDaemonArtifactResolver(
    root: Path,
) {
    private val root: Path = root.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            "Tier E definition artifact root must be an existing directory"
        }
        require(!Files.isSymbolicLink(this.root)) { "Tier E definition artifact root must not be a symbolic link" }
    }

    fun resolve(session: AttachedSessionInfo): TierEDefinitionArtifact {
        require(session.pid > 0) { "attached pid must be positive" }
        require(session.sessionTag > 0) { "attached session tag must be positive" }
        val epoch = SessionEpoch(session.sessionTag.toLong())
        val path = root.resolve("tier-e-${session.pid}-${session.sessionTag.toString(16)}.mzse").normalize()
        require(path.parent == root) { "Tier E definition artifact escaped its configured root" }
        return TierEDefinitionArtifact(path, epoch)
    }

    /** Registered deployments use the operator-approved artifact name, never a namespace PID convention. */
    fun resolve(target: TierERegisteredTarget): TierEDefinitionArtifact {
        val path = root.resolve(target.artifactFileName).normalize()
        require(path.parent == root) { "Tier E registered artifact escaped its configured root" }
        return TierEDefinitionArtifact(path, target.sessionEpoch)
    }

    /** Reads the agent-selected version before BPF installation, with one bounded no-follow FD. */
    fun readMarkerProof(target: TierERegisteredTarget): TierEMarkerVersionProof {
        val name = requireNotNull(target.markerProofFileName) { "registered v2 target lacks marker proof file" }
        val path = root.resolve(name).normalize()
        require(path.parent == root && !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "marker-version proof must be a regular file under the registered artifact root"
        }
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            require(size in 1..MAX_MARKER_PROOF_BYTES) { "marker-version proof exceeds bounded size" }
            val bytes = ByteBuffer.allocate(size.toInt())
            while (bytes.hasRemaining()) check(channel.read(bytes) > 0) { "marker-version proof changed while reading" }
            TierEMarkerVersionProof.decode(bytes.array().toString(Charsets.UTF_8))
        }
    }

    private companion object {
        const val MAX_MARKER_PROOF_BYTES = 512L
    }

    /** Reads and validates a terminal artifact through one no-follow descriptor. */
    fun readTerminal(artifact: TierEDefinitionArtifact): AgentDefinitionFile.Contents {
        val path = artifact.path.toAbsolutePath().normalize()
        require(path.parent == root) { "Tier E definition artifact is outside its configured root" }
        require(!Files.isSymbolicLink(path)) { "Tier E definition artifact must not be a symbolic link" }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Tier E definition artifact must be an existing regular file"
        }
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            AgentDefinitionFile.readForSession(channel, artifact.expectedSessionEpoch).also { contents ->
                require(contents.publication?.closed == true) {
                    "Tier E definition artifact must acknowledge producer closure"
                }
            }
        }
    }
}
