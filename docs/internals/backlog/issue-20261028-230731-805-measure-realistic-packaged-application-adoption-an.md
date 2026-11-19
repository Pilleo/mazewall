---
title: "Measure realistic packaged application adoption and containment cost"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "benchmarks"
target_modules: []
target_files:
  - "benchmarks/integration-boundaries/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Measure realistic packaged application adoption and containment cost

**Context:**
Architecture assessment finding 6: the existing benchmark is a bounded ASCII checking harness with concurrency1 (lines141-147), and lines249-253 explicitly disclaim general overhead rankings. GETTING_STARTED.md:26-41 recommends local source installation. Acceptance: one representative external application consumes pinned artifacts and retains reproducible throughput/tail-latency, startup/replacement, memory/thread scaling, overload, cancellation/shutdown and dependency-upgrade observations with declared platform, workload and measurement limits. Record unfamiliar-developer integration effort. Reuse existing consumer gates and retain the old benchmark claims unchanged. Coordinate rather than duplicate existing issues issue-20261013-005316-083-record-independent-production-operation-and-launch, issue-20261013-005316-364-verify-candidate617-through-authorized-remote-arti and issue-20261016-015032-003-repair-first-time-library-installation-guidance. Remote publication/retrieval and production launch authority remain with those owners.

**Needed:**
1. Select a representative application and explicit performance/adoption budgets, then define a reproducible packaged-consumer qualification using the existing artifact and launch workflows; collect evidence before choosing further integration features.

---
<!-- id: issue-20261028-230731-805-measure-realistic-packaged-application-adoption-an  file: issue-20261028-230731-805-measure-realistic-packaged-application-adoption-an.md -->
