package io.mazewall.orchestrator

import java.io.File

sealed class RebaseProcessState {
    data class Init(val prNumber: String, val sessionId: String?) : RebaseProcessState()
    data class SetupWorktree(val prNumber: String, val branchName: String, val worktreeDir: File, val sessionId: String?) : RebaseProcessState()
    data class AttemptMerge(val prNumber: String, val branchName: String, val worktreeDir: File, val sessionId: String?) : RebaseProcessState()
    data class HandleRescue(val prNumber: String, val branchName: String, val worktreeDir: File, val sessionId: String?) : RebaseProcessState()
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

    fun run(prNumber: String, sessionId: String?): RebaseResult {
        var state: RebaseProcessState = RebaseProcessState.Init(prNumber, sessionId)
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

        val worktreeDir = File("../temp-rebase-${state.prNumber}")
        return RebaseProcessState.SetupWorktree(state.prNumber, branchName, worktreeDir, state.sessionId)
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

        try {
            executeInDirNoRetry(null, arrayOf("git", "worktree", "add", state.worktreeDir.absolutePath, "origin/${state.branchName}", "--detach"))
        } catch (e: Exception) {
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        return RebaseProcessState.AttemptMerge(state.prNumber, state.branchName, state.worktreeDir, state.sessionId)
    }

        private fun handleAttemptMerge(state: RebaseProcessState.AttemptMerge): RebaseProcessState {
        try {
            val issueBody = execute(arrayOf("gh", "pr", "view", state.prNumber, "--json", "body", "--jq", ".body"))
            val backlogIssueMatch = Regex("""\bissue-[0-9]{8}-[0-9]{6}[a-zA-Z0-9_-]+\.md\b""").find(issueBody)

            if (backlogIssueMatch == null) {
                System.err.println("Could not find backlog issue reference in PR #${state.prNumber} body.")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
            }

            val backlogFileName = backlogIssueMatch.value
            val backlogCommandOutput = execute(arrayOf("find", "docs/internals/backlog", "-name", backlogFileName))
            val backlogFile = java.io.File(backlogCommandOutput.lines().firstOrNull { it.isNotBlank() } ?: "")
            if (!backlogFile.exists()) {
                System.err.println("Backlog file ${backlogFile.absolutePath} not found for PR #${state.prNumber}.")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
            }

            val content = backlogFile.readText()
            val targetFilesMatch = Regex("""target_files:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(content)
            if (targetFilesMatch == null) {
                System.err.println("target_files not found in backlog issue $backlogFileName.")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
            }
            val rawFiles = targetFilesMatch.groupValues[1]
            val intendedFiles = rawFiles.split(',')
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .filter { it.isNotBlank() }

            if (intendedFiles.isEmpty()) {
                System.err.println("No intended files parsed from $backlogFileName.")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
            }

            executeInDirNoRetry(state.worktreeDir, arrayOf("git", "reset", "--hard", "origin/master"))

            val checkoutErrors = mutableListOf<String>()
            for (file in intendedFiles) {
                try {
                    executeInDirNoRetry(state.worktreeDir, arrayOf("git", "checkout", "origin/${state.branchName}", "--", file))
                } catch (e: Exception) {
                    checkoutErrors.add(file)
                }
            }

            for (file in intendedFiles) {
                if (file.contains("/src/main/")) {
                    val testTarget = file
                        .replace("/src/main/", "/src/test/")
                        .replace(".kt", "Test.kt")
                        .replace(".java", "Test.java")
                    try {
                        executeInDirNoRetry(state.worktreeDir, arrayOf("git", "checkout", "origin/${state.branchName}", "--", testTarget))
                    } catch (e: Exception) {}
                }
            }

            if (checkoutErrors.size == intendedFiles.size) {
                 System.err.println("Failed to extract any of the intended files from origin/${state.branchName}.")
                 return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
            } else if (checkoutErrors.isNotEmpty()) {
                 System.err.println("Warning: Failed to extract some files: ${checkoutErrors.joinToString(", ")}")
            }

            val hasChanges = try {
                executeInDirNoRetry(state.worktreeDir, arrayOf("git", "diff", "--staged", "--quiet"))
                false
            } catch (_: Exception) {
                true
            }

            if (!hasChanges) {
                System.err.println("Extraction resulted in no changes for PR #${state.prNumber} (already up to date).")
                return RebaseProcessState.Completed(RebaseResult(success = true, conflictCount = 0))
            }

            executeInDirNoRetry(state.worktreeDir, arrayOf("git", "commit", "--no-verify", "-m", "chore: rescue clean intended files for PR #${state.prNumber} onto master"))
            return RebaseProcessState.VerifyAndPush(state.prNumber, state.branchName, state.worktreeDir, isRescue = true)
        } catch (e: Exception) {
            System.err.println("Rescue extraction failed for PR #${state.prNumber}. Output:\n${e.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
        }
    }

    private fun handleRescue(state: RebaseProcessState.HandleRescue): RebaseProcessState {
        return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
    }

    private fun handleVerifyAndPush(state: RebaseProcessState.VerifyAndPush): RebaseProcessState {
        try {
            executeInDir(state.worktreeDir, arrayOf("./gradlew", "compileKotlin", ":tools:orchestrator:compileKotlin"))
        } catch (eCompile: Exception) {
            System.err.println("Compilation failed on branch for PR #${state.prNumber}: ${eCompile.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }

        try {
             executeInDir(state.worktreeDir, arrayOf("git", "push", "--force", "origin", "HEAD:${state.branchName}"))
             return RebaseProcessState.Completed(RebaseResult(success = true, conflictCount = 0))
        } catch (ePush: Exception) {
            System.err.println("Failed to push branch: ${ePush.message}")
            return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 0))
        }
    }
}
