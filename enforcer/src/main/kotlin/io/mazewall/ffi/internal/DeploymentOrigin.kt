package io.mazewall.ffi.internal

import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarFile

/** Origin grammar is closed: ordinary file/JAR or modern Boot nested URL in one finished JAR. */
internal data class DeploymentOrigin(
    val requested: Path,
    val nestedEntry: String?,
) {
    val boot: Boolean get() = nestedEntry != null

    fun validate(
        type: Class<*>,
        path: PreparedPath,
    ) {
        require(of(type) == this) { "Selected implementation origin changed" }
        val loader = checkNotNull(type.classLoader) { "Bootstrap implementation is not a prepared application artifact" }
        check(Class.forName(type.name, false, loader) === type) { "Selected loader does not resolve actual implementation" }
        if (boot) BootArtifact.validateLoader(loader, this, path) else requireOrdinaryLoader(loader)
        val name = type.name.replace('.', '/') + ".class"
        val resource = checkNotNull(loader.getResource(name)) { "Selected class bytes are unresolved" }
        if (boot) {
            val selected = parseBoot(resource.toExternalForm(), name)
            require(selected == this) { "Selected Boot resource and actual code source disagree" }
        } else if (path.directory) {
            require(resource.protocol == "file" && Path.of(resource.toURI()).toAbsolutePath().normalize() == requested.resolve(name)) {
                "Selected exploded resource and actual code source disagree"
            }
        } else {
            require(resource.protocol == "jar") { "Selected class resource is not an ordinary JAR entry" }
            val connection = resource.openConnection() as JarURLConnection
            connection.useCaches = false
            require(connection.entryName == name && connection.jarFileURL.protocol == "file" && Path.of(connection.jarFileURL.toURI()).toRealPath() == path.resolved) {
                "Selected class resource and actual code source disagree"
            }
        }
    }

    fun loaderInputs(type: Class<*>): List<String> = (type.classLoader as? URLClassLoader)?.urLs?.map { it.toExternalForm() }.orEmpty()

    companion object {
        fun of(type: Class<*>): DeploymentOrigin {
            val source = checkNotNull(type.protectionDomain?.codeSource?.location) { "Implementation has no resolvable code source" }
            return when (source.protocol) {
                "file" -> DeploymentOrigin(Path.of(source.toURI()).toAbsolutePath().normalize(), null)
                "jar" -> parseBoot(source.toExternalForm(), "")
                else -> throw IllegalArgumentException("Unsupported implementation deployment origin: ${source.protocol}")
            }
        }

        fun requireOrdinaryLoader(loader: ClassLoader) {
            val application = loader === ClassLoader.getSystemClassLoader() &&
                loader.javaClass.classLoader == null &&
                loader.javaClass.name == "jdk.internal.loader.ClassLoaders\$AppClassLoader"
            require(application || loader.javaClass == URLClassLoader::class.java) {
                "Implementation classloader is unsupported; requires application loader or ordinary URLClassLoader"
            }
        }

        fun parseBoot(
            text: String,
            selected: String,
        ): DeploymentOrigin {
            require(text.startsWith("jar:nested:") && text.endsWith("!/$selected")) { "Unsupported Boot origin URL" }
            val body = text.removePrefix("jar:nested:").removeSuffix("!/$selected")
            val split = body.indexOf("/!")
            require(split > 0 && body.indexOf("/!", split + 2) == -1) { "Unsupported multiply nested Boot origin" }
            val outer = body.substring(0, split)
            val entry = body.substring(split + 2)
            require(entry == "BOOT-INF/classes/" || entry.startsWith("BOOT-INF/lib/") && entry.endsWith(".jar")) { "Unsupported Boot classpath root" }
            ArtifactBytes.requireEntryName(entry)
            require(!outer.contains('!') && !outer.contains('?') && !outer.contains('#')) { "Ambiguous Boot outer path" }
            val uri = URI("file:$outer")
            val path = Path.of(uri).toAbsolutePath().normalize()
            require(path.isAbsolute && path.fileName.toString().endsWith(".jar")) { "Boot origin requires a finished outer JAR" }
            return DeploymentOrigin(path, entry)
        }

        fun selectedBytes(type: Class<*>): ByteArray {
            val resource = checkNotNull(type.classLoader.getResource(type.name.replace('.', '/') + ".class")) { "Selected loader cannot read implementation bytes" }
            val connection = resource.openConnection()
            connection.useCaches = false
            return connection.getInputStream().use { ArtifactBytes.read(it) }
        }

        fun ordinaryBytes(
            type: Class<*>,
            path: PreparedPath,
        ): ByteArray =
            JarFile(path.descriptorPath().toFile()).use { jar ->
            val name = type.name.replace('.', '/') + ".class"
            val entry = checkNotNull(jar.getJarEntry(name)) { "Selected class is absent from its implementation JAR" }
            jar.getInputStream(entry).use { ArtifactBytes.read(it) }
        }
    }
}
