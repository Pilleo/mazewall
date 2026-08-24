---
title: Proof-of-Progress State Machine for Landlock Discovery (`DiscoveryTask<Status>`)
severity: ENHANCEMENT
status: open
priority: low
dependencies: []
target_files:
- profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt
target_modules:
- :profiler
component: profiler
effort: medium
paperclip_issue_id: 2883709d-3490-4441-ad54-5603f8625386
---

# 🔵 [Severity: ENHANCEMENT]: Proof-of-Progress State Machine for Landlock Discovery (`DiscoveryTask<Status>`)

**Target:** `io.mazewall.profiler.IterativeProfiler`
**Context:** The `IterativeProfiler` uses a feedback loop (Run -> Catch -> Resolve -> Add Rule -> Retry). If resolution fails or retries occur without new rules, it can enter infinite loops.
**Needed:** Use a state machine to track discovery progress: `Discovery<Pending> -> Discovery<Resolved(Path)> -> Discovery<RuleVerified> -> Discovery<Retrying>`. The `retry()` function will only accept `Discovery<RuleVerified>`, proving at compile-time that each iteration contributes a verified rule toward the final policy, preventing infinite-loop regressions.

**Progress (2026-08-23):** The RUNTIME half of this enhancement is implemented:
`IterativeProfiler` runs an explicit state machine (`IterativeProfilerState`: Running ->
Analyzing -> Updating -> Converged/Exceeded/Failed) with a bounded retry counter
(`maxRetries` -> `Exceeded`, issue-112 verified), so discovery can no longer loop forever.
The COMPILE-TIME half (phantom-typed `Discovery<RuleVerified>` gating `retry()`) remains a
future refinement and belongs to the phantom-types family (issue-028/043).
