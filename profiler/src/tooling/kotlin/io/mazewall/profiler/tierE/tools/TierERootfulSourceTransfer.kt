package io.mazewall.profiler.tierE.tools

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
    )

    public fun archive(
        repository: Path,
        archive: Path,
    ) {
        val root = repository.toRealPath()
        val git = ProcessBuilder(
            listOf(
                "git",
                "-C",
                root.toString(),
                "ls-files",
                "-z",
                "--cached",
                "--others",
            "--exclude-standard",
                "--",
            ) +
            inputs,
        ).redirectErrorStream(true).start()
        val names = git.inputStream.readNBytes(32 * 1024 * 1024 + 1)
        require(names.size <= 32 * 1024 * 1024) { "Rootful source list exceeds 32 MiB" }
        check(git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0 && names.isNotEmpty()) { "Git source list unavailable" }
        val list = Files.createTempFile("tier-e-rootful-source-", ".list")
        try {
            Files.write(list, names)
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
}
