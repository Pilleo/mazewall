with open("tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BranchRebaser.kt", "r") as f:
    content = f.read()

content = content.replace("is RebaseProcessState.HandleRescue -> handleRescue(state)\n                is RebaseProcessState.VerifyAndPush -> handleVerifyAndPush(state)", "is RebaseProcessState.HandleRescue -> handleRescue(state)\n                is RebaseProcessState.SelfHeal -> handleSelfHeal(state)\n                is RebaseProcessState.VerifyAndPush -> handleVerifyAndPush(state)")

with open("tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BranchRebaser.kt", "w") as f:
    f.write(content)
