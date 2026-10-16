---
title: "Replace stale synchronization waits with observable bounded conditions"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/test/kotlin/io/mazewall/TestSynchronizationPolicyTest.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Replace stale synchronization waits with observable bounded conditions

**Context:**
A fresh :enforcer:test run fails the existing synchronization gate on eight unchanged profiler test/workload sleeps. The cached unitCheck baseline did not rerun this gate. Polling readiness must use existing bounded await helpers; deliberate workload pacing must be explicitly distinguished from synchronization.

**Needed:**
1. Inspect each reported sleep, replace startup timing assumptions with observable readiness or the existing awaitCondition utility, document actual workload pacing, and rerun the synchronization gate without weakening it.

---
<!-- id: issue-20261006-021624-843-replace-stale-synchronization-waits-with-observabl  file: issue-20261006-021624-843-replace-stale-synchronization-waits-with-observabl.md -->
**Execution evidence:**

- Root module gate reported eight pre-existing profiler synchronization/pacing sleeps. These were not caused by policy snapshot changes.
- Native attach and acceptance harness polling use the shared monotonic, interruption-aware `awaitCondition` helper.
- Tier E readiness and terminal publication wait on actual files and fail on premature process exit.
- The post-`prepared` delay was removed because the Java fixture publishes that marker after its warmup has completed.
- Deliberate measured workload pacing remains unchanged and is explicitly documented as workload delay.
- No synchronization gate exclusion or source-scan rule was weakened.
- `./gradlew :enforcer:test`, `./gradlew :profiler:compileIntegrationTestKotlin`, and `./gradlew :profiler:test` passed.
- Optional rootful HotSpot/Tier E acceptance fixtures were compiled, not run; no rootful native claim is made.
