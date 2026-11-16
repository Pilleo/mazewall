---
title: "Maintain Tier E evidence preflight and offline diagnostic replay"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEEvidence.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEEvidenceTest.kt"
  - "profiler/build.gradle.kts"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticReplay.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticReplayTest.kt"
target_symbols:
  - "TierEEvidence"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Maintain Tier E evidence preflight and offline diagnostic replay

**Context:**
Operator approved the failure-loop assessment improvements: replace repeated ad-hoc schema/path/time-domain comparisons with one tested bounded checker and replay the existing classifier offline without weakening qualification.

**Needed:**
1. Implement explicit versioned live/snapshot/prefix file identities, bounded reference-only summaries, and negative fixtures for the observed verifier mistakes; expose and exercise the existing diagnostic classifier offline.
2. Follow the [reliability milestone plan](../../../superpowers/plans/2026-11-05-tier-e-reliability-milestone.md); keep historical evidence immutable and distinguish replay from runtime qualification.
3. Operator requested Kotlin-native implementation: use the existing tooling/JUnit/Gradle setup and remove the newly introduced Python checker, test suite and build runner.

## Resolution — 2026-11-05

`TierEEvidence` provides versioned live/snapshot/prefix identities through the existing tooling
source set and `:profiler:tierEEvidence`. Eleven JUnit cases exercise domain/schema/refusal/CLI
and output limits, including independently specified v1 data and unsigned ownership values.
The newly introduced Python source, tests and Exec task were removed; predecessor evidence
remains retained. Existing JSON parsing and JDK APIs suffice without new dependencies.

Offline replay reuses the actual classifier and preserves G5's40 matched/3 ambiguous outcome.
Kotlin CLI verification accepted all7 predecessor snapshot members, and live postflight matched
all7 final source identities. Independent review's unsigned UID/GID finding was reproduced,
fixed and rereviewed. Focused JVM checks and ktlint pass; successor `./gradlew build` exits0
(245 tasks:42 executed,203 up-to-date), with20 rootful container cases explicitly skipped.

Evidence and remaining original-roadmap gates: [terminal handoff](../../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/reliability-kotlin-001/handoff.md).
Replay/file identities never grant runtime qualification or discharge unavailable FD history.

---
<!-- id: issue-20261105-084623-741-maintain-tier-e-evidence-preflight-and-offline-dia  file: issue-20261105-084623-741-maintain-tier-e-evidence-preflight-and-offline-dia.md -->
