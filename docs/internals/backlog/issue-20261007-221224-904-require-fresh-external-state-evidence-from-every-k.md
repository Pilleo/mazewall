---
title: "Require fresh external-state evidence from every kernel integration gate"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "build.gradle.kts"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Require fresh external-state evidence from every kernel integration gate

**Context:**
Container gate run-cef27802bf57 completed successfully but portals runtime integrationTest was UP-TO-DATE from host results. Task success and cached outputs do not prove native behavior under the current kernel/container environment; enforcer native task configuration also lacks an explicit freshness policy.

**Needed:**
1. Declare kernel integration tasks non-cacheable and always fresh while preserving focused host-test caching; add an executable Gradle fixture and distinguish aggregate command success from fresh native proof.

**Evidence (pending repository review):**
- Real Gradle fixture reproduced unchanged integration tasks as UP_TO_DATE (`run-1507899060b0`). The root TestHealthPlugin now makes `integrationTest*` Test tasks always fresh and non-cacheable; ordinary host reuse remains intact.
- The fixture verifies repeat SUCCESS, no cache restoration after deleting outputs, and host UP_TO_DATE (`run-c942bdcdce9a`). No test selections, permission checks or timeouts changed.
- Fresh full build passed in 4m29s (`run-87b7f35d556f`). Fresh mapped Docker full gate passed in 2m38s (`run-a7557f9c33a8`), with Portal parent-authorization tests executed 3/skipped 0/failed 0.
- Earlier successful `run-cef27802bf57` reused Portal integration outputs and is aggregate-command success, not fresh Portal container proof. Fresh replay exposed separate admitted-cancellation and container-reaping defects; their preserving fixes/evidence have their own scopes.

---
<!-- id: issue-20261007-221224-904-require-fresh-external-state-evidence-from-every-k  file: issue-20261007-221224-904-require-fresh-external-state-evidence-from-every-k.md -->
