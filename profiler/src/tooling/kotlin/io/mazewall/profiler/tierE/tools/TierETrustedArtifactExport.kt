package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Validate the complete Docker-copied tree before publishing any file to caller-owned evidence. */
public object TierETrustedArtifactExport {
    public fun publish(
        staging: Path,
        output: Path,
    ) {
        require(Files.isDirectory(staging, NOFOLLOW_LINKS) && Files.isDirectory(output, NOFOLLOW_LINKS)) {
            "Tier E export requires owned directories"
        }
        val entries = Files.walk(staging).use { stream -> stream.toList() }
        require(entries.all { Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS) }) {
            "Tier E export contains a symlink or unsupported file type"
        }

        fun repeatedReadiness(
            entry: Path,
            published: Path,
        ): Boolean =
            staging.relativize(entry).toString() == "attachment-ready" &&
                Files.isRegularFile(entry, NOFOLLOW_LINKS) &&
                Files.isRegularFile(published, NOFOLLOW_LINKS) &&
                Files.size(entry) <= 4096 &&
                Files.size(published) <= 4096 &&
                Files.readAllBytes(entry).contentEquals(Files.readAllBytes(published))
        require(
            entries.drop(1).none { entry ->
            val published = output.resolve(staging.relativize(entry))
            Files.exists(published, NOFOLLOW_LINKS) && !repeatedReadiness(entry, published)
        },
        ) {
            "Tier E export would replace an existing artifact"
        }
        for (entry in entries.drop(1)) {
            val published = output.resolve(staging.relativize(entry))
            if (repeatedReadiness(entry, published)) continue
            if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                Files.createDirectory(published)
            } else {
                Files.copy(entry, published)
            }
        }
    }
}
