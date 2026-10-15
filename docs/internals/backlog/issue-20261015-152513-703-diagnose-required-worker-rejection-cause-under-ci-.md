---
title: "Diagnose required worker rejection cause under CI cleanup timing"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":portals:runtime"]
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerLaunchProfileTest.kt"
target_symbols:
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose required worker rejection cause under CI cleanup timing

**Context:**
CI run38757090872 now completes enforcer kernel suites and fails only RequiredWorkerLaunchProfileTest unsupported-contract assertion: broker startup fails but expected worker rejection reason is absent. The assertion omitted the actual exception and bounded child diagnostics. Ten repeated local runs of all five negative modes pass, and prior CI also observed retained incomplete cleanup. Preserve zero guest entry, ownership and specific-reason assertions; capture actual cause before deciding between lifecycle bug and fixture timing.

**Needed:**
1. Include actual startup exception in assertion diagnostics and inspect the next complete kernel JUnit artifact; do not accept generic failure or weaken cleanup.

---
<!-- id: issue-20261015-152513-703-diagnose-required-worker-rejection-cause-under-ci-  file: issue-20261015-152513-703-diagnose-required-worker-rejection-cause-under-ci-.md -->
