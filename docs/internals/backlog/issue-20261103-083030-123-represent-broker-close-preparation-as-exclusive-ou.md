---
title: "Represent broker close preparation as exclusive outcomes"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalBrokerOwnershipTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalOwnershipDeadlineTest.kt"
target_symbols:
  - "ClosePreparation"
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: LOW]: Represent broker close preparation as exclusive outcomes

**Context:**
Design review finding 9b (2026-11-03): ProcessBroker.ClosePreparation combines executeCleanup with nullable waitForOtherClose, startupToJoin and operation fields. Its factories currently keep combinations consistent, but close still needs checkNotNull and implicit field correlations. This is local maintainability hardening; no concurrent-close defect was demonstrated.

**Needed:**
1. Replace ClosePreparation with private AlreadyClosed, Wait(operation) and Execute(operation, startupToJoin) alternatives, and handle them exhaustively. Keep the pure broker lifecycle, synchronization, shared close result, deadlines and independent partial-cleanup flags intact. Verify concurrent closers, startup cancellation and failed cleanup retry using existing broker fixtures.

**Evidence and scope:**
- `ProcessBroker.kt:862-877` admits arbitrary Boolean/nullable combinations; its factories currently select only three valid shapes. `close` at `:366-376` unpacks those shapes using conditionals and `checkNotNull`.
- Replace that private decision value and its construction/dispatch sites. Retain `ProcessBrokerLifecycleMachine` and the current owner registries; partial retirement of individual resources remains valid independent state.
- Read the [process Portal lifecycle contract](../designs/enforcer/process-portal-design.md#authoritative-lifecycles) before implementation. The separate [invocation phase issue](issue-20261103-083030-086-bind-invocation-worker-identity-to-its-lifecycle-p.md) does not block this change.

**Acceptance and verification:**
- Already-closed calls perform no new cleanup; overlapping closers observe the same owned operation/result; failure releases waiters and retains cleanup ownership for retry.
- Startup cancellation/join and failed-cleanup retry retain their existing deadlines and failure propagation. Successful `Closed` still requires physical retirement.
- Run `./gradlew :portals:runtime:test`, including ownership/deadline fixtures, then the required `./gradlew build` completion gate.

---
<!-- id: issue-20261103-083030-123-represent-broker-close-preparation-as-exclusive-ou  file: issue-20261103-083030-123-represent-broker-close-preparation-as-exclusive-ou.md -->
