with open("tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BranchRebaser.kt", "r") as f:
    content = f.read()

content = content.replace("    data class AttemptMerge(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()", "    data class AttemptMerge(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()\n    data class SelfHeal(val prNumber: String, val branchName: String, val worktreeDir: File, val targetFiles: List<String>) : RebaseProcessState()")

with open("tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BranchRebaser.kt", "w") as f:
    f.write(content)
