package io.mazewall.verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.security.MessageDigest
import java.time.Duration

internal object ConsumerFixture {
    fun optionalIntegrationEvidence(workspace: Path): Path {
        var parent = workspace.toRealPath()
        for (name in listOf("build", "reports", "optional-integrations")) {
            parent = parent.resolve(name)
            require(!Files.isSymbolicLink(parent)) { "Diagnostic directory must not be an alias: $parent" }
            if (!Files.exists(parent, NOFOLLOW_LINKS)) Files.createDirectory(parent)
            require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Diagnostic parent must be a directory: $parent" }
        }
        return Files.createTempDirectory(parent, "run-")
    }

    fun retainTestReports(
        project: Path,
        evidence: Path,
    ) {
        for (name in listOf("test-results", "reports/tests")) {
            val source = project.resolve("build/$name")
            if (!Files.exists(source, NOFOLLOW_LINKS)) continue
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    require(!Files.isSymbolicLink(path)) { "Test report alias refused: $path" }
                    val target = evidence.resolve(name).resolve(source.relativize(path))
                    if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                        Files.createDirectories(target)
                    } else {
                        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Unsupported report entry: $path" }
                        Files.copy(path, target, COPY_ATTRIBUTES)
                    }
                }
            }
        }
    }

    fun snapshot(root: Path): Map<String, String> =
        sourcePaths(root).associateWith { name ->
        val source = root.resolve(name)
        when {
            Files.isSymbolicLink(source) -> "link:${Files.readSymbolicLink(source)}"
            Files.isRegularFile(
                source,
                NOFOLLOW_LINKS,
            ) -> "${Files.isExecutable(source)}:${MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)).joinToString("") { "%02x".format(it) }}"
            !Files.exists(source, NOFOLLOW_LINKS) -> "missing"
            else -> error("Unsupported consumer fixture source: $source")
        }
    }

    fun checkout(root: Path): Path {
        val parent = Files.createTempDirectory("mazewall-consumer-bootstrap-")
        val clone = parent.resolve("checkout")
        val result = command(listOf("git", "clone", "--shared", "--no-hardlinks", root.toString(), clone.toString()), root, parent, "clone")
        check(result.exitCode == 0) { "Cannot acquire isolated checkout: ${result.stderr}; evidence=$parent" }
        for (name in sourcePaths(root)) {
            val relative = Path.of(name)
            require(!relative.isAbsolute && relative.normalize() == relative && !relative.startsWith("..")) { "Invalid Git fixture source path: $name" }
            val source = root.resolve(relative)
            val target = clone.resolve(relative)
            Files.createDirectories(target.parent)
            require(target.parent.toRealPath().startsWith(clone.toRealPath())) { "Fixture source escapes clone: $target" }
            Files.deleteIfExists(target)
            if (Files.exists(source, NOFOLLOW_LINKS)) Files.copy(source, target, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
        }
        check(snapshot(root) == snapshot(clone)) { "Disposable checkout source/mode inventory differs: $clone" }
        return clone
    }

    fun command(
        arguments: List<String>,
        root: Path,
        evidence: Path,
        label: String,
        timeout: Duration = Duration.ofMinutes(4),
    ): ConsumerCommandResult = ConsumerProcessEvidence.run(arguments, root, label, timeout, evidence)

    private fun sourcePaths(root: Path): List<String> {
        val evidence = Files.createTempDirectory("mazewall-consumer-source-inventory-")
        val result = command(listOf("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"), root, evidence, "source-paths")
        check(result.exitCode == 0) { "Cannot inventory actual fixture sources: ${result.stderr}" }
        return result.stdout
            .split('\u0000')
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
    }
}
