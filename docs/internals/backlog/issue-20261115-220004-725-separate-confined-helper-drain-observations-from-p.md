---
title: "Separate confined-helper drain observations from physical retirement"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/execution/ConfinedHelperRuntimeDrainTest.kt"
target_symbols:
  - "ConfinedHelperRuntimeDrainTest"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Separate confined-helper drain observations from physical retirement

**Context:**
Authentic CI RED at PR head `b356c8e301da43e0114b0019f0233b767b7bfc2a`, run `43760530358`, Core Quality and Kernel Validation job `132954093610`, artifact `14180655992`: `:enforcer:test` reported 1,639 tests, 2 failed, 2 skipped.

1. `transferred live setup remains counted while connect is blocked()` (6.117s): after the second `DrainReport.Incomplete`, the assertion expected `supportCloseCalls=1` but observed `0` at test source line 3287. The receipt does not establish whether cleanup was pending, failed, or omitted.
2. `cancelled queued helper preparation cannot strand the retained manager admission()` (1.054s): barrier submission at test source line 7441 threw `RejectedExecutionException` while the one-worker executor had one active and one queued task.

`Future.get()` on the blocking task does not prove physical manager-task quiescence; `DrainReport.Incomplete` does not prove cleanup completion. Neither failure was reported as `EPERM`/`EACCES` or a timeout, and the timing pattern does not establish flakiness or root cause. Scope is confined to the named enforcer test file: fix test-only synchronization/observation boundaries while preserving all assertions, deadlines, queue capacity, and production lifecycle logic. Do not claim either failure harmless or discard the authentic CI RED. The retained full local gate at this same source revision is separate evidence and does not erase these CI failures.

**Focused verification update (2026-11-15):** The two named methods, the complete 209-test `ConfinedHelperRuntimeDrainTest` class, and Enforcer test lint passed in the focused local run; its runner retired and source/index remained unchanged. This local result validates the fixture update only at that workspace state. The historical Full003 pass was on commit `b356c8e301da43e0114b0019f0233b767b7bfc2a` before the test-only change and does not qualify it. Fresh CI for the updated PR head remains pending. Keep the issue OPEN until that CI result is reviewed; no production behavior changed or is claimed fixed by this fixture-only issue.

**Needed:**
1. Use the retained JUnit XML and CI artifact to preserve both exact failures, then prove the test fixture can observe the existing queued-task quiescence latch and actual dedicated retirement actor without adding production hooks; retain original assertions and deadlines.

---
<!-- id: issue-20261115-220004-725-separate-confined-helper-drain-observations-from-p  file: issue-20261115-220004-725-separate-confined-helper-drain-observations-from-p.md -->
