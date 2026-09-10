---
title: "Record fail-closed supervisor routing and installation evidence"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
target_symbols:
  - "SupervisorSessionHandler"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Record fail-closed supervisor routing and installation evidence

**Context:**
RH-09 through RH-14: unresolved routes, handlerless notify, permanent installation publication, listener health, admission and authority helper ownership require recorded regression evidence.

**Needed:**
1. Record implementing commits and executable regression evidence in the canonical hardening manifest.

**Resolution:** RH-09–RH-14 are implemented by commits d72b577a, e06de4cf, 24c5f5cb, 8253fd21, 219392fd, 8c725eff, bee9ffc3. Regression owners: SupervisorNotificationMachineTest, SupervisorBpfCompilationTest, SupervisorInstallationLifetimeTest, SeccompInstallPublicationTest, SupervisorAdmissionTest and TraceeAuthorityIntegrationTest.

**Verification:** Focused `:enforcer:test` and authority `:enforcer:integrationTestFreshJvm` suites passed for the routing, lifecycle, admission and helper packages. Peer-address accept remains explicitly unsupported and tracked separately; this record does not close that functionality gap.

The [canonical finding manifest](../../designs/core/post-review-hardening-evidence.md#liveness-and-boundary-review-rh) maps individual methods and gates. [Final integration evidence](../../designs/core/review-hardening-verification.md) distinguishes fresh execution from cached outputs and does not imply aggregate acceptance from this scoped resolution.

---
<!-- id: issue-20260910-225752-609-record-fail-closed-supervisor-routing-and-installa  file: issue-20260910-225752-609-record-fail-closed-supervisor-routing-and-installa.md -->
