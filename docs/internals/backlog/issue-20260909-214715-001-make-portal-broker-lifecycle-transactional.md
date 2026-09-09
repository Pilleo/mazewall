---
title: "Make portal broker lifecycle transactional"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "portal"
target_modules: ["portal"]
target_files: ["portal/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt", "portal/src/main/kotlin/io/mazewall/portal/ProcessBrokerLifecycle.kt"]
target_symbols: ["ProcessBroker", "ProcessBrokerLifecycleMachine"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make portal broker lifecycle transactional

**Context:**
Independent worker registration allowed a partially initialized pool to escape and separate
started/closed flags could disagree with queue and ownership state during shutdown.

**Needed:**
1. Make pool publication, rollback, replenishment, and shutdown follow one lifecycle authority.

**Resolved:**
1. Added the explicit New/Starting/Running/Closing/Closed/Failed reducer.
2. Initial workers remain provisional until the complete batch is ready under one deadline.
3. Startup rollback, destroy-before-replenish, bounded reaping, original-error preservation,
   method validation, and return-vs-close serialization are covered by portal unit/integration tests.

---
<!-- id: issue-20260909-214715-001-make-portal-broker-lifecycle-transactional  file: issue-20260909-214715-001-make-portal-broker-lifecycle-transactional.md -->
