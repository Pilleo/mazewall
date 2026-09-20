package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

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

    /** Reads a closed v2 envelope only after checking the path has not become a symlink. */
    fun readTerminal(artifact: TierEDefinitionArtifact): AgentDefinitionFile.Contents {
        val path = artifact.path.toAbsolutePath().normalize()
        require(path.parent == root) { "Tier E definition artifact is outside its configured root" }
        require(!Files.isSymbolicLink(path)) { "Tier E definition artifact must not be a symbolic link" }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Tier E definition artifact must be an existing regular file"
        }
        return AgentDefinitionFile.readForSession(path, artifact.expectedSessionEpoch).also { contents ->
            require(contents.publication?.closed == true) {
                "Tier E definition artifact must acknowledge producer closure"
            }
        }
    }
}
