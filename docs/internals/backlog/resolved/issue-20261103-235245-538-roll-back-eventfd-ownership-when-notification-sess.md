---
title: "Roll back eventfd ownership when notification session construction fails"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "core"
target_modules: [":platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt"
target_symbols:
  - "SeccompSessionHandler"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Roll back eventfd ownership when notification session construction fails

**Context:**
Repository-wide review, 2026-11-04: [PLAT-06](../../reviews/2026-11-04-repository-review-findings.md#plat-06). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · resource lifecycle · confirmed source defect · high confidence · shared supervisor/profiler USER_NOTIF sessions.**

- **Evidence:** `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt:45–50` acquires `EventFdCompletionWakeup` before the fallible listener configuration and buffer acquisition. `configureListenerNonblocking` throws on either fcntl failure (`333–346`). In `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt:547–609`, the handler cleanup `finally` starts only after construction returns. `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/EventFdCompletionWakeup.kt:36–43,97–110` also allocates arena/buffers after acquiring an fd, without constructor rollback.
- **Trigger/effect:** eventfd creation succeeds, then listener F_GETFL/F_SETFL fails (e.g. a native permission/error result), or session-buffer acquisition throws. No handler is returned, so its eventfd and shared arena have no cleanup path. Repeated failed session admissions accumulate kernel descriptors and native storage. This remains a leak even though the enclosing connection correctly closes its listener/socket.
- **Smallest correction:** make the owned session-resource acquisition transactional. Retain each acquired owner until construction commits; close the wakeup on every later failure while preserving the primary error. Give the wakeup factory its own fd/arena rollback on allocation failure. Reuse existing cleanup patterns.
- **Regression:** inject both fcntl failures after an actual recorded wakeup acquisition and assert exactly one wakeup close; inject buffer acquisition failure and cleanup failure, asserting complete independent attempts and suppression. An injected `providedCompletionWakeup` makes the constructor test kernel-free.
- **Tracking:** **NEW exact acquisition rollback gap** after eventfd/class/cleanup searches. Related historical daemon thread-start and session cleanup issues are different acquisition boundaries. Align with `notification-liveness-contract.md:160–167` and `fd-token-ownership.md`'s ownership contract.
- **Dependency:** independent of PLAT-07; both are required for a truthful eventfd lifecycle, and cleanup-error assertions depend on PLAT-04's distinction where descriptor adapters are used.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Resolution — 2026-11-26

Session startup rolls back its completion wakeup when listener setup or buffer
acquisition fails. Partial buffer acquisition returns every acquired lease.
The eventfd factory retires its descriptor and arena on allocation failure.
Cleanup failures retain their evidence under the shared retirement policy.

JDK 25 tests inject both listener setup failures, a session buffer failure,
failures at each eventfd buffer allocation, and cleanup errors. They verify
exactly one close attempt per owner and preservation of the startup error.

---
<!-- id: issue-20261103-235245-538-roll-back-eventfd-ownership-when-notification-sess  file: issue-20261103-235245-538-roll-back-eventfd-ownership-when-notification-sess.md -->
