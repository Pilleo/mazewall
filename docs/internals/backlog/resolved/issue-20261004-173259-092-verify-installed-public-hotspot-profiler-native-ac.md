---
title: "Verify installed public HotSpot profiler native acceptance and compatibility"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot"
target_symbols:
  - "HotSpotPublicIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Verify installed public HotSpot profiler native acceptance and compatibility

**Context:**
Approved milestone 6 needs unique-token ordered attribution, supported current-JVM workload and real native/public fault acceptance with provenance and bounded claims

**Needed:**
1. Add guarded installed public API corpus and native fault fixtures, then verify graph fingerprint compatibility and measurements

**Resolution:** The complete guarded six-case public/native integration suite passes
with zero skips: unique-token ordered public attribution, retained-evidence pressure,
real helper permission/startup deadline refusal, target kill/restart ownership,
supported ordinary current-JVM workload and matched public/standalone target windows.
Three matched 400-open runs each report checksums and actual latency windows; no
threshold was introduced. Go verified replay/readout deadlines, true first-definition
recovery, unloading, same-PID exec and event/definition/auxiliary kernel retirement.
The installed graph/asset fingerprints are unchanged; collection loss stays partial.
See [acceptance evidence](../../../plans/2026-10-05-mazewall-hotspot-native-acceptance-milestone.md).

---
<!-- id: issue-20261004-173259-092-verify-installed-public-hotspot-profiler-native-ac  file: issue-20261004-173259-092-verify-installed-public-hotspot-profiler-native-ac.md -->
