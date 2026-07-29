package io.mazewall.orchestrator

import kotlin.test.*
import kotlin.test.Test
import java.io.File

class BranchRebaserTest {

    @Test
    fun `test successful merge without rescue`() {
        var clearCacheCalled = false
        val commands = mutableListOf<String>()

        val rebaser = BranchRebaser(
            execute = { args ->
                val cmd = args.joinToString(" ")
                commands.add(cmd)
                if (cmd.contains("headRefName")) return@BranchRebaser "test-branch"
                if (cmd.contains("body")) return@BranchRebaser "Resolves issue-20260727-140934-some-issue.md"
                ""
            },
            executeInDir = { dir, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDir: $cmd")
                if (cmd.contains("rev-list")) "1" else ""
            },
            executeInDirNoRetry = { dir, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDirNoRetry: $cmd")
                if (cmd.startsWith("git worktree add")) {
                    java.io.File(args[3]).mkdirs()
                }
                ""
            },
            clearPrCache = { clearCacheCalled = true }
        )

        val worktreeDir = java.io.File("build/tmp/temp-rebase-123")
        worktreeDir.mkdirs()

        val result = try {
            rebaser.run("123", "session_abc", listOf("test_target.txt"))
        } finally {
            worktreeDir.deleteRecursively()
        }

        assertTrue(clearCacheCalled)
        assertTrue(result.success)
        assertEquals(0, result.conflictCount)
        assertFalse(result.needsRescueApproval)
        assertNull(result.rescueBranchName)
        assertTrue(commands.contains("inDirNoRetry: git merge origin/master --no-edit -m chore: merge master into PR #123 to keep up to date"))
        assertTrue(commands.contains("inDir: git push --force-with-lease origin HEAD:test-branch"))
    }

    @Test
    fun `test merge aborts on normal conflict and asks for rescue`() {
        val rebaser = BranchRebaser(
            execute = { args ->
                val cmd = args.joinToString(" ")
                when (cmd) {
                    "gh pr view 123 --json headRefName --jq .headRefName" -> "test-branch"
                    else -> ""
                }
            },
            executeInDir = { _, _ -> "" },
            executeInDirNoRetry = { _, args ->
                val cmd = args.joinToString(" ")
                if (cmd.startsWith("git worktree add")) {
                    java.io.File(args[3]).mkdirs()
                }
                if (cmd.contains("merge origin/master")) throw RuntimeException("Merge conflict")
                if (cmd.startsWith("git diff --staged --quiet")) throw RuntimeException("Has changes")
                ""
            },
            clearPrCache = {}
        )

        val worktreeDir = java.io.File("build/tmp/temp-rebase-123")
        worktreeDir.mkdirs()

        val result = try {
            rebaser.run("123", "session_abc", listOf("test_target.txt"))
        } finally {
            worktreeDir.deleteRecursively()
        }

        assertFalse(result.success)
        assertEquals(0, result.conflictCount)
        assertTrue(result.needsRescueApproval)
        assertEquals("test-branch-rescue", result.rescueBranchName)
    }

    @Test
    fun `test fallback to rescue on unrelated histories`() {
        val commands = mutableListOf<String>()

        val rebaser = BranchRebaser(
            execute = { args ->
                val cmd = args.joinToString(" ")
                commands.add(cmd)
                when (cmd) {
                    "gh pr view 123 --json headRefName --jq .headRefName" -> "test-branch"
                    else -> ""
                }
            },
            executeInDir = { _, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDir: $cmd")
                ""
            },
            executeInDirNoRetry = { _, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDirNoRetry: $cmd")
                if (cmd.startsWith("git worktree add")) {
                    java.io.File(args[3]).mkdirs()
                }
                if (cmd.contains("merge origin/master")) throw RuntimeException("Unrelated histories")
                if (cmd == "git checkout origin/test-branch -- test_target.txt") throw RuntimeException("Extraction conflict")
                ""
            },
            clearPrCache = {}
        )

        val worktreeDir = java.io.File("build/tmp/temp-rebase-123")
        worktreeDir.mkdirs()

        val result = try {
            rebaser.run("123", "session_abc", listOf("test_target.txt"))
        } finally {
            worktreeDir.deleteRecursively()
        }

        assertFalse(result.success)
        assertEquals(1, result.conflictCount)
    }
}
