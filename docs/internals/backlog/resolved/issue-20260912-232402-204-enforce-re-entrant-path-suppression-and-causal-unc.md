---
title: "Enforce re-entrant path suppression and causal-uncertain classification in attribution"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/InvocationReplayMachine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/AttributionCollector.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/InvocationReplayMachineTest.kt"
target_symbols:
  - "InvocationReplayMachine"
  - "AttributionCollector"
  - "ResolutionStatus"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Enforce re-entrant path suppression and causal-uncertain classification in attribution

**Context:**
When a supported native wrapper invokes a Java callback that subsequently executes an unsupported native syscall or another wrapped call, attributing inner syscalls directly to the outer invocation creates false exactness. Tier E attribution must treat re-entrant scopes as non-exact (`CAUSAL_UNCERTAIN` or `UNKNOWN`) unless an explicit, valid inner boundary is entered. Furthermore, capture failures within nested scopes must invalidate exact attribution rather than leaving stale positive attribution.

**Needed:**
1. In `InvocationReplayMachine`, when `reentrantDepth > 1`, set attribution status to `CAUSAL_UNCERTAIN` (or explicit un-attributed status) rather than inheriting the outer `activeInvocationId`.
2. In `AttributionCollector`, map `CAUSAL_UNCERTAIN` to `ResolutionStatus.CAUSAL_UNCERTAIN` and verify it cannot be marked `RESOLVED`.
3. Add tests in `InvocationReplayMachineTest`:
   - Supported native → Java callback → unsupported native syscall: inner context attribution is explicit (`CAUSAL_UNCERTAIN`) and not falsely exact.
   - Active outer scope + inner capture failure: wrapper re-entrancy invalidates exact state rather than leaving stale positive attribution.

---
<!-- id: issue-20260912-232402-204-enforce-re-entrant-path-suppression-and-causal-unc  file: issue-20260912-232402-204-enforce-re-entrant-path-suppression-and-causal-unc.md -->
