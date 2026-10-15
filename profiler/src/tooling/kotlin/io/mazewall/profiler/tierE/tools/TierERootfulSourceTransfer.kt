package io.mazewall.profiler.tierE.tools

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Git-scoped source archive, including uncommitted and untracked inputs, without shell pipelines. */
public object TierERootfulSourceTransfer {
    private val inputs = listOf(
        "gradle",
        "buildSrc",
        "enforcer",
        "platform",
        "profiler",
        "profiler-native",
        "portals",
        "integrations",
        "tests",
        "demos",
        "infra",
        "config",
        "scripts",
        "src",
        "docs",
        "gradlew",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        ".editorconfig",
    )

    public fun archive(
        repository: Path,
        archive: Path,
    ) {
        val root = repository.toRealPath()
        val names = gitNames(root, "--cached", "--others", "--exclude-standard")
        check(names.isNotEmpty()) { "Git source list unavailable" }
        val deleted = gitNames(root, "--deleted").toSet()
        val list = Files.createTempFile("tier-e-rootful-source-", ".list")
        try {
            Files.newOutputStream(list).use { output ->
                for (name in names.filterNot { it in deleted }) {
                    output.write(name.array(), name.position(), name.remaining())
                    output.write(0)
                }
            }
            val tar = ProcessBuilder("tar", "-C", root.toString(), "-cf", archive.toString(), "--null", "-T", list.toString())
                .redirectErrorStream(true)
                .start()
            val report = tar.inputStream.bufferedReader().use { it.readText() }
            check(tar.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS) && tar.exitValue() == 0) {
                "Tier E source archive failed: $report"
            }
        } finally {
            Files.deleteIfExists(list)
        }
    }

    private fun gitNames(
        repository: Path,
        vararg options: String,
    ): List<ByteBuffer> {
        val errors = Files.createTempFile("tier-e-rootful-git-", ".stderr")
        try {
            val command = listOf("git", "-C", repository.toString(), "ls-files", "-z") + options + listOf("--") + inputs
            val git = ProcessBuilder(command).redirectError(errors.toFile()).start()
            val names = git.inputStream.readNBytes(32 * 1024 * 1024 + 1)
            require(names.size <= 32 * 1024 * 1024) { "Rootful source list exceeds 32 MiB" }
            // Git may report EACCES as deleted with exit zero; diagnostics must fail closed.
            check(git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0 && Files.size(errors) == 0L) {
                "Git source list unavailable: ${Files.newInputStream(errors).use { it.readNBytes(4096) }.toString(Charsets.UTF_8)}"
            }
            val records = mutableListOf<ByteBuffer>()
            var start = 0
            for (end in names.indices) {
                if (names[end] == 0.toByte()) {
                    require(end > start) { "Git source list contains an empty filename" }
                    // ByteBuffer equality compares raw remaining bytes, including non-UTF-8 names.
                    records += ByteBuffer.wrap(names, start, end - start)
                    start = end + 1
                }
            }
            check(start == names.size) { "Git source list is not NUL-terminated" }
            return records
        } finally {
            Files.deleteIfExists(errors)
        }
    }
}
