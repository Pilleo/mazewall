---
title: "Align approved accept routing with tracee authority execution"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityExecution.kt"
target_symbols:
  - "TraceeAuthorityExecution"
open_questions: false
---

# 🟡 [Severity: HIGH]: Align approved accept routing with tracee authority execution

**Context:**
Repository-wide review, 2026-11-04: [SUP-02](../reviews/2026-11-04-repository-review-findings.md#sup-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · source-confirmed · high confidence · supervised accept/accept4.**

**Trigger:** an `accept` or `accept4` notification reaches the production authority helper and its JVM policy approves it. This affects both address-less calls and calls with peer-address output.

**Connected evidence:**

- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt:443–448` emits `JvmVerdict.InjectFd` for approved accept.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorNotificationMachine.kt:105–124` converts both approved verdict forms to `SupervisorRoute.Continue` for accept. `SupervisorJvmVerdictResolver.kt:16–19` and `TraceeAuthorityNotificationWork.kt:55–64` carry that route into the helper; `TraceeAuthorityDecisionMachine.kt:9–19` preserves it.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityExecution.kt:80–88` permits `Continue` only for `Spawn`, returning an error for `Accept`. Its special accept continuation exists only under `InjectFd`, a route the normal verdict reducer has already replaced.
- `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/TraceeAuthorityNativeContractTest.kt:90–106` explicitly expects direct accept `Continue` to deny; lines 114–120 exercise only forced `InjectFd`. Those independent tests encode the mismatch rather than a composed production contract.

**Consequence:** approval does not produce an accepting syscall; normal production routing sends EPERM. Testing only the verdict reducer or the forced-injection branch can miss the failure entirely.

**Smallest correction:** align the authority interpreter's accepted routes with the already selected kernel-mediated accept design. Preserve the kernel's peer-output/EFAULT semantics and keep arbitrary pointer-based open/connect continuation forbidden. Reconcile the stale accept documentation and contradictory tests in the same scoped correction.

**Regression:** compose actual verdict decoding → decision machine → authority execution for accept/accept4 with both approved verdicts and both null/non-null output pointers. Require a continuation effect only for the authorized accept cases; denial must remain terminal. Then use a fresh-JVM positive connection/accept control plus writable and read-only peer-output cases.

**Mapping/dependency:** **incomplete/regressed resolved behavior**, `docs/internals/backlog/resolved/issue-20260910-171157-430-restore-accept-peer-address-writes-through-identit.md:27–29` says approved accept continues in the tracee. `docs/internals/designs/core/notification-liveness-contract.md:114–117` and `docs/internals/designs/enforcer/containment-design.md:960–968` still describe the older unsupported-peer-output/address-less-ADDFD split. This is a current route defect, not merely that documented limitation. It blocks the restored-accept claim; broader accept support is outside Plan B's initial read-only-open envelope.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235357-847-align-approved-accept-routing-with-tracee-authorit  file: issue-20261103-235357-847-align-approved-accept-routing-with-tracee-authorit.md -->
