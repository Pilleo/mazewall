---
title: "toDsl fail-closed gate is not reflected in docs, demos, or coverage.complete"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
  - "GETTING_STARTED.md"
  - "README.md"
  - "profiler/README.md"
  - "docs/internals/designs/profiler/profiler-design.md"
  - "demos/cli-demo/src/main/kotlin/demo/ProfileAndEnforceDemo.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: toDsl fail-closed gate is not reflected in docs, demos, or coverage.complete

**Context:** `BillOfBehavior.toDsl()` now throws `IncompleteProfileException` when `execs` or `connects` are non-empty unless `allowIncomplete=true`. That matches `toPolicy()` for destination enforcement. Three gaps remain:

1. Operator docs still show `println(result.behavior.toDsl())` with no `allowIncomplete` and no mention of the exception (`GETTING_STARTED.md`, root `README.md`, `profiler/README.md`, `profiler-design.md`).
2. `demos/cli-demo` and `ProfilerIntegrationTest` call `toDsl(...)` without the flag. Workloads that observe exec/connect will throw at the documented copy-paste step.
3. `toPolicy()` also throws when `coverage.complete` is false. `toDsl()` has no `coverage` parameter, so an incomplete USER_NOTIF/strace run with empty execs/connects still emits pasteable DSL. The two APIs are not the same gate.

`toDsl()` also fabricates `ProfilingCoverage.absent()` instead of the run's evidence, so the exception is less actionable than `toPolicy()`.

**Needed:**
1. Document the throw and `allowIncomplete=true` on every operator-facing `toDsl()` example.
2. Share one incomplete-profile helper between `toPolicy()` and `toDsl()`; optionally accept `coverage` on `toDsl()`.
3. Update the CLI demo (and any integration test that prints DSL for exec/connect workloads) to handle `IncompleteProfileException`.
4. Tests: `toDsl()` throws on `coverage.complete=false` if that parameter is added; docs examples compile against the new signature.
