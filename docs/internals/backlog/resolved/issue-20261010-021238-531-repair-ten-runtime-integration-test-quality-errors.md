---
title: "Repair ten runtime integration-test quality errors within five scoped fixtures"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - "portals/runtime"
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PreparedPortalOperationIntegrationTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerGenerationTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/StableWorkerCacheGenerationTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/WorkerPhysicalReceiptLifetimeTest.kt"
target_symbols:
  - "ProcessBrokerIntegrationTest"
open_questions: false
---

# ✅ [RESOLVED]: Repair ten runtime integration-test quality errors within five scoped fixtures

**Context:**
COORDINATOR72 explicit user scope: authoritative coordinator71 unqualified detektIntegrationTest failed on ten hard errors in five unchanged runtime integration-test files; production warning ratchet passed. Test-only cleanup receipts, checked injected directories and Locale.ROOT; no runtime production or protocol change.

**Needed:**
1. Repair four injected-directory lateinit declarations and PreparedPortalOperation locale mechanically, then preserve actual ProcessBroker failure and physical-cleanup evidence through consumed Results.
2. Keep JUnit TempDir injection real: use nullable injected storage and a checked `requireNotNull` access; use `Locale.ROOT` for the diagnostic. No invented directory or rule-name waiver.
3. Replace ProcessBroker generic catches at original lines 132/390/430/596 and swallowed cleanup at 596 with actual Result capture consumed outside the capture boundary. Preserve primary failures, fatal errors, interrupts and physical broker-cleanup evidence. A semantic gap requires same-boundary failing/passing evidence with existing controls.
4. This issue authorizes only these five new integration-test paths. Combined source-only work remains exactly 21 paths (the preserved 16 plus these five). Existing warning repairs are limited to TraceeAuthorityIntegrationTest, BpfHardeningTest, DedicatedProcessBaselineTest and SupervisorProxyIntegrationTest: cohesive ownership helpers must eliminate the six exact added source/message diagnostics against the frozen coordinator69 report. Any affected six-file receipt edits require an explicit causal declaration before granting write permission.
5. Preserve production runtime/protocol/layout/policy/API/dependencies and all assertions, skips, pressure, profiles, timeouts and cleanup budgets. Verify targeted runtime compilation/existing controls, then unchanged all-module integration Detekt and production warning ratchet. Do not archive this issue without actual evidence.


## Closure evidence — Task 9 controller draft

**Proposed resolved, 2026-10-11. Tested integrated producer:
`dc6f7bf44b68f494a4cb609702dc97c72fb892c4`.** The five declared fixtures retain real
JUnit TempDir injection via checked nullable storage, `Locale.ROOT`, actual consumed
failure Results and original primary/fatal/interrupt/physical-cleanup contracts.
Accepted cumulative source/static reviews and integration preserve the production
runtime/protocol boundary. All current integration Detekt reports are hard0 with
added-warning0 and the unchanged production ratchet.

Current P's Portal integration task executes **six suites / 33 PASS / 0 skips**,
including **24** Broker cases, required generation **2**, parent authorization **3**,
stable cache **1** and worker physical receipt **1** (plus prepared-operation
cases). Packaged Koog at the same P additionally proves real terminal-state-loss,
cancellation, retained worker owners and joined close: **four workers / 17 observed
owned identities ended**, **12 pathnames restored**. These counts are separate,
not a cumulative JUnit total. Receipts: accepted `task-3-fix2-review.md`,
`task-4-root-review.md`, `task-5-6-review.md`, `verification/combined.suite-acceptance.json`,
current frozen Portal XML and `verification/task-6-native-reconciliation.json`.
The preserved newest Broker extension remains unverified and is not part of closure.

---
<!-- id: issue-20261010-021238-531-repair-ten-runtime-integration-test-quality-errors  file: issue-20261010-021238-531-repair-ten-runtime-integration-test-quality-errors.md -->
