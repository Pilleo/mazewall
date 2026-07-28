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
                if (cmd == "gh pr view 123 --json headRefName --jq .headRefName") "test-branch" else ""
            },
            executeInDir = { dir, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDir: $cmd")
                if (cmd == "git rev-list --count origin/master..HEAD") "1" else ""
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

        val worktreeDir = java.io.File("../temp-rebase-123")
        worktreeDir.mkdirs()
        val result = try {
            rebaser.run("123", "session_abc")
        } finally {
            worktreeDir.deleteRecursively()
        }

        assertTrue(clearCacheCalled)
        assertTrue(result.success)
        assertEquals(0, result.conflictCount)
        assertFalse(result.needsRescueApproval)
        assertNull(result.rescueBranchName)
    }

    @Test
    fun `test merge aborts on normal conflict`() {
        val rebaser = BranchRebaser(
            execute = { args ->
                val cmd = args.joinToString(" ")
                if (cmd == "gh pr view 123 --json headRefName --jq .headRefName") "test-branch" else ""
            },
            executeInDir = { _, _ -> "" },
            executeInDirNoRetry = { _, args ->
                val cmd = args.joinToString(" ")
                if (cmd.startsWith("git merge origin/master")) throw RuntimeException("Merge conflict")
                ""
            },
            clearPrCache = {}
        )

        val worktreeDir = java.io.File("../temp-rebase-123")
        worktreeDir.mkdirs()
        val result = try {
            rebaser.run("123", "session_abc")
        } finally {
            worktreeDir.deleteRecursively()
        }

        assertFalse(result.success)
        assertEquals(1, result.conflictCount)
        assertFalse(result.needsRescueApproval)
    }

    @Test
    fun `test fallback to rescue on unrelated histories`() {
        val commands = mutableListOf<String>()

        val rebaser = BranchRebaser(
            execute = { args ->
                val cmd = args.joinToString(" ")
                commands.add(cmd)
                if (cmd == "gh pr view 123 --json headRefName --jq .headRefName") "test-branch" else ""
            },
            executeInDir = { _, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDir: $cmd")
                if (cmd.startsWith("git diff --staged --quiet")) throw RuntimeException("Has changes")
                if (cmd.startsWith("git push")) { } // Do nothing, just return success
                ""
            },
            executeInDirNoRetry = { _, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDirNoRetry: $cmd")
                if (cmd.startsWith("git worktree add")) {
                    java.io.File(args[3]).mkdirs()
                }
                if (cmd.startsWith("git merge origin/master")) throw RuntimeException("fatal: refusing to merge unrelated histories")
                ""
            },
            clearPrCache = {}
        )

        val worktreeDir = java.io.File("../temp-rebase-123")
        worktreeDir.mkdirs()
        val result = try {
            rebaser.run("123", "session_abc")
        } finally {
            worktreeDir.deleteRecursively()
        }

        assertFalse(result.success)
        assertEquals(0, result.conflictCount)
        assertTrue(result.needsRescueApproval)
        assertEquals("test-branch-rescue", result.rescueBranchName)

        assertTrue(commands.contains("inDir: git reset --hard origin/master"))
        assertTrue(commands.contains("inDirNoRetry: jules remote pull session_abc"))
        assertTrue(commands.contains("inDirNoRetry: git add ."))
        assertTrue(commands.contains("inDir: git commit --no-verify -m chore(orchestrator): rescue PR #123 onto master via jules remote pull"))
        assertTrue(commands.contains("inDir: git push --force origin HEAD:test-branch-rescue"))
    }
}
