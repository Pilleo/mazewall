---
title: "Restore ptrace authority when reusing a cached supervisor daemon"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: [platform, enforcer, profiler]
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt"
  - "platform/src/main/kotlin/io/mazewall/core/DaemonPtracer.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerDaemonManager.kt"
target_symbols: 
  - "getOrSpawnSharedDaemon"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Restore ptrace authority when reusing a cached supervisor daemon

**Context:**
Profiling assigns PR_SET_PTRACER to its daemon. Cached supervisor reuse does not restore that process-global grant, so subsequent supervised file opens fail before authorization with EACCES.

**Needed:**
1. Add regressions for cached ptracer reauthorization and failure propagation, restore the grant, and verify profile-to-enforcement file reads.

---
<!-- id: issue-20260910-210337-751-restore-ptrace-authority-when-reusing-a-cached-sup  file: issue-20260910-210337-751-restore-ptrace-authority-when-reusing-a-cached-sup.md -->
