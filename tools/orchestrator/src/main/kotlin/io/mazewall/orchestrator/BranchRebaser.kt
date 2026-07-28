package io.mazewall.orchestrator

import java.io.File

sealed class RebaseProcessState {
    data class Init(val prNumber: String, val targetFiles: List<String>) : RebaseProcessState()
    data class SetupWorktree(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()
    data class AttemptMerge(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()
    data class HandleRescue(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()
    data class VerifyAndPush(val prNumber: String, val branchName: String, val worktreeDir: File, val isRescue: Boolean) : RebaseProcessState()
    data class Completed(val result: RebaseResult) : RebaseProcessState()
    data class Failed(val result: RebaseResult) : RebaseProcessState()
}

class BranchRebaser(
    private val execute: (Array<out String>) -> String,
    private val executeInDir: (File?, Array<out String>) -> String,
    private val executeInDirNoRetry: (File?, Array<out String>) -> String,
    private val clearPrCache: (String) -> Unit,
    private val fetchJulesPatch: (prNumber: String) -> String? = { null }
) {

    fun run(prNumber: String, targetFiles: List<String>): RebaseResult {
        var state: RebaseProcessState = RebaseProcessState.Init(prNumber, targetFiles)
        while (state !is RebaseProcessState.Completed && state !is RebaseProcessState.Failed) {
            state = when (state) {
                is RebaseProcessState.Init -> handleInit(state)
                is RebaseProcessState.SetupWorktree -> handleSetupWorktree(state)
                is RebaseProcessState.AttemptMerge -> handleAttemptMerge(state)
                is RebaseProcessState.HandleRescue -> handleRescue(state)
                is RebaseProcessState.VerifyAndPush -> handleVerifyAndPush(state)
                else -> throw IllegalStateException("Unexpected state")
            }
        }

        val worktreeDir = File("../temp-rebase-$prNumber")
        try {
            execute(arrayOf("git", "worktree", "remove", worktreeDir.absolutePath, "--force"))
        } catch (_: Exception) {}
        if (worktreeDir.exists()) {
            worktreeDir.deleteRecursively()
        }
        try {
            execute(arrayOf("git", "worktree", "prune"))
        } catch (_: Exception) {}
        try {
            execute(arrayOf("git", "config", "--local", "core.bare", "false"))
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

        val worktreeDir = File("../temp-rebase-${state.prNumber}")
        return RebaseProcessState.SetupWorktree(state.prNumber, branchName, worktreeDir, state.targetFiles)
    }

    private fun handleSetupWorktree(state: RebaseProcessState.SetupWorktree): RebaseProcessState {
        try {
            execute(arrayOf("git", "fetch", "origin", "master"))
            execute(arrayOf("git", "fetch", "origin", state.branchName))
        } catch (e: Exception) {
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        try {
            execute(arrayOf("git", "worktree", "remove", state.worktreeDir.absolutePath, "--force"))
        } catch (_: Exception) {}
        if (state.worktreeDir.exists()) {
            state.worktreeDir.deleteRecursively()
        }
        try {
            execute(arrayOf("git", "worktree", "prune"))
        } catch (_: Exception) {}

        try {
            execute(arrayOf("git", "config", "--local", "core.bare", "false"))
        } catch (_: Exception) {}

        try {
            execute(arrayOf("git", "worktree", "add", state.worktreeDir.absolutePath, "origin/${state.branchName}", "--detach"))
        } catch (e: Exception) {
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        return RebaseProcessState.AttemptMerge(state.prNumber, state.branchName, state.worktreeDir, state.targetFiles)
    }

        private fun handleAttemptMerge(state: RebaseProcessState.AttemptMerge): RebaseProcessState {
        try {
            executeInDirNoRetry(
                state.worktreeDir, arrayOf("git", "merge", "origin/master", "--no-edit", "-m", "chore: merge master into PR #${state.prNumber} to keep up to date")
            )
            // If merge succeeds cleanly, check if we're actually ahead of master
            val aheadOfMaster = try {
                executeInDir(state.worktreeDir, arrayOf("git", "rev-list", "--count", "origin/master..HEAD")).trim().toIntOrNull() ?: 0
            } catch (e: Exception) {
                0
            }
            if (aheadOfMaster == 0) {
                System.err.println("Nothing to merge for PR #${state.prNumber} — already up to date")
                return RebaseProcessState.Completed(RebaseResult(success = true, conflictCount = 0))
            }
            return RebaseProcessState.VerifyAndPush(state.prNumber, state.branchName, state.worktreeDir, isRescue = false)
        } catch (e: Exception) {
            // Any merge failure (conflict, unrelated histories, etc) transitions to the pristine Jules API rescue
            try {
                executeInDirNoRetry(state.worktreeDir, arrayOf("git", "merge", "--abort"))
            } catch (_: Exception) {}
            return RebaseProcessState.HandleRescue(state.prNumber, state.branchName, state.worktreeDir, state.targetFiles)
        }
    }

    private fun handleRescue(state: RebaseProcessState.HandleRescue): RebaseProcessState {
        System.err.println("Unrelated histories detected for PR #${state.prNumber}. Falling back to patch rescue logic.")
        try { executeInDirNoRetry(state.worktreeDir, arrayOf("git", "merge", "--abort")) } catch (_: Exception) {}

        try {
            executeInDir(state.worktreeDir, arrayOf("git", "reset", "--hard", "origin/master"))

            val patch = fetchJulesPatch(state.prNumber)
            if (patch.isNullOrBlank()) {
                System.err.println("Failed to fetch Jules patch for PR #${state.prNumber}. Cannot rescue.")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
            }

            val patchFile = File(state.worktreeDir, "jules-rescue.patch")
            patchFile.writeText(patch)

            try {
                executeInDir(state.worktreeDir, arrayOf("git", "apply", "--3way", "jules-rescue.patch"))
                executeInDir(state.worktreeDir, arrayOf("git", "add", "."))
            } catch (eApply: Exception) {
                System.err.println("Failed to apply Jules patch cleanly for PR #${state.prNumber}: ${eApply.message}")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
            } finally {
                patchFile.delete()
            }

            val hasChanges = try {
                executeInDir(state.worktreeDir, arrayOf("git", "diff", "--staged", "--quiet"))
                false
            } catch (_: Exception) {
                true // diff --quiet returns 1 if there are changes
            }

            if (hasChanges) {
                executeInDir(state.worktreeDir, arrayOf("git", "commit", "--no-verify", "-m", "chore(orchestrator): rescue PR #${state.prNumber} onto master via patch apply"))
                return RebaseProcessState.VerifyAndPush(state.prNumber, state.branchName, state.worktreeDir, isRescue = true)
            } else {
                System.err.println("Jules patch applied but resulted in no changes for PR #${state.prNumber}")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
            }
        } catch (e: Exception) {
            System.err.println("Failed to handle rescue: ${e.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
        }
    }

    private fun handleVerifyAndPush(state: RebaseProcessState.VerifyAndPush): RebaseProcessState {
        try {
            executeInDir(state.worktreeDir, arrayOf("./gradlew", "compileKotlin", ":tools:orchestrator:compileKotlin"))
        } catch (eCompile: Exception) {
            System.err.println("Compilation failed on branch for PR #${state.prNumber}: ${eCompile.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        try {
            if (state.isRescue) {
                val rescueBranch = "${state.branchName}-rescue"
                executeInDir(state.worktreeDir, arrayOf("git", "push", "--force", "origin", "HEAD:$rescueBranch"))
                return RebaseProcessState.Completed(RebaseResult(success = false, conflictCount = 0, needsRescueApproval = true, rescueBranchName = rescueBranch))
            } else {
                executeInDir(state.worktreeDir, arrayOf("git", "push", "--force-with-lease", "origin", "HEAD:${state.branchName}"))
                return RebaseProcessState.Completed(RebaseResult(success = true, conflictCount = 0))
            }
        } catch (ePush: Exception) {
            System.err.println("Failed to push branch: ${ePush.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }
    }
}
