---
title: "Pause sibling slots when a PR diff escapes declared target_files"
severity: "HIGH"
status: "open"
priority: high
dependencies:
  - issue-20260726-191003
  - issue-20260823-181020
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateHandlerTest.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 53df39c0-4489-4c39-bbf0-b646b3ce9563
---

# 🔴 [Severity: HIGH]: Pause sibling slots when a PR diff escapes declared target_files

**Context:**
`issue-20260726-191003` tracks populating `slot.actualTargetFiles` from `git diff --name-only origin/master..head`. Declared `target_files` are a hint; agents leak. Safer parallelism (operator constraint) is: keep module-level exclusive scheduling, and if an in-flight PR touches undeclared paths, **pause starting siblings** and notify Telegram. Escape into CORE files is a hard conflict even with 191003’s union of actual files, because a new task might have been selected before the leak was observed.

**Needed:**
1. After 191003’s `actualTargetFiles` exists, compute `escaped = actual - declared` (allow `docs/internals/backlog/**` as today). If `escaped` is non-empty:
   - Telegram notify with the extra paths.
   - Do not select new tasks whose `target_modules` or `target_files` intersect `actualTargetFiles` (already the 191003 intent).
   - Additionally: if `escaped` intersects CORE set (`Syscall.kt`, `Arch.kt`, `Policy.kt`, `AGENTS.md`, `ArchitectureTest.kt`, Gradle files), set a slot flag `globalLock=true` so `selectAndStartTasks` starts **no** new mutating slots until this slot resolves.
2. Tests: fixture slot with declared `PolicyCompilationCache.kt` and actual also containing `Syscall.kt` → no second slot starts. Fixture where actual ⊆ declared → sibling with disjoint module may start (subject to existing module lock).
3. Do not fail-closed-kill the leaking agent automatically (it may be a legitimate discovery). Pause siblings; leave the leaking slot running; human decides.
4. Do not implement package-level intra-module parallel here.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.ParallelTaskSchedulerTest --tests io.mazewall.orchestrator.StateHandlerTest`.
