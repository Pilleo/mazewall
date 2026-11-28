---
title: "Roll back acquired demo processes when output pump startup fails"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "demos"
target_modules: [":demos:agent-sandbox-demo"]
target_files:
  - "demos/agent-sandbox-demo/src/main/kotlin/io/mazewall/demo/agent/OwnedProcessRunner.kt"
target_symbols:
  - "OwnedProcessRunner"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Roll back acquired demo processes when output pump startup fails

**Context:**
Repository-wide review, 2026-11-04: [KD-E2](../../reviews/2026-11-04-repository-review-findings.md#kd-e2). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category:** confirmed source defect; synchronous pump-acquisition exception demonstrated. **Confidence:** high. **Boundary:** optional agent demo, **P2**. This ownership fix is independent of KD-E1/E3 and should precede additional process-demo qualification.

`demos/agent-sandbox-demo/src/main/kotlin/io/mazewall/demo/agent/OwnedProcessRunner.kt:52–60` starts the process, then constructs/submits its output pump **outside any rollback/finally boundary**. The first cleanup-covered work starts only at `:68`; `terminateTree` and `finishOutput` are reached only later at `:90–100`. If executor construction or the first worker's construction/start throws, the method exits with the child and its streams still acquired. There is no returned process owner for the caller to clean up.

This is reachable API behavior, not an assumption that task exceptions escape their `Future`: a `ThreadPoolExecutor` creates its first thread during submission. The self-check uses `Executors.newSingleThreadExecutor` with a controlled failing thread factory and confirms **both** `IllegalStateException` and `LinkageError` are thrown synchronously by `submit`, before it returns a future. The real factory at `OwnedProcessRunner.kt:247–251` constructs a platform thread, so real native-thread/allocation failure is a corresponding production trigger; no exhaustion was attempted. A spontaneous `RejectedExecutionException` from an otherwise private, newly created executor is **not** claimed as its normal trigger.

Connected callers `demos/agent-sandbox-demo/src/main/kotlin/io/mazewall/demo/agent/Tools.kt:129–133,160–170,253–260` receive only an outcome or throwable; none receives the process. Its outer owned invocation/worker may end after this failure without owning the escaped OS child. Completing those higher-level owners cannot repair the missing process rollback. This is not evidence of a syscall authorization bypass.

**Smallest correction:** register provisional child ownership immediately after `start()` returns and keep post-start acquisition under a rollback guard. As soon as the executor exists, include it in that guard. On acquisition failure, attempt exact child/observed-descendant termination and bounded reaping, all process-stream closures, and acquired-executor shutdown independently; preserve interruption and merge failures with fatal-first ordering. Keep the original fatal object primary when appropriate; do not return a normal outcome for an `Error`. Existing local cleanup methods should do the work. No shared process framework/dependency is needed. The catch in `demos/cli-demo/src/main/kotlin/demo/OwnedProcessRunner.kt:43–53` is a useful local pattern, **not** proof that copying it verbatim covers all stream closures or fatal cleanup ordering.

**Meaningful regression:** add the smallest internal pump-executor/factory seam. Acquire one bounded, genuinely long-lived test child with an independently retained test owner; fail executor construction and fail first submission/thread start deterministically, including a sentinel `Error`. Require parent/observed-child physical end and reaping, stream closure, executor termination if acquired, and original/suppressed failure identity. An independent test `finally` must always reap the fixture even on RED. A fake process can additionally record all stream-close attempts and a cleanup failure to check failure ordering; fake-only checks do not prove OS retirement. Do not exhaust native threads to reproduce it.

`OwnedProcessRunnerTest.kt:13–98` covers stderr pressure, timeout/descendants, interruption and late writes after interrupted tools. It contains no pump-acquisition failure seam or assertion. Tests were read, not run.

**Mapping:** **NEW narrow finding** after searches across active/resolved issues and plans. Related completed work is resolved `docs/internals/backlog/resolved/issue-20260910-212626-611-make-cli-and-agent-containment-claims-verifiable.md` and `resolved/issue-20261011-220236-529-preserve-custom-supervised-agent-capabilities-on-o.md`; the latter fixes accepted-handle publication/cancellation, not post-`ProcessBuilder.start` rollback. Older remediation Task 10 and `docs/internals/designs/core/post-review-hardening-evidence.md` PMR-16 describe broad process ownership, not a regression for this window. Coordinator should file/attach a narrowly scoped follow-up, not reopen unrelated fixed admission/HTTP/FORK findings. No issue created by this lane.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Resolution**

Register child and executor ownership before pump submission. Failed acquisition now retires the child, closes all streams, and shuts down any acquired executor with fatal-first failure ordering. The JDK 25 tests reproduced both construction and first-submission failures before the fix and passed with the exact failures retained afterward.

---
<!-- id: issue-20261103-235402-001-roll-back-acquired-demo-processes-when-output-pump  file: issue-20261103-235402-001-roll-back-acquired-demo-processes-when-output-pump.md -->
