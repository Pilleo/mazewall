---
title: "Ensure superseded label before creating a generation"
severity: "MEDIUM"
status: "resolved"
priority: 8
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubClient.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Ensure superseded label before creating a generation

**Context:** `CreateGenerationState` attempted to apply the `superseded` label without first ensuring that the repository defined it. GitHub CLI rejects an unknown label, preventing the replacement Jules session from being created and causing every retry to fail at the same operation.

**Needed:** Ensure the label idempotently through the GitHub CLI wrapper before applying it. Treat either label operation as a retryable external-call failure, and retain regression coverage for the generation transition.

**Resolution:** The GitHub client now exposes an idempotent label-ensure operation backed by `gh label create --force`. Generation creation ensures and applies the label inside a local retry boundary before starting the new Jules session.
