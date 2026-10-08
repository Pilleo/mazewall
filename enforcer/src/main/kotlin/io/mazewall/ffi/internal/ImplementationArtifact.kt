package io.mazewall.ffi.internal

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.native.NativeEngine
import java.nio.file.Files
import java.nio.file.Path

/** Actual selected deployment closure, owned until the existing runtime retirement releases it. */
internal class ImplementationArtifact private constructor(
    val type: Class<*>,
    val path: PreparedPath,
    val sha256: String,
    private val origin: DeploymentOrigin,
    private val tree: ExplodedArtifact?,
    private val inputs: List<String>,
) : AutoCloseable {
    val generation: String = listOf(path.identity, origin.nestedEntry.orEmpty(), tree?.generation.orEmpty(), inputs.joinToString("\n")).joinToString("\u0000")

    fun validate() {
        path.validate()
        requireBudget(path)
        if (tree != null) tree.validate() else check(ArtifactBytes.digest(path.descriptorPath()) == sha256) { "Prepared implementation bytes changed: ${path.requested}" }
        origin.validate(type, path)
        check(origin.loaderInputs(type) == inputs) { "Selected classloader inputs changed" }
        validateSelectedBytes(type)
    }

    fun requireMemberOrigin(declaring: Class<*>) {
        require(declaring.classLoader === type.classLoader && DeploymentOrigin.of(declaring) == origin) { "Reviewed method implementation originates outside the selected artifact" }
        origin.validate(declaring, path)
        validateSelectedBytes(declaring)
    }

    private fun validateSelectedBytes(selected: Class<*>) {
        val pinned = when {
            tree != null -> tree.selectedBytes(selected.name.replace('.', '/') + ".class")
            origin.boot -> BootArtifact.selectedBytes(selected, origin, path)
            else -> DeploymentOrigin.ordinaryBytes(selected, path)
        }
        require(pinned.contentEquals(DeploymentOrigin.selectedBytes(selected))) { "Selected loader resource bytes disagree with pinned deployment" }
    }

    @Suppress("TooGenericExceptionCaught") // all genuine tree/container acquisitions receive a release attempt
    override fun close() {
        var failure: Throwable? = null
        try {
            tree?.close()
        } catch (problem: Throwable) {
            failure = problem
        }
        try {
            path.close()
        } catch (problem: Throwable) {
            failure = CleanupFailures.merge(failure, problem)
        }
        failure?.let { throw it }
    }

    companion object {
        @Suppress("TooGenericExceptionCaught") // rollback cannot erase a fatal original/cleanup outcome
        fun resolve(
            type: Class<*>,
            native: NativeEngine,
            directoryAllowed: (Path) -> Boolean,
        ): ImplementationArtifact {
            val origin = DeploymentOrigin.of(type)
            val path = PreparedPath.open(origin.requested, native)
            var tree: ExplodedArtifact? = null
            try {
                require(
                    path.directory ||
                    path.resolved.fileName
                    .toString()
                    .endsWith(".jar"),
                ) { "Implementation requires a finished JAR or reviewed exploded root" }
                requireBudget(path)
                origin.validate(type, path)
                if (path.directory) {
                    require(directoryAllowed(path.resolved)) { "Exploded implementation requires an explicitly reviewed existing directory read grant" }
                    tree = ExplodedArtifact.capture(path, native)
                }
                val digest = tree?.sha256 ?: ArtifactBytes.digest(path.descriptorPath())
                return ImplementationArtifact(type, path, digest, origin, tree, origin.loaderInputs(type)).also { it.validate() }
            } catch (failure: Throwable) {
                var primary = failure
                try {
                    tree?.close()
                } catch (cleanup: Throwable) {
                    primary = CleanupFailures.merge(primary, cleanup)
                }
                try {
                    path.close()
                } catch (cleanup: Throwable) {
                    primary = CleanupFailures.merge(primary, cleanup)
                }
                throw primary
            }
        }

        private fun requireBudget(path: PreparedPath) {
            if (!path.directory) require(Files.size(path.descriptorPath()) <= ArtifactBytes.MAX_ARTIFACT_BYTES) { "Implementation exceeds bounded identity budget" }
        }
    }
}
