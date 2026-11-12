package io.mazewall.profiler.tierE.daemon

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProfile
import io.mazewall.profiler.internal.attribution.TierETerminalReason
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE

private const val DEFAULT_EVENT_ARTIFACT_MAX_BYTES: Long = 64L * 1024 * 1024

/** One bounded source-session event file; never publishes a partial success. */
internal class TierEEventJsonlFileSink(
    root: Path,
    private val maxBytes: Long = DEFAULT_EVENT_ARTIFACT_MAX_BYTES,
    private val maxRecords: Int = TierEOptions.DEFAULT_RECORD_LIMIT,
    access: TierEEvidenceFileAccess = TierEEvidenceFileAccess(),
    private val prepareTemporary: (Path) -> Unit = { access.apply(it) },
    private val publishTemporary: (Path, Path) -> Unit = { temporary, destination -> Files.createLink(destination, temporary) },
) {
    private val root = root.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(this.root)) {
            "Tier E event evidence root must be an existing non-symlink directory"
        }
        require(maxBytes > 0 && maxRecords > 0) { "Tier E event bounds must be positive" }
    }

    @Suppress("TooGenericExceptionCaught") // Restore confidentiality on any failure, preserving fatal-first cleanup ordering.
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
        try {
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
            prepareTemporary(temporary)
            // link(2) atomically creates or fails EEXIST, unlike ATOMIC_MOVE on the Linux provider.
            publishTemporary(temporary, destination)
        } catch (failure: Throwable) {
            try {
                Files.setPosixFilePermissions(temporary, TierEEvidenceFileAccess.OWNER_ONLY)
            } catch (cleanup: Throwable) {
                throw CleanupFailures.merge(failure, cleanup)
            }
            throw failure
        }
        // Publication committed; an unlink error is observable cleanup failure, not partial output.
        Files.delete(temporary)
        return destination
    }
}
