---
title: "Retain supervisor helper capacity across fatal worker replacement"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperPool.kt"
target_symbols:
  - "SocketHelperPool"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain supervisor helper capacity across fatal worker replacement

**Context:**
Repository-wide review, 2026-11-04: [SUP-04](../reviews/2026-11-04-repository-review-findings.md#sup-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · reproduced · high confidence · daemon-wide helper capacity.**

**Trigger/interleaving:** a helper operation throws `Error`; retiring its helper then throws because the child has not been reaped. The executor replaces the failed Java worker and another job is admitted.

**Connected evidence:**

- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperPool.kt:26,42–53,182–198,220–225` keys capacity ownership by `Thread`. Failed `worker.close()` retains the old map entry, as intended.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketJobOutcome.kt:16–20` completes the future exceptionally and rethrows the fatal error. The executor replaces that worker thread.
- The new worker's `helpers.computeIfAbsent(Thread.currentThread())` at `SocketHelperPool.kt:187` creates a new helper even though the old thread's helper remains owned and unreaped. `SocketHelperSlot.kt:23–28` blocks reuse of the old slot, but cannot stop allocation under a new thread key.

**Actual reproduction:** current pool/request/slot/cleanup/outcome sources with boundary-only stubs; `active=1`, injected `LinkageError`, and injected failed reap. Output:

```text
configured active=1; factories=2; unreleased helper owners=2
cleanup retry released both owners; no native helpers were started
```

**Consequence:** the documented active-helper/process-ownership cap is not retained across fatal executor-worker replacement. Repeated fatal/failing-retirement events can accumulate unreaped helpers and capability owners beyond the configured limit. The reproduction proves the ownership/allocation defect; it does not simulate an actual uninterruptible kernel process.

**Smallest correction:** count helper ownership independently of Java thread identity and retain the reservation until successful reap, or make failed retirement terminal for pool admission. Preserve fatal exception identity and retryable cleanup.

**Regression:** combine fatal execution and failed close in the same test, wait for executor replacement, submit another job and require no new helper beyond retained capacity. Release cleanup and verify every owner is eventually retired. Existing `SocketHelperPoolTest.kt:96–129` covers fatal execution only with successful close.

**Mapping/dependency:** **expanded residual** of `docs/internals/backlog/resolved/issue-20260909-183425-220-preserve-supervised-socket-descriptions-with-bound.md:26–30`. It violates `docs/internals/designs/core/notification-liveness-contract.md:110–113` and `containment-design.md:970–974`. Fix is foundational to all authority-helper routes and Plan B4/B6/B7; unrelated profiler pool behavior is owned by lane 05.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235358-819-retain-supervisor-helper-capacity-across-fatal-wor  file: issue-20261103-235358-819-retain-supervisor-helper-capacity-across-fatal-wor.md -->
