---
title: "Implement Token-Efficient PR Review via Diff Injection"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
---

# 🔴 [Severity: HIGH]: Implement Token-Efficient PR Review via Diff Injection

**Context:**
Currently, passing only the PR number to the automated reviewer `agy` forces the agent to use tool calls to fetch the diff, consuming massive amounts of context tokens and increasing latency/failure rates.

**Needed:**
1. Fetch the diff locally in Kotlin within `OrchestratorDaemon.kt`:
   ```kotlin
   val prDiff = executeCmd("gh", "pr", "diff", finalPrNumber)
   ```
2. Inject `prDiff` directly into the `agy` prompt text string so the LLM has immediate access to the code changes, saving context tokens and reducing latency.
