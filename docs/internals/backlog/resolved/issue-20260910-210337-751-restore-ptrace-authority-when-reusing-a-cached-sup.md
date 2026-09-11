---
title: "Restore ptrace authority when reusing a cached supervisor daemon"
severity: "MEDIUM"
status: "resolved"
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

**Resolution and evidence (2026-09-10):**
- `ac79af08` introduces a shared, checked library-owned ptracer record keyed by the native process backend and exact child identity. Both managers restore a changed grant and skip an unchanged grant after the permanent baseline; failed authorization is never cached or bypassed.
- `DaemonPtracerTest` (4 tests), `SupervisorDaemonManagerTest` (9), and `ProfilerDaemonManagerTest` (3) passed the focused regression gate. The original two manager regressions failed before the fix.
- Dependent CLI `ProfileAndEnforceDemoTest` now requires the real profiled file operation to succeed under cached supervision, while `AgentScenarioFreshJvmTest` requires legitimate work after the process baseline.
- Fresh dependent suite: `./gradlew --no-daemon --no-build-cache :demos:cli-demo:cleanTest :demos:agent-sandbox-demo:cleanTest :demos:cli-demo:test :demos:agent-sandbox-demo:test` passed, 29 CLI and 18 agent tests, no skips/failures. The supervisor/profiler sequence and baseline sequence both execute in isolated JVMs.
- The core commit passed its ordinary security-audit and host/portal integration hook. No `PTRACER_ANY` or permission fallback is used. Concurrent independent observer daemons remain outside the single-PID Yama contract documented in `security-considerations.md`.

---
<!-- id: issue-20260910-210337-751-restore-ptrace-authority-when-reusing-a-cached-sup  file: issue-20260910-210337-751-restore-ptrace-authority-when-reusing-a-cached-sup.md -->
