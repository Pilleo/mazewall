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
    val generation: String = generationDirect(path.identity, origin.nestedEntry, tree?.generation, inputs)

    fun validate() {
        if (tree == null) {
            validateFlat(type, path, sha256, origin, inputs)
            return
        }
        path.validate()
        requireBudget(path)
        tree.validate()
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
        requireSelectedBytes(selected, pinned)
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
        private fun generationDirect(
            identity: String,
            nested: String?,
            tree: String?,
            inputs: List<String>,
        ): String = listOf(identity, nested.orEmpty(), tree.orEmpty(), inputs.joinToString("\n")).joinToString("\u0000")

        internal fun metadataDirect(
            type: Class<*>,
            path: PreparedPath,
            entries: Map<String, PreparedPath>?,
        ): Pair<String, String> {
            val origin = DeploymentOrigin.of(type)
            val digest = if (entries == null) ArtifactBytes.digest(path.descriptorPath()) else ExplodedArtifact.verifyDirect(path, entries, type.name.replace('.', '/') + ".class").first
            val tree = entries?.let { ExplodedArtifact.generationDirect(it) }
            return digest to generationDirect(path.identity, origin.nestedEntry, tree, origin.loaderInputs(type))
        }

        private fun validateFlat(
            type: Class<*>,
            path: PreparedPath,
            sha256: String,
            origin: DeploymentOrigin,
            inputs: List<String>,
        ) {
            path.validate()
            requireBudget(path)
            check(ArtifactBytes.digest(path.descriptorPath()) == sha256) { "Prepared implementation bytes changed: ${path.requested}" }
            origin.validate(type, path)
            check(origin.loaderInputs(type) == inputs) { "Selected classloader inputs changed" }
            val pinned = if (origin.boot) BootArtifact.selectedBytes(type, origin, path) else DeploymentOrigin.ordinaryBytes(type, path)
            requireSelectedBytes(type, pinned)
        }

        private fun requireSelectedBytes(
            selected: Class<*>,
            pinned: ByteArray,
        ) {
            require(pinned.contentEquals(DeploymentOrigin.selectedBytes(selected))) { "Selected loader resource bytes disagree with pinned deployment" }
        }

        internal fun validateRootDirect(
            type: Class<*>,
            path: PreparedPath,
        ) {
            require(
                path.directory ||
                path.resolved.fileName
                .toString()
                .endsWith(".jar"),
            )
            requireBudget(path)
            DeploymentOrigin.of(type).validate(type, path)
        }

        internal fun verifyDirect(
            type: Class<*>,
            path: PreparedPath,
            entries: Map<String, PreparedPath>?,
        ) {
            val origin = DeploymentOrigin.of(type)
            require(
                path.directory ||
                path.resolved.fileName
                .toString()
                .endsWith(".jar"),
            )
            requireBudget(path)
            origin.validate(type, path)
            val inputs = origin.loaderInputs(type)
            if (entries == null) {
                val digest = ArtifactBytes.digest(path.descriptorPath())
                validateFlat(type, path, digest, origin, inputs)
            } else {
                path.validate()
                val (_, pinned) = ExplodedArtifact.verifyDirect(path, entries, type.name.replace('.', '/') + ".class")
                origin.validate(type, path)
                check(origin.loaderInputs(type) == inputs)
                requireSelectedBytes(type, pinned)
            }
        }

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
