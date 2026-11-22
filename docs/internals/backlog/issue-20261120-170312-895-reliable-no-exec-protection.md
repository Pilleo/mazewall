---
title: "Reliable no-exec protection"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Reliable no-exec protection

**Context:**
Block execve and execveat so HotSpot JIT thread creation works; review alternative process-creation mechanisms without blocking legitimate JVM thread creation. (Java4Shell-roadmap P0 #2)

**Needed:**
1. Establish a regression matrix of thread creation paths used by supported JDKs (Loom, C1/C2, profiler/service threads) and container runtimes.
2. Implement execve/execveat denial and verify every matrix path still creates threads and the denial fires for real execs.
3. Review alternative process-creation mechanisms and document rejected candidates with reasons in the issue.
4. Wire readiness assertions for the denied-exec state and coverage of all supported JDKs.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170312-895-reliable-no-exec-protection  file: issue-20261120-170312-895-reliable-no-exec-protection.md -->
