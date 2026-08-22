---
title: "Require coverage evidence when generating DSL"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingResult.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789280
---

# 🔴 [Severity: HIGH]: Require coverage evidence when generating DSL

**Review (2026-08-21):** Residual gap. Duplicate `issue-20260821-000002-todsl-coverage-gate` is closed. `toPolicy()` already refuses incomplete coverage. `toDsl()` does **not**.

**Current tree:**
- `ProfilingResult.toPolicy()` passes `coverage` into `BillOfBehavior.toPolicy` and throws `IncompleteProfileException` unless `allowIncomplete`.
- The documented DSL path is `result.behavior.toDsl("Policy.PURE_COMPUTE_UNSAFE")` (`ProfilingResult` KDoc). That method has **no** `coverage` parameter.
- `toDsl` only fail-closes on non-empty `execs` / `connects`. A USER_NOTIF/strace run with `droppedEvents > 0` or unresolved paths and empty exec/connect sets still emits copy-pasteable policy source.

**Do not:**
- Delete `toDsl` or make it always succeed with a comment.
- Gate only execs/connects and call the issue done (already done; that is not enough).
- Pass `ProfilingCoverage.absent()` as if that were complete evidence.

**Do:**
1. Add `coverage: ProfilingCoverage?` (and `allowIncomplete`) to `toDsl`, same contract as `toPolicy`.
2. Prefer a `ProfilingResult.toDsl(...)` that forwards `this.coverage` so the documented operator path cannot skip evidence.
3. If `coverage` is omitted on the Bob-level method, treat it as incomplete (`ProfilingCoverage.absent()` already has `complete=false`) and refuse unless `allowIncomplete=true`.

**Tests:**
- `droppedEvents=1`, empty execs/connects: `result.toPolicy()` already throws; `toDsl` must throw too.
- Same Bob with `allowIncomplete=true` may emit DSL but must not claim completeness.
- Non-empty `execs` remains rejected (existing gate).

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789280
