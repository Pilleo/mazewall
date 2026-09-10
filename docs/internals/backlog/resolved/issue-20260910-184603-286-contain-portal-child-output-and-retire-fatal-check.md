---
title: "Contain portal child output and retire fatal checkouts"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "portal"
target_modules: [":portal"]
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
target_symbols:
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: HIGH]: Contain portal child output and retire fatal checkouts

**Context:**
Child output used an unbounded shared /tmp sink and fatal RPC Error bypassed checked-out worker retirement.

**Needed:**
1. Bound per-child diagnostics and retire then reap fatal checkouts preserving primary failures.

**Resolution (2026-09-10):** Replaced the shared file sink with a per-child incremental pump capped at 64 lines and 1,024 characters per line, with truncation/drop counts and retained stream failure. Only an exact complete READY line publishes readiness. Fatal checkout errors synchronously retire and reap the worker and retain cleanup failures as suppressed evidence before independent replacement.

**Evidence:** `PortalWorkerOutputTest` covers bounds, controls, exact readiness, and read failure. `ProcessBrokerIntegrationTest.fatal checkout retires child before propagating original error and suppresses cleanup` failed because the child remained alive before the fix and passes afterwards. Existing broker reducer tests cover method and request identity validation. `./gradlew --no-daemon :portal:test :portal:integrationTest :portal:detektMain` passed: 45 host tests, 18 process integration tests, no failures or skips; Detekt has no error findings.

---
<!-- id: issue-20260910-184603-286-contain-portal-child-output-and-retire-fatal-check  file: issue-20260910-184603-286-contain-portal-child-output-and-retire-fatal-check.md -->
