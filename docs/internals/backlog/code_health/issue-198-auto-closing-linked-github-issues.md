---
title: "Auto-Closing Linked GitHub Issues"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
---

# 🔴 [Severity: HIGH]: Auto-Closing Linked GitHub Issues

**Context:**
The orchestrator moves the local backlog file to `resolved/` when completed, but leaves the corresponding GitHub issue open if the PR doesn't explicitly contain the `fixes #` keyword.

**Needed:**
Right after detecting that the PR is merged (`GitHubCli.isPrMerged(finalPrNumber) == true`) in `OrchestratorDaemon.kt`, check and close the corresponding GitHub issue:
```kotlin
println("Closing GitHub issue #$finalIssueNumber...")
executeCmd("gh", "issue", "close", finalIssueNumber)
bot?.sendMessage("✅ GitHub issue #$finalIssueNumber automatically closed.")
```
Ensure this is done reliably and handle any potential exceptions during CLI execution.
