package io.mazewall.orchestrator

import java.io.File

sealed class RebaseProcessState {
    data class Init(val prNumber: String, val sessionId: String?) : RebaseProcessState()
    data class SetupWorktree(val prNumber: String, val branchName: String, val featureSha: String, val worktreeDir: File, val sessionId: String?) : RebaseProcessState()
    data class ReconstructOnMaster(val prNumber: String, val branchName: String, val featureSha: String, val worktreeDir: File, val sessionId: String?) : RebaseProcessState()
    data class VerifyAndPush(val prNumber: String, val branchName: String, val featureSha: String, val worktreeDir: File) : RebaseProcessState()
    data class Completed(val result: RebaseResult) : RebaseProcessState()
    data class Failed(val result: RebaseResult) : RebaseProcessState()
}

class BranchRebaser(
    private val execute: (Array<out String>) -> String,
    private val executeInDir: (File?, Array<out String>) -> String,
    private val executeInDirNoRetry: (File?, Array<out String>) -> String,
    private val clearPrCache: (String) -> Unit
) {

    fun run(prNumber: String, sessionId: String?): RebaseResult {
        var state: RebaseProcessState = RebaseProcessState.Init(prNumber, sessionId)
        while (state !is RebaseProcessState.Completed && state !is RebaseProcessState.Failed) {
            state = when (state) {
                is RebaseProcessState.Init -> handleInit(state)
                is RebaseProcessState.SetupWorktree -> handleSetupWorktree(state)
                is RebaseProcessState.ReconstructOnMaster -> handleReconstructOnMaster(state)
                is RebaseProcessState.VerifyAndPush -> handleVerifyAndPush(state)
                else -> throw IllegalStateException("Unexpected state")
            }
        }

        val worktreeDir = File("build/tmp/temp-rebase-$prNumber")
        try {
            executeInDirNoRetry(null, arrayOf("git", "worktree", "remove", worktreeDir.absolutePath, "--force"))
        } catch (_: Exception) {}
        if (worktreeDir.exists()) {
            worktreeDir.deleteRecursively()
        }
        try {
            executeInDirNoRetry(null, arrayOf("git", "worktree", "prune"))
        } catch (_: Exception) {}
        try {
            executeInDirNoRetry(null, arrayOf("git", "config", "core.bare", "false"))
        } catch (_: Exception) {}

        return if (state is RebaseProcessState.Completed) state.result else (state as RebaseProcessState.Failed).result
    }

    private fun handleInit(state: RebaseProcessState.Init): RebaseProcessState {
        clearPrCache(state.prNumber)
        val branchName = try {
            execute(arrayOf("gh", "pr", "view", state.prNumber, "--json", "headRefName", "--jq", ".headRefName")).trim()
        } catch (e: Exception) {
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }
        if (branchName.isBlank()) return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))

        val featureSha = try {
            execute(arrayOf("git", "rev-parse", "origin/$branchName")).trim()
        } catch (e: Exception) {
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        val worktreeDir = File("build/tmp/temp-rebase-${state.prNumber}")
        return RebaseProcessState.SetupWorktree(state.prNumber, branchName, featureSha, worktreeDir, state.sessionId)
    }

    private fun handleSetupWorktree(state: RebaseProcessState.SetupWorktree): RebaseProcessState {
        try {
            execute(arrayOf("git", "fetch", "origin", "master"))
            execute(arrayOf("git", "fetch", "origin", state.branchName))
        } catch (e: Exception) {
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        try {
            executeInDirNoRetry(null, arrayOf("git", "worktree", "remove", state.worktreeDir.absolutePath, "--force"))
        } catch (_: Exception) {}
        if (state.worktreeDir.exists()) {
            state.worktreeDir.deleteRecursively()
        }
        try {
            executeInDirNoRetry(null, arrayOf("git", "worktree", "prune"))
        } catch (_: Exception) {}

        try {
            executeInDirNoRetry(null, arrayOf("git", "config", "core.bare", "false"))
        } catch (_: Exception) {}

        return RebaseProcessState.ReconstructOnMaster(state.prNumber, state.branchName, state.featureSha, state.worktreeDir, state.sessionId)
    }

    fun runFallback(prNumber: String, sessionId: String?, targetFiles: List<String>): RebaseResult {
        if (targetFiles.isEmpty()) {
            System.err.println("Fallback rebase aborted: targetFiles is empty")
            return RebaseResult(success = false, conflictCount = 0)
        }
        System.err.println("Manual targetFiles fallback executed for files: $targetFiles")
        
        clearPrCache(prNumber)
        val branchName = try {
            execute(arrayOf("gh", "pr", "view", prNumber, "--json", "headRefName", "--jq", ".headRefName")).trim()
        } catch (e: Exception) {
            return RebaseResult(success = false, conflictCount = 0)
        }
        if (branchName.isBlank()) return RebaseResult(success = false, conflictCount = 0)

        val featureSha = try {
            execute(arrayOf("git", "rev-parse", "origin/$branchName")).trim()
        } catch (e: Exception) {
            return RebaseResult(success = false, conflictCount = 0)
        }

        val worktreeDir = File("build/tmp/temp-rebase-$prNumber")
        
        try {
            execute(arrayOf("git", "fetch", "origin", "master"))
            execute(arrayOf("git", "fetch", "origin", branchName))
            executeInDirNoRetry(null, arrayOf("git", "worktree", "remove", worktreeDir.absolutePath, "--force"))
        } catch (_: Exception) {}
        if (worktreeDir.exists()) {
            worktreeDir.deleteRecursively()
        }
        try { executeInDirNoRetry(null, arrayOf("git", "worktree", "prune")) } catch (_: Exception) {}
        try { executeInDirNoRetry(null, arrayOf("git", "config", "core.bare", "false")) } catch (_: Exception) {}

        try {
            executeInDirNoRetry(null, arrayOf("git", "worktree", "add", worktreeDir.absolutePath, "origin/master", "--detach"))
            
            // Loop through each file strictly defined in targetFiles
            for (file in targetFiles) {
                try {
                    executeInDirNoRetry(worktreeDir, arrayOf("git", "checkout", featureSha, "--", file))
                } catch (e: Exception) {
                    System.err.println("Failed to checkout $file from feature branch for PR #$prNumber. Assuming deleted.")
                    try {
                        executeInDirNoRetry(worktreeDir, arrayOf("git", "rm", "--ignore-unmatch", file))
                    } catch (e2: Exception) {
                        System.err.println("Failed to remove $file: ${e2.message}")
                    }
                }
            }

            executeInDirNoRetry(worktreeDir, arrayOf("git", "commit", "-m", "chore: manual fallback recovery for PR #$prNumber"))
            
            try {
                executeInDir(worktreeDir, arrayOf("./gradlew", "compileKotlin", ":tools:orchestrator:compileKotlin"))
            } catch (eCompile: Exception) {
                System.err.println("Compilation failed on fallback branch for PR #$prNumber: ${eCompile.message}")
                return RebaseResult(success = false, conflictCount = 0)
            }
            
            val leaseArg = "--force-with-lease=refs/heads/$branchName:$featureSha"
            executeInDir(worktreeDir, arrayOf("git", "push", leaseArg, "origin", "HEAD:refs/heads/$branchName"))
            return RebaseResult(success = true, conflictCount = 0)
        } catch (e: Exception) {
            System.err.println("Fallback rebase failed for PR #$prNumber: ${e.message}")
            return RebaseResult(success = false, conflictCount = 1)
        } finally {
            try { executeInDirNoRetry(null, arrayOf("git", "worktree", "remove", worktreeDir.absolutePath, "--force")) } catch (_: Exception) {}
            if (worktreeDir.exists()) {
                worktreeDir.deleteRecursively()
            }
            try { executeInDirNoRetry(null, arrayOf("git", "worktree", "prune")) } catch (_: Exception) {}
            try { executeInDirNoRetry(null, arrayOf("git", "config", "core.bare", "false")) } catch (_: Exception) {}
        }
    }

    private fun handleReconstructOnMaster(state: RebaseProcessState.ReconstructOnMaster): RebaseProcessState {
        val baseSha = try {
            executeInDirNoRetry(null, arrayOf("git", "merge-base", "origin/master", "origin/${state.branchName}")).trim()
        } catch (e: Exception) {
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
        }

        // 1. Extract first-parent commits from baseSha to featureSha
        val commitLines = try {
            executeInDirNoRetry(null, arrayOf("git", "rev-list", "--reverse", "--first-parent", "$baseSha..${state.featureSha}"))
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }

        // 2. Classify candidate commits: exclude merge commits and synthetic sync commits
        val agentCommits = mutableListOf<String>()
        for (commit in commitLines) {
            val parents = try {
                executeInDirNoRetry(null, arrayOf("git", "show", "-s", "--format=%P", commit))
                    .trim()
                    .split("\\s+".toRegex())
                    .filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }

            // Skip merge commits
            if (parents.size > 1) {
                continue
            }

            val authorEmail = try {
                executeInDirNoRetry(null, arrayOf("git", "show", "-s", "--format=%ae", commit)).trim()
            } catch (e: Exception) {
                ""
            }

            val commitMsg = try {
                executeInDirNoRetry(null, arrayOf("git", "show", "-s", "--format=%B", commit)).trim()
            } catch (_: Exception) {
                ""
            }

            // Skip automated orchestrator sync commits
            if (commitMsg.startsWith("chore: merge master into PR") || commitMsg.startsWith("chore: strip dirt files")) {
                continue
            }

            // Only replay legitimate Jules commits. Skip any other contamination commits
            if (authorEmail != "161369871+google-labs-jules[bot]@users.noreply.github.com") {
                System.err.println("Skipping commit $commit authored by $authorEmail (not Jules)")
                continue
            }

            agentCommits.add(commit)
        }

        if (agentCommits.isEmpty()) {
            System.err.println("Nothing to rebase for PR #${state.prNumber} — branch is already up to date or has no non-merge commits")
            return RebaseProcessState.Completed(RebaseResult(success = true, conflictCount = 0))
        }

        // 3. Create fresh worktree on origin/master
        try {
            executeInDirNoRetry(null, arrayOf("git", "worktree", "add", state.worktreeDir.absolutePath, "origin/master", "--detach"))
        } catch (e: Exception) {
            System.err.println("Failed to create worktree on origin/master for PR #${state.prNumber}: ${e.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
        }

        // 4. Replay candidate commits in chronological order via git cherry-pick
        for (commit in agentCommits) {
            try {
                executeInDirNoRetry(state.worktreeDir, arrayOf("git", "cherry-pick", "--keep-redundant-commits", commit))
            } catch (e: Exception) {
                val errText = e.message ?: ""
                if (errText.contains("cherry-pick is now empty") || errText.contains("nothing to commit") || errText.contains("previous cherry-pick is now empty")) {
                    try {
                        executeInDirNoRetry(state.worktreeDir, arrayOf("git", "cherry-pick", "--skip"))
                        continue
                    } catch (_: Exception) {}
                }

                System.err.println("Cherry-pick failed for PR #${state.prNumber} on commit $commit: ${e.message}")
                try {
                    executeInDirNoRetry(state.worktreeDir, arrayOf("git", "cherry-pick", "--abort"))
                } catch (_: Exception) {}
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
            }
        }

        return RebaseProcessState.VerifyAndPush(state.prNumber, state.branchName, state.featureSha, state.worktreeDir)
    }

    private fun handleVerifyAndPush(state: RebaseProcessState.VerifyAndPush): RebaseProcessState {
        try {
            val merges = executeInDirNoRetry(state.worktreeDir, arrayOf("git", "rev-list", "--merges", "origin/master..HEAD")).trim()
            if (merges.isNotEmpty()) {
                System.err.println("Unexpected merge commits introduced in sanitized branch for PR #${state.prNumber}")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
            }
        } catch (e: Exception) {
            System.err.println("Failed to check for merge commits on reconstructed branch for PR #${state.prNumber}: ${e.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        try {
            executeInDir(state.worktreeDir, arrayOf("./gradlew", "compileKotlin", ":tools:orchestrator:compileKotlin"))
        } catch (eCompile: Exception) {
            System.err.println("Compilation failed on reconstructed branch for PR #${state.prNumber}: ${eCompile.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        try {
            val leaseArg = "--force-with-lease=refs/heads/${state.branchName}:${state.featureSha}"
            executeInDir(state.worktreeDir, arrayOf("git", "push", leaseArg, "origin", "HEAD:refs/heads/${state.branchName}"))
            return RebaseProcessState.Completed(RebaseResult(success = true, conflictCount = 0))
        } catch (ePush: Exception) {
            System.err.println("Failed to push reconstructed branch for PR #${state.prNumber}: ${ePush.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }
    }
}
