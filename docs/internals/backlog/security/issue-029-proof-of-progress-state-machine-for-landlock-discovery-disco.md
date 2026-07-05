---
title: "Proof-of-Progress State Machine for Landlock Discovery (`DiscoveryTask<Status>`)"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "profiler"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Proof-of-Progress State Machine for Landlock Discovery (`DiscoveryTask<Status>`)

**Target:** `io.mazewall.profiler.IterativeProfiler`
**Context:** The `IterativeProfiler` uses a feedback loop (Run -> Catch -> Resolve -> Add Rule -> Retry). If resolution fails or retries occur without new rules, it can enter infinite loops.
**Needed:** Use a state machine to track discovery progress: `Discovery<Pending> -> Discovery<Resolved(Path)> -> Discovery<RuleVerified> -> Discovery<Retrying>`. The `retry()` function will only accept `Discovery<RuleVerified>`, proving at compile-time that each iteration contributes a verified rule toward the final policy, preventing infinite-loop regressions.
