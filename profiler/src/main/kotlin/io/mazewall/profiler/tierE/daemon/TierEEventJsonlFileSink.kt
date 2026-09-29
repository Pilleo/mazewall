package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProfile
import io.mazewall.profiler.internal.attribution.TierETerminalReason
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE

private const val DEFAULT_EVENT_ARTIFACT_MAX_BYTES: Long = 64L * 1024 * 1024

/** One bounded source-session event file; never publishes a partial success. */
internal class TierEEventJsonlFileSink(
    root: Path,
    private val maxBytes: Long = DEFAULT_EVENT_ARTIFACT_MAX_BYTES,
    private val maxRecords: Int = TierEOptions.DEFAULT_RECORD_LIMIT,
) {
    private val root = root.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(this.root)) {
            "Tier E event evidence root must be an existing non-symlink directory"
        }
        require(maxBytes > 0 && maxRecords > 0) { "Tier E event bounds must be positive" }
    }

    fun publish(
        target: TierERegisteredTarget,
        profile: TierEProfile,
        reason: TierETerminalReason,
    ): Path {
        val destination = root.resolve("${target.registrationId}-${target.sessionEpoch.value}.events.jsonl").normalize()
        require(destination.parent == root && !Files.exists(destination, NOFOLLOW_LINKS)) {
            "Tier E event artifact already exists or escaped its evidence root"
        }
        val temporary = Files.createTempFile(root, ".${target.registrationId}-${target.sessionEpoch.value}.", ".tmp")
        // Leave the owned, bounded .tmp for incomplete-run diagnosis on failure.
        Files.newBufferedWriter(temporary, StandardCharsets.UTF_8, WRITE).use { writer ->
            var bytes = 0L

            fun append(row: String) {
                val next = Math.addExact(bytes, row.toByteArray(StandardCharsets.UTF_8).size.toLong() + 1L)
                check(next <= maxBytes) { "Tier E event artifact exceeds $maxBytes bytes" }
                writer.append(row).append('\n')
                bytes = next
            }

            append(TierEEventJsonl.header(target))
            for ((index, event) in profile.syscalls.withIndex()) {
                check(index < maxRecords) { "Tier E event artifact exceeds $maxRecords records" }
                append(TierEEventJsonl.event(target, index + 1, event))
            }
            append(TierEEventJsonl.terminal(target, profile, reason))
        }
        java.nio.channels.FileChannel
            .open(temporary, WRITE, NOFOLLOW_LINKS)
            .use { it.force(true) }
        // On filesystems without atomic rename, fail closed and keep the .tmp.
        Files.move(temporary, destination, ATOMIC_MOVE)
        return destination
    }
}
