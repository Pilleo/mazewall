package io.mazewall.orchestrator

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BacklogResolverTest {

    private fun tempRepo(): Path {
        val root = Files.createTempDirectory("supervisor-resolve-test")
        ProcessBuilder("git", "init", "-q").directory(root.toFile()).start().waitFor()
        ProcessBuilder("git", "config", "user.email", "t@t").directory(root.toFile()).start().waitFor()
        ProcessBuilder("git", "config", "user.name", "t").directory(root.toFile()).start().waitFor()
        return root
    }

    private fun write(root: Path, rel: String, content: String) {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
    }

    private fun git(root: Path, vararg args: String): Boolean =
        ProcessGitRunner.run(root, *args)

    private val frontmatter = """
        ---
        title: "Probe issue"
        status: open
        priority: low
        dependencies: []
        ---

        # Probe body
    """.trimIndent() + "\n"

    private val marker = "<!-- mazewall:backlog-file=docs/internals/backlog/testing/issue-x-probe.md -->"

    @Test
    fun `happy path flips frontmatter, moves to resolved, commits identifier`() {
        val root = tempRepo()
        write(root, "docs/internals/backlog/testing/issue-x-probe.md", "$frontmatter\n$marker\n")
        git(root, "add", "-A"); git(root, "commit", "-m", "init")

        val resolved = BacklogResolver(root, ProcessGitRunner)
            .resolveIfNeeded("MAZ-TEST", "lead $marker tail")

        assertTrue(resolved)
        val moved = root.resolve("docs/internals/backlog/resolved/issue-x-probe.md")
        assertTrue(Files.exists(moved))
        assertFalse(Files.exists(root.resolve("docs/internals/backlog/testing/issue-x-probe.md")))
        assertTrue(Files.readString(moved).contains("status: \"resolved\""))
        val p = ProcessBuilder("git", "log", "-1", "--format=%s").directory(root.toFile()).start()
        val subject = p.inputStream.bufferedReader().readText().trim(); p.waitFor()
        assertEquals("Resolve MAZ-TEST", subject)
    }

    @Test
    fun `out-of-repo sandbox marker is re-anchored by basename`() {
        val root = tempRepo()
        write(root, "docs/internals/backlog/security/issue-y-escape.md", "$frontmatter\n")
        git(root, "add", "-A"); git(root, "commit", "-m", "init")

        val escapedMarker = "<!-- mazewall:backlog-file=../../../../../../tmp/opencode/sandbox/issue-y-escape.md -->"
        val resolved = BacklogResolver(root, ProcessGitRunner)
            .resolveIfNeeded("MAZ-ESC", escapedMarker)

        assertTrue(resolved)
        assertTrue(Files.exists(root.resolve("docs/internals/backlog/resolved/issue-y-escape.md")))
    }

    @Test
    fun `missing everywhere is a quiet false - never invents work`() {
        val root = tempRepo()
        git(root, "commit", "--allow-empty", "-q", "-m", "init")
        val resolved = BacklogResolver(root, ProcessGitRunner)
            .resolveIfNeeded("MAZ-GONE", "<!-- mazewall:backlog-file=docs/internals/backlog/nope.md -->")
        assertFalse(resolved)
    }

    @Test
    fun `second run is a no-op once the file sits in resolved`() {
        val root = tempRepo()
        val twiceMarker = "<!-- mazewall:backlog-file=docs/internals/backlog/testing/issue-z-twice.md -->"
        write(root, "docs/internals/backlog/testing/issue-z-twice.md", "$frontmatter\n$twiceMarker\n")
        git(root, "add", "-A"); git(root, "commit", "-m", "init")
        val resolver = BacklogResolver(root, ProcessGitRunner)

        assertTrue(resolver.resolveIfNeeded("MAZ-TWICE", twiceMarker))
        assertFalse(resolver.resolveIfNeeded("MAZ-TWICE", twiceMarker))
        assertEquals(
            2,
            runCatching { countCommits(root) }.getOrDefault(-1),
        )
    }

    @Test
    fun `in_progress and quoted statuses also flip`() {
        for (variant in listOf("status: in_progress", "status: \"open\"", "status: 'open'")) {
            val root = tempRepo()
            write(root, "docs/internals/backlog/testing/i-v.md", "---\ntitle: t\n$variant\n---\nbody")
            git(root, "add", "-A"); git(root, "commit", "-m", "init")
            val ok = BacklogResolver(root, ProcessGitRunner).resolveIfNeeded(
                "MAZ-V",
                "<!-- mazewall:backlog-file=docs/internals/backlog/testing/i-v.md -->",
            )
            assertTrue(ok, "variant failed: $variant")
            assertTrue(
                Files.readString(root.resolve("docs/internals/backlog/resolved/i-v.md"))
                    .contains("status: \"resolved\""),
            )
        }
    }

    private fun countCommits(root: Path): Int {
        val p = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(root.toFile()).start()
        return p.inputStream.bufferedReader().readText().trim().toInt().also { p.waitFor() }
    }
}
