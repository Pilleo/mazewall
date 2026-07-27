package io.mazewall.orchestrator

import java.io.File

sealed class RebaseProcessState {
    data class Init(val prNumber: String, val targetFiles: List<String>) : RebaseProcessState()
    data class SetupWorktree(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()
    data class AttemptMerge(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()
    data class SelfHeal(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()
    data class HandleRescue(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()
    data class VerifyAndPush(val prNumber: String, val branchName: String, val worktreeDir: File, val isRescue: Boolean) : RebaseProcessState()
    data class Completed(val result: RebaseResult) : RebaseProcessState()
    data class Failed(val result: RebaseResult) : RebaseProcessState()
}

class BranchRebaser(
    private val execute: (Array<out String>) -> String,
    private val executeInDir: (File?, Array<out String>) -> String,
    private val executeInDirNoRetry: (File?, Array<out String>) -> String,
    private val clearPrCache: (String) -> Unit
) {

    private fun isFileAllowed(file: String, targetFiles: List<String>): Boolean {
        if (file.startsWith("docs/internals/backlog/") && file.endsWith(".md")) return true
        val normalizedFile = file.replace('\\', '/').trim()
        return targetFiles.any { target ->
            val normalizedTarget = target.replace('\\', '/').trim().removePrefix(":")
            if (normalizedFile == normalizedTarget || normalizedFile.endsWith("/$normalizedTarget")) return true
            if (normalizedTarget.contains("/src/main/")) {
                val testTarget = normalizedTarget
                    .replace("/src/main/", "/src/test/")
                    .replace(".kt", "Test.kt")
                    .replace(".java", "Test.java")
                if (normalizedFile == testTarget || normalizedFile.endsWith("/$testTarget")) return true
            }
            false
        }
    }

    fun run(prNumber: String, targetFiles: List<String>): RebaseResult {
        var state: RebaseProcessState = RebaseProcessState.Init(prNumber, targetFiles)

        while (state !is RebaseProcessState.Completed && state !is RebaseProcessState.Failed) {
            state = when (state) {
                is RebaseProcessState.Init -> handleInit(state)
                is RebaseProcessState.SetupWorktree -> handleSetupWorktree(state)
                is RebaseProcessState.AttemptMerge -> handleAttemptMerge(state)
                is RebaseProcessState.HandleRescue -> handleRescue(state)
                is RebaseProcessState.SelfHeal -> handleSelfHeal(state)
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
            return RebaseProcessState.SelfHeal(state.prNumber, state.branchName, state.worktreeDir, state.targetFiles)
        } catch (e: Exception) {
            val errorMsg = e.message ?: ""
            if (errorMsg.contains("unrelated histories")) {
                return RebaseProcessState.HandleRescue(state.prNumber, state.branchName, state.worktreeDir, state.targetFiles)
            } else {
                // Determine if it's a conflict purely on dirty files
                val conflictedFiles = try {
                    executeInDir(state.worktreeDir, arrayOf("git", "diff", "--name-only", "--diff-filter=U"))
                        .lines().map { it.trim() }.filter { it.isNotEmpty() }
                } catch (_: Exception) {
                    emptyList()
                }

                if (conflictedFiles.isNotEmpty() && conflictedFiles.all { !isFileAllowed(it, state.targetFiles) }) {
                    System.err.println("Merge conflict detected ONLY on dirty files! Self-healing mid-merge...")
                    var midMergeCleaned = false
                    for (dirtyFile in conflictedFiles) {
                        try {
                            executeInDir(state.worktreeDir, arrayOf("git", "checkout", "origin/master", "--", dirtyFile))
                            executeInDir(state.worktreeDir, arrayOf("git", "add", dirtyFile))
                            midMergeCleaned = true
                        } catch (ex: Exception) {
                            System.err.println("Failed to discard dirty conflicted file '$dirtyFile': ${ex.message}")
                        }
                    }
                    if (midMergeCleaned) {
                        try {
                            executeInDir(state.worktreeDir, arrayOf("git", "commit", "--no-edit"))
                            return RebaseProcessState.SelfHeal(state.prNumber, state.branchName, state.worktreeDir, state.targetFiles)
                        } catch (ex: Exception) {
                            System.err.println("Failed to commit resolved dirty files mid-merge: ${ex.message}")
                        }
                    }
                }

                // Real conflict on target files or failed to recover
                try {
                    executeInDirNoRetry(state.worktreeDir, arrayOf("git", "merge", "--abort"))
                } catch (_: Exception) {}
                System.err.println("Merge conflict on PR #${state.prNumber}: ${e.message}")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = if (conflictedFiles.isNotEmpty()) conflictedFiles.size else 1, conflictedFiles = conflictedFiles))
            }
        }
    }

    private fun handleSelfHeal(state: RebaseProcessState.SelfHeal): RebaseProcessState {
        val differentFiles = try {
            executeInDir(state.worktreeDir, arrayOf("git", "diff", "--name-only", "origin/master")).lines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }

        var cleanedAny = false
        for (file in differentFiles) {
            if (!isFileAllowed(file, state.targetFiles)) {
                System.err.println("🧹 DISCARDING UNINTENDED MODIFICATION: File '$file' is not in targetFiles. Restoring from master...")
                try {
                    executeInDir(state.worktreeDir, arrayOf("git", "checkout", "origin/master", "--", file))
                    executeInDir(state.worktreeDir, arrayOf("git", "add", file))
                    cleanedAny = true
                } catch (e: Exception) {
                    System.err.println("Failed to discard unintended modification on '$file': ${e.message}")
                }
            }
        }

        if (cleanedAny) {
            try {
                executeInDir(state.worktreeDir, arrayOf("git", "commit", "-m", "chore: discard unintended file modifications"))
            } catch (e: Exception) {
                System.err.println("Failed to commit self-healing cleanup: ${e.message}")
            }
        }

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
    }

    private fun handleRescue(state: RebaseProcessState.HandleRescue): RebaseProcessState {
        System.err.println("Unrelated histories detected for PR #${state.prNumber}. Falling back to patch rescue logic.")
        try { executeInDirNoRetry(state.worktreeDir, arrayOf("git", "merge", "--abort")) } catch (_: Exception) {}

        try {
            val allDifferentFiles = executeInDir(state.worktreeDir, arrayOf("git", "diff", "--name-only", "origin/master", "origin/${state.branchName}"))
                .lines().map { it.trim() }.filter { it.isNotEmpty() }

            executeInDir(state.worktreeDir, arrayOf("git", "reset", "--hard", "origin/master"))

            var rescuedAny = false
            for (file in allDifferentFiles) {
                if (isFileAllowed(file, state.targetFiles)) {
                    try {
                        val exists = executeInDir(state.worktreeDir, arrayOf("git", "ls-tree", "-r", "origin/${state.branchName}", "--name-only"))
                            .lines().any { it.trim() == file }
                        if (exists) {
                            executeInDir(state.worktreeDir, arrayOf("git", "checkout", "origin/${state.branchName}", "--", file))
                            executeInDir(state.worktreeDir, arrayOf("git", "add", file))
                        } else {
                            executeInDir(state.worktreeDir, arrayOf("git", "rm", "--ignore-unmatch", file))
                        }
                        rescuedAny = true
                    } catch (eRescue: Exception) {
                        System.err.println("Failed to rescue file '$file': ${eRescue.message}")
                    }
                }
            }

            if (rescuedAny) {
                executeInDir(state.worktreeDir, arrayOf("git", "commit", "-m", "chore(orchestrator): rescue PR #${state.prNumber} onto master via target-files apply"))
                return RebaseProcessState.VerifyAndPush(state.prNumber, state.branchName, state.worktreeDir, isRescue = true)
            } else {
                System.err.println("No target files found to rescue for PR #${state.prNumber}")
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
