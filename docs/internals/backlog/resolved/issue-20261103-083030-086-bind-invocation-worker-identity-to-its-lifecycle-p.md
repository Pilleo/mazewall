---
title: "Bind invocation worker identity to its lifecycle phase"
severity: "LOW"
status: "resolved"
priority: low
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
  - ":portals:runtime"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedInvocation.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/OwnedPortalInvocation.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalInvocationLifecycle.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PreparedPortalOperationTest.kt"
target_symbols:
  - "OwnedInvocation"
  - "OwnedPortalInvocation"
open_questions: false
---

# 🟡 [Severity: LOW]: Bind invocation worker identity to its lifecycle phase

**Context:**
Design review finding 9a (2026-11-03): OwnedInvocation and OwnedPortalInvocation store an invocation phase and nullable worker thread separately, with checkNotNull in running cancellation. Existing locks maintain the invariant; no race was demonstrated. Private phase variants can make Running without a worker unrepresentable while retaining the distinct delivery, body-end and physical-retirement boundaries.

**Needed:**
1. Replace the parallel phase/thread fields with private sealed variants carrying the worker only during Running, retaining the Portal Retiring phase. Preserve lock ownership, queued cancellation, generation failure handling, exactly-once admission release and the separate result/executionEnd promises. Cover queued, running and retiring cancellation with deterministic host tests.

**Evidence and scope:**
- `OwnedInvocation.kt:21-25,83-119,137-163` maintains phase and worker under a shared lock and relies on `checkNotNull(worker)` for running cancellation/failure.
- `OwnedPortalInvocation.kt:17-19,29-47,73-104` has the same correlation plus a valid `RETIRING` phase after body end and before physical retirement.
- Use local `Queued`, `Running(worker)`, `Ended` variants, with `Retiring` where required. Preserve the existing pure Portal transition rules; any phase projection should derive from the owned variant. No shared state-machine framework or public API change is needed.
- Keep result delivery distinct from physical completion, cancellation cooperative, and fatal-error/cleanup behavior intact. Do not replace runtime synchronization with a type-only safety claim.

**Acceptance and verification:**
- Deterministic barriers exercise cancellation before dispatch, during a running body and during Portal retirement; running cancellation targets the correct worker and never releases admission early.
- Generation failure, repeated cancellation and normal completion retain exactly-once admission/end accounting and existing failure identity.
- Run focused `OwnedRuntimeTest` and `PreparedPortalOperationTest` checks in their modules, then the required `./gradlew build` completion gate. A green type-state test alone does not establish concurrency correctness.

**Resolution**

Both runtimes now represent active work with a worker-bearing Running state. Portal retirement has a distinct state and the live Enforcer cancellation decision carries its worker without a separate field. JDK 25 checks passed for all 59 OwnedRuntimeTest cases and the Portal fixtures, including cancellation while a retirement future holds admission.

---
<!-- id: issue-20261103-083030-086-bind-invocation-worker-identity-to-its-lifecycle-p  file: issue-20261103-083030-086-bind-invocation-worker-identity-to-its-lifecycle-p.md -->
