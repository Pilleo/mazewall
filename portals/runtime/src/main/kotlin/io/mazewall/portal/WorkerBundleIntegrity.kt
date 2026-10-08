package io.mazewall.portal

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Associates the complete stable cache file set/content with entries of the verified ZIP. */
internal object WorkerBundleIntegrity {
    private const val MAX_ARCHIVE_ENTRIES = 256
    private const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024

    fun verify(
        source: Path,
        directory: Path,
        classpath: List<Path>,
        budget: PortalMeasurementBudget,
    ) {
        val expected = linkedMapOf<Path, String>()
        var expanded = 0L
        budget.check()
        ZipFile(source.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                budget.entry()
                require(expected.size < MAX_ARCHIVE_ENTRIES) { "worker archive has too many entries" }
                val entry = entries.nextElement()
                val relative = archivePath(entry)
                val digest = zip.getInputStream(entry).use { input ->
                    hash(input, budget) {
                    expanded += it
                    require(expanded <= MAX_EXPANDED_BYTES) { "worker archive expanded bound exceeded" }
                }
                }
                require(expected.put(relative, digest) == null) { "duplicate worker archive entry" }
            }
        }
        val actual = linkedSetOf<Path>()
        Files.walk(directory, PortalMeasurementBudget.MAX_DEPTH).use { files ->
            val iterator = files.iterator()
            while (iterator.hasNext()) {
                budget.entry()
                val path = iterator.next()
                require(!Files.isSymbolicLink(path)) { "symbolic link in worker cache" }
                if (Files.isDirectory(path)) {
                    require(directory.relativize(path).nameCount < PortalMeasurementBudget.MAX_DEPTH) { "worker cache exceeds depth bound" }
                    continue
                }
                val relative = directory.relativize(path)
                if (relative.toString() == ".mazewall-worker-bundle") continue
                require(Files.isRegularFile(path)) { "nonregular worker cache entry" }
                val expectedDigest = requireNotNull(expected[relative]) { "unexpected worker cache entry: $relative" }
                require(expectedDigest == budget.file(path) { hash(it, budget) }) { "worker cache entry changed: $relative" }
                actual.add(relative)
            }
        }
        require(actual == expected.keys) { "worker cache has missing archive entries" }
        val expectedClasspath = expected.keys
            .filter { it.nameCount == 2 && it.getName(0).toString() == "lib" && it.fileName.toString().endsWith(".jar") }
            .sorted()
            .map(directory::resolve)
        require(classpath == expectedClasspath && classpath.isNotEmpty()) { "worker classpath differs from verified archive entries" }
    }

    private fun archivePath(entry: java.util.zip.ZipEntry): Path {
        val path = Path.of(entry.name)
        require(
            !entry.isDirectory &&
            !path.isAbsolute &&
            '\\' !in entry.name &&
            path.none {
            it.toString() == ".."
        } &&
            path.normalize().toString() != ".mazewall-worker-bundle",
        ) { "unsafe worker archive entry" }
        return path.normalize()
    }

    private fun hash(
        input: InputStream,
        budget: PortalMeasurementBudget,
        consume: (Long) -> Unit = {},
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = budget.read(input, buffer)
        while (count >= 0) {
            consume(count.toLong())
            digest.update(buffer, 0, count)
            count = budget.read(input, buffer)
        }
        return java.util.HexFormat
            .of()
            .formatHex(digest.digest())
    }
}
