package io.mazewall.enforcer.api

import io.mazewall.platform.lifecycle.SandboxedPath

/** Pre-install filesystem intent. Existing descriptors and mappings are not revoked. */
public sealed interface FileSystemPolicy {
    public data object Unrestricted : FileSystemPolicy

    /** Install filesystem restriction even though no resource is granted. */
    public data object DenyAll : FileSystemPolicy

    public class AllowResources(
        readPaths: Set<SandboxedPath>,
        writePaths: Set<SandboxedPath>,
    ) : FileSystemPolicy {
        public val readPaths: Set<SandboxedPath> = java.util.Set.copyOf(readPaths)
        public val writePaths: Set<SandboxedPath> = java.util.Set.copyOf(writePaths)
    }
}

internal fun <S : PolicyScope> PolicyBuilder<S>.setFilesystem(policy: FileSystemPolicy): PolicyBuilder<S> =
    when (policy) {
        FileSystemPolicy.Unrestricted -> replaceFilesystemGrants(restricted = false)
        FileSystemPolicy.DenyAll -> replaceFilesystemGrants(restricted = true)
        is FileSystemPolicy.AllowResources ->
            replaceFilesystemGrants(
                restricted = true,
                readPaths = policy.readPaths,
                writePaths = policy.writePaths,
            )
    }
