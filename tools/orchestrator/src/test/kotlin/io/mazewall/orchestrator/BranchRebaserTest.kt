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
                if (cmd.contains("body")) return@BranchRebaser "Resolves issue-20260727-140934-some-issue"
                                ""
            },
            executeInDir = { dir, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDir: $cmd")
                ""
            },
            executeInDirNoRetry = { dir, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDirNoRetry: $cmd")
                if (cmd.startsWith("git worktree add")) {
                    java.io.File(args[3]).mkdirs()
                }
                if (cmd.startsWith("git diff --staged --quiet")) throw RuntimeException("Has changes")
                ""
            },
            clearPrCache = { clearCacheCalled = true }
        )

        val worktreeDir = java.io.File("../temp-rebase-123")
        worktreeDir.mkdirs()

        val result = try {
            rebaser.run("123", "session_abc", listOf("test_target.txt"))
        } finally {
            worktreeDir.deleteRecursively()
                    }

        println(commands)

        assertTrue(clearCacheCalled)
        assertTrue(result.success)
        assertEquals(0, result.conflictCount)
        assertFalse(result.needsRescueApproval)
        assertNull(result.rescueBranchName)
        assertTrue(commands.contains("inDirNoRetry: git commit --no-verify -m chore: rescue clean intended files for PR #123 onto master"))
    }

    @Test
    fun `test merge aborts on normal conflict`() {

        val rebaser = BranchRebaser(
            execute = { args ->
                val cmd = args.joinToString(" ")
                when (cmd) {
                    "gh pr view 123 --json headRefName --jq .headRefName" -> "test-branch"
                    "gh pr view 123 --json body --jq .body" -> "Resolves issue-20260727-140934-some-issue"
                    "find docs/internals/backlog -name issue-20260727-140934-some-issue.md" -> ""
                    else -> ""
                }
            },
            executeInDir = { _, _ -> "" },
            executeInDirNoRetry = { _, args ->
                val cmd = args.joinToString(" ")
                if (cmd == "git checkout origin/test-branch -- test_target.txt") throw RuntimeException("Extraction conflict")
                if (cmd.startsWith("git diff --staged --quiet")) throw RuntimeException("Has changes")
                ""
            },
            clearPrCache = {}
        )

        val worktreeDir = java.io.File("../temp-rebase-123")
        worktreeDir.mkdirs()

        val result = try {
            rebaser.run("123", "session_abc", listOf("test_target.txt"))
        } finally {
            worktreeDir.deleteRecursively()
                    }

        assertFalse(result.success)
        assertTrue(result.conflictCount > 0)
        assertFalse(result.needsRescueApproval)
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
                    "gh pr view 123 --json body --jq .body" -> "Resolves issue-20260727-140934-some-issue"
                    "find docs/internals/backlog -name issue-20260727-140934-some-issue.md" -> ""
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
                if (cmd == "git checkout origin/test-branch -- test_target.txt") throw RuntimeException("Extraction conflict")
                ""
            },
            clearPrCache = {}
        )

        val worktreeDir = java.io.File("../temp-rebase-123")
        worktreeDir.mkdirs()

        val result = try {
            rebaser.run("123", "session_abc", listOf("test_target.txt"))
        } finally {
            worktreeDir.deleteRecursively()
                    }

        assertFalse(result.success)
        assertTrue(result.conflictCount > 0)
    }
}
