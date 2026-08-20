---
title: "BillOfBehavior.toDsl does not refuse observed exec/connect destinations"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/BillOfBehaviorTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: BillOfBehavior.toDsl does not refuse observed exec/connect destinations

**Context:** `toPolicy()` now throws `IncompleteProfileException` when `execs` (or `connects`) are non-empty unless `allowIncomplete=true`. `toDsl()` is the operator-facing path documented in README / GETTING_STARTED (`result.behavior.toDsl()`). It still emits a snippet that unblocks `EXECVE` / `SOCKET` / `CONNECT` from the syscall set. A warning comment is added only when `allowIncomplete` is already true; the default `allowIncomplete=false` path neither throws nor warns. Operators who copy-paste DSL can install a policy that allows exec/connect without destination enforcement, which is the gap `toPolicy()` was just closed for.

**Needed:**
1. Make `toDsl()` share the same incomplete-profile gate as `toPolicy()`: throw unless `allowIncomplete=true`.
2. When `allowIncomplete=true`, keep the WARNING comment (today this is the only path that emits it).
3. Tests: `toDsl()` throws on `execs` / `connects` without `allowIncomplete`; with the flag, the snippet contains the warning and still lists unblocked syscalls.

**Resolved:** Implemented in this working tree (`BillOfBehavior.toDsl` throws unless `allowIncomplete=true`; `BillOfBehaviorTest` covers exec/connect). Operator docs/demos still lag — see `issue-20260820-185331-todsl-docs-and-coverage-gate`.
