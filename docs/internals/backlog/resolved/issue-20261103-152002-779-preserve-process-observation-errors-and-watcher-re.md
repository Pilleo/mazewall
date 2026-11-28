---
title: "Preserve process observation errors and watcher retirement"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetRegistration.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetExitCoordinator.kt"
target_symbols:
  - "TierEProcessIdentityVerifier"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve process observation errors and watcher retirement

**Context:**
Linux process observation currently converts I/O, permission, malformed stat and fatal failures to an exited identity, affecting daemon admission and watcher retirement.

**Needed:**
1. Distinguish true exit from observation failures while preserving failure causes and fatal errors; ensure watcher failure remains observable and cannot prevent terminal retirement or leave collection running indefinitely.
2. Test unavailable and exited processes separately from permission, malformed input, ordinary I/O and fatal failures, including watcher liveness and resource retirement.

**Resolution**

Process observation now treats a missing proc-stat file as disappearance and propagates permission, I/O, malformed data, and fatal failures. The watcher delivers failure to its original session owner, which records the cause and retires collection without admitting terminal evidence. JDK 25 regressions passed for exact failure identity and resource close, and the local host build and confined kernel lane passed.

---
<!-- id: issue-20261103-152002-779-preserve-process-observation-errors-and-watcher-re  file: issue-20261103-152002-779-preserve-process-observation-errors-and-watcher-re.md -->
