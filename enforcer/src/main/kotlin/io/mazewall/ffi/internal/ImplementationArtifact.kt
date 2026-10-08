package io.mazewall.ffi.internal

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.native.NativeEngine
import java.net.JarURLConnection
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile

/** Finished ordinary JAR identity; custom loaders, nested JARs and transformed code are unsupported. */
internal class ImplementationArtifact private constructor(
    val type: Class<*>,
    val path: PreparedPath,
    val sha256: String,
) : AutoCloseable {
    fun validate() {
        path.validate()
        check(digest(path.descriptorPath()) == sha256) { "Prepared implementation bytes changed: ${path.requested}" }
        validateOrigin(type, path.resolved)
        validateSelectedBytes(type, path)
    }

    override fun close() = path.close()

    companion object {
        private const val MAX_ARTIFACT_BYTES = 256L * 1024 * 1024
        private const val MAX_CLASS_BYTES = 16 * 1024 * 1024

        @Suppress("TooGenericExceptionCaught") // genuine acquisition rollback preserves original failure
        fun resolve(
            type: Class<*>,
            native: NativeEngine,
        ): ImplementationArtifact {
            val source = checkNotNull(type.protectionDomain?.codeSource?.location) { "Implementation has no resolvable code source" }
            require(source.protocol == "file") { "Implementation requires an ordinary finished file JAR" }
            val path = PreparedPath.open(Path.of(source.toURI()), native)
            try {
                require(
                    !path.directory &&
                    path.resolved.fileName
                    .toString()
                    .endsWith(".jar"),
                ) { "Nested/exploded implementation origins are not supported by this contract" }
                validateOrigin(type, path.resolved)
                validateSelectedBytes(type, path)
                return ImplementationArtifact(type, path, digest(path.descriptorPath())).also { it.validate() }
            } catch (failure: Throwable) {
                try {
                    path.close()
                } catch (cleanup: Throwable) {
                    throw CleanupFailures.merge(failure, cleanup)
                }
                throw failure
            }
        }

        private fun validateSelectedBytes(
            type: Class<*>,
            path: PreparedPath,
        ) {
            val name = type.name.replace('.', '/') + ".class"
            val selected = checkNotNull(type.classLoader.getResourceAsStream(name)) { "Selected loader cannot read implementation bytes" }.use { it.readNBytes(MAX_CLASS_BYTES + 1) }
            require(selected.size <= MAX_CLASS_BYTES) { "Selected class exceeds bounded identity budget" }
            JarFile(path.descriptorPath().toFile()).use { jar ->
                val entry = checkNotNull(jar.getJarEntry(name)) { "Selected class is absent from its implementation JAR" }
                val pinned = jar.getInputStream(entry).use { it.readNBytes(MAX_CLASS_BYTES + 1) }
                require(pinned.size <= MAX_CLASS_BYTES && selected.contentEquals(pinned)) { "Selected loader resource bytes disagree with pinned JAR" }
            }
        }

        private fun validateOrigin(
            type: Class<*>,
            artifact: Path,
        ) {
            val loader = checkNotNull(type.classLoader) { "Bootstrap implementation is not a prepared application artifact" }
            val ordinaryApplicationLoader = loader === ClassLoader.getSystemClassLoader() &&
                loader.javaClass.classLoader == null &&
                loader.javaClass.name == "jdk.internal.loader.ClassLoaders\$AppClassLoader"
            require(ordinaryApplicationLoader || loader.javaClass == URLClassLoader::class.java) {
                "Implementation classloader is unsupported; requires application loader or ordinary URLClassLoader"
            }
            check(Class.forName(type.name, false, loader) === type) { "Selected classloader does not resolve the actual implementation class" }
            val name = type.name.replace('.', '/') + ".class"
            val resource = checkNotNull(loader.getResource(name)) { "Selected class bytes are unresolved" }
            require(resource.protocol == "jar") { "Selected class resource is not an ordinary JAR entry" }
            val connection = resource.openConnection() as JarURLConnection
            connection.useCaches = false
            require(connection.entryName == name && connection.jarFileURL.protocol == "file" && Path.of(connection.jarFileURL.toURI()).toRealPath() == artifact) {
                "Selected class resource and actual code source disagree"
            }
        }

        private fun digest(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                var count = input.read(buffer)
                while (count >= 0) {
                    total += count
                    require(total <= MAX_ARTIFACT_BYTES) { "Implementation JAR exceeds bounded identity budget" }
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
