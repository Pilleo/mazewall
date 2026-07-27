with open("tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BranchRebaser.kt", "r") as f:
    content = f.read()

# Wait, the current implementation of `handleAttemptMerge` DOES NOT HAVE `RebaseProcessState.SelfHeal`
# Let's extract the self-healing part into `handleSelfHeal`

import re

attempt_merge_block = """    private fun handleAttemptMerge(state: RebaseProcessState.AttemptMerge): RebaseProcessState {
        try {
            executeInDirNoRetry(
                state.worktreeDir, arrayOf("git", "merge", "origin/master", "--no-edit", "-m", "chore: merge master into PR #${state.prNumber} to keep up to date")
            )

            // Self healing
            val differentFiles = executeInDir(state.worktreeDir, arrayOf("git", "diff", "--name-only", "origin/master")).lines().map { it.trim() }.filter { it.isNotEmpty() }
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

            val aheadOfMaster = executeInDir(
                state.worktreeDir, arrayOf("git", "rev-list", "--count", "origin/master..HEAD")
            ).trim().toIntOrNull() ?: 0

            if (aheadOfMaster == 0) {
                System.err.println("Nothing to merge for PR #${state.prNumber} — already up to date")
                return RebaseProcessState.Completed(RebaseResult(success = true, conflictCount = 0))
            }

            return RebaseProcessState.VerifyAndPush(state.prNumber, state.branchName, state.worktreeDir, isRescue = false)

        } catch (e: Exception) {
            val errorMsg = e.message ?: ""
            if (errorMsg.contains("unrelated histories")) {
                return RebaseProcessState.HandleRescue(state.prNumber, state.branchName, state.worktreeDir, state.targetFiles)
            } else {
                try {
                    executeInDirNoRetry(state.worktreeDir, arrayOf("git", "merge", "--abort"))
                } catch (_: Exception) {}
                System.err.println("Merge conflict on PR #${state.prNumber}: ${e.message}")
                return RebaseProcessState.Failed(RebaseResult(success = false, conflictCount = 1))
            }
        }
    }"""

new_attempt_merge_block = """    private fun handleAttemptMerge(state: RebaseProcessState.AttemptMerge): RebaseProcessState {
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
    }"""

if attempt_merge_block in content:
    content = content.replace(attempt_merge_block, new_attempt_merge_block)
    with open("tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BranchRebaser.kt", "w") as f:
        f.write(content)
    print("Patched BranchRebaser")
else:
    print("Failed to find attempt_merge_block")
