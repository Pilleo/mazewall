package io.mazewall.orchestrator

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence

/**
 * Ports the verified resolution lifecycle (formerly paperclip_telegram_bridge.py
 * sync_git_lifecycle, proven live 2026-08-24): when a board issue reaches `done`,
 * flip its markdown frontmatter to resolved, move it into backlog/resolved/, and
 * commit. Idempotent — a second run finds no source file and exits quietly.
 *
 * Marker handling learned from the Python round: a marker recorded relative to a
 * different working tree escapes the repo; re-anchor by basename inside the real
 * backlog tree and never touch files outside the repository.
 */
class BacklogResolver(
    private val repoRoot: Path,
    private val git: GitRunner,
    private val out: (String) -> Unit = ::println,
    private val err: (String) -> Unit = System.err::println,
) {
    fun resolveIfNeeded(identifier: String, description: String?): Boolean {
        val markerPath = Regex("""mazewall:backlog-file=(\S+)""")
            .find(description.orEmpty())?.groupValues?.get(1)
            ?: return false

        val root = repoRoot.toAbsolutePath().normalize()
        var source = root.resolve(markerPath).normalize()
        val insideRepo = source.startsWith(root)

        if (!insideRepo || !source.exists()) {
            source = findByBasename(root, source.name)
        }
        if (source == null || !source.exists()) {
            // Quiet path: an already-resolved issue hits this on every tick. Only
            // complain when the resolved copy does not exist either (broken link).
            val basename = source?.name ?: markerPath.substringAfterLast('/')
            val alreadyResolved = root.resolve(RESOLVED_DIR).resolve(basename).exists()
            if (!alreadyResolved) {
                err("resolve $identifier: backlog file not found for marker '$markerPath'")
            }
            return false
        }

        val content = source.readText()
        val flipped = STATUS_RE.replaceFirst(content, "status: \"resolved\"")
        if (flipped == content) {
            err("resolve $identifier: no open/in_progress status line in ${source.fileName}")
            return false
        }
        source.writeText(flipped)

        val resolvedDir = root.resolve(RESOLVED_DIR).also { Files.createDirectories(it) }
        val destination = resolvedDir.resolve(source.name)
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)

        // Stage the whole backlog tree: a pathspec limited to resolved/ misses the
        // deletion of the original file, producing commits with both copies while
        // leaving the worktree dirty (fresh checkout would re-ingest it).
        git.run(root, "add", "-A", BACKLOG_DIR)
        val committed = git.run(root, "commit", "-m", "Resolve $identifier")
        if (!committed) {
            // Nothing staged (e.g. dry environment); the move itself already happened.
            out("resolve $identifier: moved but nothing to commit")
        } else {
            out("Resolved $identifier -> $RESOLVED_DIR/${source.name}")
        }
        return true
    }

    private fun findByBasename(root: Path, basename: String): Path? {
        val backlog = root.resolve(BACKLOG_DIR)
        if (!Files.exists(backlog)) return null
        return Files.walk(backlog).use { stream ->
            stream.asSequence()
                .filter { it.name == basename }
                .firstOrNull()
        }
    }

    companion object {
        const val BACKLOG_DIR = "docs/internals/backlog"
        const val RESOLVED_DIR = "$BACKLOG_DIR/resolved"
        val STATUS_RE = Regex("""status:\s*['"]?(?:open|in_progress)['"]?""")
    }
}

/** Seam over git for tests; production shells out to the git binary. */
interface GitRunner {
    /** Returns true when the command exited 0; stderr is surfaced through err on failure. */
    fun run(workdir: Path, vararg args: String): Boolean
}

object ProcessGitRunner : GitRunner {
    override fun run(workdir: Path, vararg args: String): Boolean {
        val process = ProcessBuilder("git", *args)
            .directory(workdir.toFile())
            .start()
        val ok = process.waitFor() == 0
        if (!ok) System.err.println("git ${args.firstOrNull()}: ${process.errorStream.bufferedReader().readText().take(300)}")
        return ok
    }
}
