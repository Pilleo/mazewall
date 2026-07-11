---
title: "Auto-Closing Linked GitHub Issues"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Auto-Closing Linked GitHub Issues

**Context:**
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

**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Unknown`
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
