package io.mazewall.ffi.internal

import java.io.ByteArrayInputStream
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

/** Supported modern Boot 4.1.1 loader bytes, bound to the same reviewed outer artifact. */
internal object BootArtifact {
    private val loaderHashes = linkedMapOf(
        "org.springframework.boot.loader.launch.LaunchedClassLoader" to "3cffc7389dbaf45efbbd3561bcdf637522821725f18c7f4ff34e27fcf02896d1",
        "org.springframework.boot.loader.net.protocol.jar.JarUrlClassLoader" to "c65afe195106c323225e4b4971802bbfc1e36750344c769a0b9856d464f541ea",
    )

    fun validateLoader(
        loader: ClassLoader,
        origin: DeploymentOrigin,
        path: PreparedPath,
    ) {
        var type: Class<*> = loader.javaClass
        loaderHashes.forEach { (name, hash) ->
            require(type.name == name) { "Unsupported Boot loader hierarchy" }
            val defining = checkNotNull(type.classLoader) { "Boot loader has no ordinary defining loader" }
            DeploymentOrigin.requireOrdinaryLoader(defining)
            require(DeploymentOrigin.of(type) == DeploymentOrigin(origin.requested, null)) { "Boot loader is not defined by the selected finished outer artifact" }
            DeploymentOrigin(origin.requested, null).validate(type, path)
            val pinned = DeploymentOrigin.ordinaryBytes(type, path)
            require(ArtifactBytes.hash(pinned) == hash && pinned.contentEquals(DeploymentOrigin.selectedBytes(type))) { "Unsupported Boot loader implementation bytes" }
            type = type.superclass
        }
        require(type == URLClassLoader::class.java && loader is URLClassLoader) { "Unsupported Boot loader superclass" }
        val inputs = loader.urLs.map { DeploymentOrigin.parseBoot(it.toExternalForm(), "") }
        require(inputs.isNotEmpty() && inputs.distinct().size == inputs.size && origin in inputs && inputs.all { it.requested == origin.requested }) {
            "Boot loader inputs escape selected finished artifact"
        }
        JarFile(path.descriptorPath().toFile()).use { jar ->
            validateEntries(jar)
            inputs.forEach { input -> requireNotNull(jar.getJarEntry(input.nestedEntry)) { "Boot loader input is absent from pinned outer artifact" } }
        }
    }

    fun selectedBytes(
        type: Class<*>,
        origin: DeploymentOrigin,
        path: PreparedPath,
    ): ByteArray =
        JarFile(path.descriptorPath().toFile()).use { jar ->
        val name = type.name.replace('.', '/') + ".class"
        val root = checkNotNull(origin.nestedEntry)
        if (root == "BOOT-INF/classes/") {
            val entry = checkNotNull(jar.getJarEntry(root + name)) { "Selected Boot application class is absent" }
            jar.getInputStream(entry).use { ArtifactBytes.read(it) }
        } else {
            val inner = checkNotNull(jar.getJarEntry(root)) { "Selected Boot library is absent" }
            require(inner.method == java.util.zip.ZipEntry.STORED) { "Boot library must be an uncompressed nested JAR" }
            val bytes = jar.getInputStream(inner).use { ArtifactBytes.read(it, ArtifactBytes.MAX_ARTIFACT_BYTES.toInt()) }
            nestedClass(bytes, name)
        }
    }

    private fun nestedClass(
        bytes: ByteArray,
        name: String,
    ): ByteArray =
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
        val central = NestedZipTable(bytes).validate()
        val names = mutableSetOf<String>()
        var selected: ByteArray? = null
        var entry = input.nextEntry
        while (entry != null) {
            ArtifactBytes.requireEntryName(entry.name)
            require(names.size < ArtifactBytes.MAX_ENTRIES && names.add(entry.name)) { "Ambiguous or oversized nested JAR entry table" }
            val record = requireNotNull(central[entry.name]) { "Nested ZIP local entry is absent from central table" }
            val content = ArtifactBytes.read(input, record.size)
            require(content.size == record.size && entry.crc == record.crc && entry.compressedSize == record.compressed.toLong()) { "Nested ZIP streamed content disagrees with central record" }
            if (entry.name == name) {
                require(content.size <= ArtifactBytes.MAX_CLASS_BYTES) { "Selected nested class exceeds bounded identity budget" }
                selected = content
            }
            entry = input.nextEntry
        }
        require(names == central.keys) { "Nested ZIP streamed entry set disagrees with central table" }
        checkNotNull(selected) { "Selected class is absent from pinned nested library" }
    }

    private fun validateEntries(jar: JarFile) {
        val names = mutableSetOf<String>()
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            ArtifactBytes.requireEntryName(entry.name)
            require(names.size < ArtifactBytes.MAX_ENTRIES && names.add(entry.name)) { "Ambiguous or oversized Boot JAR entry table" }
        }
    }
}
