---
title: "Require coverage evidence when generating DSL"
severity: "HIGH"
status: "open"
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
related_thread: PRRT_kwDOScnnEM6a5aQ6
---

# 🔴 [Severity: P1]: Require coverage evidence when generating DSL

**Context:** When a USER_NOTIF or strace run has dropped or unresolved events but no recorded exec/connect destinations, the documented `result.behavior.toDsl()` path cannot receive `ProfilingResult.coverage`, so this method emits an apparently usable policy even though `result.toPolicy()` correctly rejects the same incomplete run.

**Problem:**
- `BillOfBehavior.kt:103` - toDsl() cannot receive coverage parameter
- Incomplete coverage (dropped events, unresolved) can produce DSL that bypasses guards
- `toPolicy()` correctly rejects incomplete runs, but `toDsl()` does not

**Impact:**
- Operators can copy-paste DSL that allows exec/connect without destination enforcement
- Incomplete profiles can be installed as policies
- Security bypass: unobserved destinations are not enforced

**Needed:**
1. Carry coverage through a result-level DSL API and reject `!coverage.complete` unless the caller explicitly opts into incomplete output (via `allowIncomplete` parameter).

**Notes:** This is the operator-facing path documented in README / GETTING_STARTED. Must match `toPolicy()` guard behavior.
