---
title: "Investigate intermittent interrupted broker close cleanup classification"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: [":portals:runtime"]
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
target_symbols:
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate intermittent interrupted broker close cleanup classification

**Context:**
The unchanged full scanner hook failed interrupted close retains active acquisition for later cleanup at line142: closeFailure was not IncompleteBrokerCleanupException (1 of32 native tests). A fresh exact focused invocation then passed1/0/0/0 and the one full unchanged retry passed, producing9c41f3e7. No timeout, assertion, skip or Portal source was changed. Root cause remains unproven; retain failure XML/log and verify causal cleanup ordering rather than treating the rerun as a fix.

**Needed:**
1. Reproduce the cleanup classification with retained failed and passing traces; inspect the startup/closer causal wait and exception, then add a meaningful deterministic regression before any source correction.

---
<!-- id: issue-20261009-000016-699-investigate-intermittent-interrupted-broker-close-  file: issue-20261009-000016-699-investigate-intermittent-interrupted-broker-close-.md -->
