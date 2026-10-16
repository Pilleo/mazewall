---
title: "Prove actual profile-backed post-install HotSpot compilation and coverage custody"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/PortalWorkerPolicyNativeTest.kt"
target_symbols:
  - "PortalWorkerPolicyNativeTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prove actual profile-backed post-install HotSpot compilation and coverage custody

**Context:**
**Resolution — 2026-10-12:** Needed step1 fulfilled: real USER_NOTIF coverage/drain → selected HOTSPOT_JIT conversion → required Seccomp/Landlock install → completed post-install C1 tier2 target compilation/lazy result → physical end. E=`/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`: `task-5a/task-5a-review.md` / report (62 selected PASS); exact two-owner bounded observation fix and fresh owning P617 case accepted in `task-6d2/continuation-20261012-a1/task-6d2-checkpoint-review.md`. No all-future-path, CET or Native Image hardware claim. [Acceptance](../../reviews/2026-10-12-integration-contract-completion.md). Prior open prose is historical.

C5 Task5a lacks a real public profiling result to selected-runtime restrictive installation and post-install target compilation proof; synthetic completeness is not native evidence.

**Needed:**
1. Execute a supported public profiling session in a disposable JVM and retain actual coverage, exact policy installation, ordered target compilation, workload result and physical cleanup.

**Implementation / review evidence:**
- `PortalWorkerPolicyNativeTest` uses the public USER_NOTIF facade and retains actual
  scope/coverage, observations, ended worker and observed collector identities/stacks.
  Selects HOTSPOT_JIT before intersection; serializes the exact converted components.
- Profiling JVM and its shared daemon/helpers/pumps end before launching enforcement.
  The second disposable JVM installs the unchanged retained components on all threads
  (required ABI8+ Landlock TSYNC and Seccomp), then loads a new target/resource and
  proves actual target compilation after the receipt using ordered JDK output.
  The retained JDK compilation XML additionally requires a completed positive-sized
  C1/C2 target nmethod; a queued compilation alone cannot satisfy the proof.
- Native Image converted mmap/mprotect verdicts remain strict; contradictory HotSpot
  use rejects before installation. Legacy/unused/recorded-log refusals remain active.
- Corrects only the proven stale public incomplete-override advice and corresponding
  KDoc/design guidance. No completeness/backend/installer semantics change.
- A single-JVM experiment exposed why shared profiler infrastructure must retire
  before the final filesystem/no-process baseline: its shutdown endpoint cleanup
  is denied by that baseline. This fixture lifetime finding does not justify widening
  policy or redesigning the backend. Original failed/diagnostic receipts are retained.
- An actual zero-exit installation child exposed a fixture output-pump backlog:
  scanning kernel ancestry for every JDK compilation line delayed EOF. The fixture
  now observes ancestry at its lifecycle markers and daemon launch, retaining the
  same readiness/90-second child/5-second EOF/reap/join bounds. Output parsing also
  accepts an intact receipt marker interleaved with ordinary HotSpot output.
- Scoped report/review and actual receipts live at repository artifact directory
  `.superpowers/sdd/2026-10-11-integration-contract-completion/task-5a/`.
  Keep this issue open for the parent review/Task6 completion checkpoint.

---
<!-- id: issue-20261012-034048-911-prove-actual-profile-backed-post-install-hotspot-c  file: issue-20261012-034048-911-prove-actual-profile-backed-post-install-hotspot-c.md -->
