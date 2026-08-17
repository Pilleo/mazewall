---
title: Implement Serial PR Merge Queue in Orchestrator to Prevent Rebase Races
severity: HIGH
status: open
priority: high
dependencies: []
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt
effort: medium
autonomy: autonomous
---

# 🔴 [Severity: HIGH]: Implement Serial PR Merge Queue in Orchestrator to Prevent Rebase Races

**Context:**
When multiple AI agent (Jules) sessions run in parallel on separate feature branches, merging them directly or asynchronously causes rebase races and git merge conflicts on `master`. Without a centralized merge queue, PRs that were tested against an older `master` baseline can introduce build breakages or merge conflicts when merged out of order.

**Needed:**
1. Implement a serial PR merge queue in `OrchestratorDaemon.kt` where completed PRs are merged into `master` strictly one at a time.
2. Before merging PR #N into `master`, Orchestrator must:
   - Fetch the latest `origin/master`.
   - Rebase PR #N on `master` using `gitHubClient.rebaseBranch`.
   - Run `./gradlew test` (or CI check) synchronously to verify green build.
   - Execute squash merge to `master` only after verification succeeds.
3. Immediately trigger a rebase signal for any remaining in-flight active slots so parallel feature branches stay aligned with the updated `master`.
