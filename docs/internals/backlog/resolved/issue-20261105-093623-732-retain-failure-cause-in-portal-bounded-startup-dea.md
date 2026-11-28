---
title: "Retain failure cause in portal bounded startup deadline fixture"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "portal"
target_modules: ["portals:runtime"]
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
target_symbols:
  - "ProcessBrokerIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain failure cause in portal bounded startup deadline fixture

**Context:**
The Tier E reliability full build on 2026-11-05 failed the unchanged worker connection and readiness share one bounded startup deadline test at line540: child?.isAlive == false. The XML cannot distinguish a null unpublished child from a still-live child; the expected startup exception cause is discarded. Raw XML and full-build log are preserved under .superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/reliability-001/. No portal source changes or established retirement defect.

**Needed:**
1. Capture the actual startup exception and launcher-entry/child identity with original150ms budget, provide unconditional cleanup, and distinguish prelaunch expiry from physical-retirement failure before proposing any fix; relate to issue20261020-090040-415 without assuming the same cause.

Related setup-budget evidence: [late-publication fixture](../issue-20261020-090040-415-retain-prelaunch-failure-evidence-and-cleanup-in-l.md).
The new failure concerns a different test and does not establish that its cause is identical.

The unchanged test passed in the successor full build at09:55:12UTC (24 owning-class cases,
zero failures/skips). This passing rerun is retained under `reliability-kotlin-001/portals/`;
it does not explain the earlier failure, so the diagnostic issue remains open.

**Resolution**

The startup test keeps the 150 ms budget, records launcher entry and child identity, attaches the startup exception to failures, and releases the broker and fixture child on every exit. The JDK 25 ProcessBrokerIntegrationTest suite passed.

---
<!-- id: issue-20261105-093623-732-retain-failure-cause-in-portal-bounded-startup-dea  file: issue-20261105-093623-732-retain-failure-cause-in-portal-bounded-startup-dea.md -->
