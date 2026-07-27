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
                if (cmd == "gh pr view 123 --json headRefName --jq .headRefName") "test-branch"
                else ""
            },
            executeInDir = { dir, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDir: $cmd")
                if (cmd == "git rev-list --count origin/master..HEAD") "1"
                else if (cmd == "git diff --name-only origin/master") "allowed.txt\ndisallowed.txt"
                else ""
            },
            executeInDirNoRetry = { dir, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDirNoRetry: $cmd")
                ""
            },
            clearPrCache = { clearCacheCalled = true }
        )

        val result = rebaser.run("123", listOf("allowed.txt"))

        assertTrue(clearCacheCalled)
        assertTrue(result.success)
        assertEquals(0, result.conflictCount)
        assertFalse(result.needsRescueApproval)
        assertNull(result.rescueBranchName)

        assertTrue(commands.contains("git fetch origin test-branch"))
        assertTrue(commands.contains("inDirNoRetry: git merge origin/master --no-edit -m chore: merge master into PR #123 to keep up to date"))
        assertTrue(commands.contains("inDir: git checkout origin/master -- disallowed.txt"))
        assertTrue(commands.contains("inDir: git commit -m chore: discard unintended file modifications"))
        assertTrue(commands.contains("inDir: git push --force-with-lease origin HEAD:test-branch"))
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

        val result = rebaser.run("123", listOf("allowed.txt"))

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
                if (cmd.startsWith("git diff --name-only origin/master origin/test-branch")) "allowed.txt\nmaster_only.txt"
                else if (cmd.startsWith("git ls-tree")) "allowed.txt" // Exists in branch
                else ""
            },
            executeInDirNoRetry = { _, args ->
                val cmd = args.joinToString(" ")
                commands.add("inDirNoRetry: $cmd")
                if (cmd.startsWith("git merge origin/master")) throw RuntimeException("fatal: refusing to merge unrelated histories")
                ""
            },
            clearPrCache = {}
        )

        val result = rebaser.run("123", listOf("allowed.txt"))

        assertFalse(result.success)
        assertEquals(0, result.conflictCount)
        assertTrue(result.needsRescueApproval)
        assertEquals("test-branch-rescue", result.rescueBranchName)

        assertTrue(commands.contains("inDir: git reset --hard origin/master"))
        assertTrue(commands.contains("inDir: git checkout origin/test-branch -- allowed.txt"))
        assertTrue(commands.contains("inDir: git commit -m chore(orchestrator): rescue PR #123 onto master via target-files apply"))
        assertTrue(commands.contains("inDir: git push --force origin HEAD:test-branch-rescue"))
    }
}
