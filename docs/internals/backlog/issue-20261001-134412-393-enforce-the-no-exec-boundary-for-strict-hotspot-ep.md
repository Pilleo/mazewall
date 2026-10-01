---
title: "Enforce the no-exec boundary for strict HotSpot epoch sessions"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-design.md"
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-plan.md"
  - "experiments/hotspot-agentless/results/identity-investigation.md"
target_symbols:
  - "HotSpotEpochTarget"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Enforce the no-exec boundary for strict HotSpot epoch sessions

**Context:**
Second review confirmed PID and proc start ticks survive exec. Matching JVM ELF, load bias and a restarted counter can reuse old producer references before a periodic mapping check, so the plan does not define how strict mode refuses the unsupported JVM incarnation.

**Needed:**
1. Specify authoritative pre-capture exec invalidation or restrict checked capture to a verifiably controlled fixture lifetime; add same-PID same-build exec reuse validation.
2. Define attachment ordering and same-path/same-bias reuse behavior; retrospective periodic map checks cannot certify samples accepted earlier.

**Evidence:** ordinary-user exec probe retained PID 2755636 and start ticks
7137991 before/after exec. Pinned profiler scheduler attachment observes process
free, not exec; its interpreter assignment is cached by PID/on-disk identity.
This confirms a missing boundary contract. It does not reproduce HotSpot label
reuse across exec; that remains a required validation case if general strict CLI
capture is retained.

**Closure evidence:** an unsupported JVM incarnation cannot receive the checked
label by reusing pre-exec references/epoch values. A live same-build exec test or
an explicit enforceable controlled-fixture restriction supports the revised scope.

**Implemented; independent review pending:** an owned `sched_process_exec`
attachment precedes profile discovery. A single monotonic four-byte session
poison word is checked before/after strict resolution and by the existing pidfd
watcher. The actual-kernel `TestExecBarrierPoisonsSamePIDBeforeNewImageRuns` passes
with collector-equivalent host-PID/tracefs configuration. Epoch zero remains
valid while unsupported exec ends the session; the collector does not rebaseline.

---
<!-- id: issue-20261001-134412-393-enforce-the-no-exec-boundary-for-strict-hotspot-ep  file: issue-20261001-134412-393-enforce-the-no-exec-boundary-for-strict-hotspot-ep.md -->
