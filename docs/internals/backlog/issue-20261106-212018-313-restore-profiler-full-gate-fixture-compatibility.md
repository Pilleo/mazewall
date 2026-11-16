---
title: "Restore profiler full gate fixture compatibility"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/internal/DaemonStartupRollbackTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierEEvidenceFileAccessTest.kt"
target_symbols:
  - "supervisorDriver"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Restore profiler full gate fixture compatibility

**Context:**
Profiler full gate 022 exposed stale reflective supervisor constructor and empty supplementary groups in profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierEEvidenceFileAccessTest.kt; retain open until focused container and full gate evidence confirms compatibility.

**Needed:**
1. Verify fixture fixes with focused host and constrained container tests, then full gate.

---
<!-- id: issue-20261106-212018-313-restore-profiler-full-gate-fixture-compatibility  file: issue-20261106-212018-313-restore-profiler-full-gate-fixture-compatibility.md -->
