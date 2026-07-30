package io.mazewall.orchestrator

import java.io.File

sealed class RebaseProcessState {
    data class Init(val prNumber: String, val sessionId: String?, val targetFiles: List<String>) : RebaseProcessState()
    data class SetupWorktree(val prNumber: String, val branchName: String, val featureSha: String, val worktreeDir: File, val sessionId: String?, val targetFiles: List<String>) : RebaseProcessState()
    data class ReconstructOnMaster(val prNumber: String, val branchName: String, val featureSha: String, val worktreeDir: File, val sessionId: String?, val targetFiles: List<String>) : RebaseProcessState()
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

    fun run(prNumber: String, sessionId: String?, targetFiles: List<String> = emptyList()): RebaseResult {
        var state: RebaseProcessState = RebaseProcessState.Init(prNumber, sessionId, targetFiles)
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
        return RebaseProcessState.SetupWorktree(state.prNumber, branchName, featureSha, worktreeDir, state.sessionId, state.targetFiles)
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

        return RebaseProcessState.ReconstructOnMaster(state.prNumber, state.branchName, state.featureSha, state.worktreeDir, state.sessionId, state.targetFiles)
    }

    private fun isFileAllowed(file: String, targetFiles: List<String>): Boolean {
        if (file.startsWith("docs/internals/backlog/") && file.endsWith(".md")) return true
        val normalizedFile = file.replace('\\', '/').trim()
        return targetFiles.any { target ->
            val normalizedTarget = target.replace('\\', '/').trim().removePrefix(":")

            // Exact match or suffix match
            if (normalizedFile == normalizedTarget || normalizedFile.endsWith("/$normalizedTarget")) return true

            // If the target is a main file, also allow its corresponding test file
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

            val commitMsg = try {
                executeInDirNoRetry(null, arrayOf("git", "show", "-s", "--format=%B", commit)).trim()
            } catch (_: Exception) {
                ""
            }

            // Skip automated orchestrator sync commits
            if (commitMsg.startsWith("chore: merge master into PR") || commitMsg.startsWith("chore: strip dirt files")) {
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

        // 4. Replay candidate commits in chronological order via git cherry-pick with dirt-filtering
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

                // Disallowed file conflict resolution: if conflict is in disallowed files (dirt), restore origin/master version
                val statusFiles = try {
                    executeInDirNoRetry(state.worktreeDir, arrayOf("git", "status", "--porcelain"))
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                } catch (_: Exception) {
                    emptyList()
                }

                var resolvedDisallowed = false
                var hasUnresolvedAllowedConflict = false

                for (line in statusFiles) {
                    if (line.length < 3) continue
                    val file = line.substring(3).trim()
                    if (!isFileAllowed(file, state.targetFiles)) {
                        try {
                            executeInDirNoRetry(state.worktreeDir, arrayOf("git", "checkout", "origin/master", "--", file))
                            executeInDirNoRetry(state.worktreeDir, arrayOf("git", "add", file))
                            resolvedDisallowed = true
                        } catch (_: Exception) {}
                    } else if (line.startsWith("UU") || line.startsWith("AA") || line.startsWith("DD") || line.startsWith("U")) {
                        hasUnresolvedAllowedConflict = true
                    }
                }

                if (resolvedDisallowed && !hasUnresolvedAllowedConflict) {
                    try {
                        executeInDirNoRetry(state.worktreeDir, arrayOf("git", "commit", "-C", commit, "--no-verify"))
                        System.err.println("Sanitization: successfully resolved cherry-pick conflict for $commit by discarding disallowed file modifications")
                        continue
                    } catch (_: Exception) {}
                }

                System.err.println("Cherry-pick failed for PR #${state.prNumber} on commit $commit: ${e.message}")
                try {
                    executeInDirNoRetry(state.worktreeDir, arrayOf("git", "cherry-pick", "--abort"))
                } catch (_: Exception) {}
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
            }

            // Post-cherry-pick sanitization: strip any disallowed file modifications introduced by this commit
            try {
                val modifiedInCommit = executeInDirNoRetry(state.worktreeDir, arrayOf("git", "diff", "--name-only", "HEAD~1..HEAD"))
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                var stripped = false
                for (file in modifiedInCommit) {
                    if (!isFileAllowed(file, state.targetFiles)) {
                        executeInDirNoRetry(state.worktreeDir, arrayOf("git", "checkout", "origin/master", "--", file))
                        executeInDirNoRetry(state.worktreeDir, arrayOf("git", "add", file))
                        stripped = true
                    }
                }
                if (stripped) {
                    executeInDirNoRetry(state.worktreeDir, arrayOf("git", "commit", "--amend", "--no-edit", "--no-verify"))
                }
            } catch (_: Exception) {}
        }

        return RebaseProcessState.VerifyAndPush(state.prNumber, state.branchName, state.featureSha, state.worktreeDir)
    }

    private fun handleVerifyAndPush(state: RebaseProcessState.VerifyAndPush): RebaseProcessState {
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
