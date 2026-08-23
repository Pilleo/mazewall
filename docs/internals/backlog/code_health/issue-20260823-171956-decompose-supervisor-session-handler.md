---
title: "Decompose SupervisorSessionHandler God File Along SupervisedKind Routes"
severity: "MEDIUM"
status: "open"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "large"
autonomy: "supervised"
open_questions: true
dependencies: []
---

# 🟠 [Severity: MEDIUM]: Decompose SupervisorSessionHandler God File

**Context:** `SupervisorSessionHandler.kt` is 1218 lines mixing at least five concerns: notification
reception/ACK plumbing, `SupervisedKind` routing, the JVM classpath fast-path bypass
(processNotification, lines ~200-260), Yama/procfs inspection helpers (`getPpid`, `getTgid`,
canonicalizeExecPath), and response marshalling. This is the highest-leverage refactor in the repo:
every supervisor change today lands in one file with high merge-collision probability, weak
testability (testing/issue-20260726-0135 tracks profiler-side testability), and cognitive overload
that invites exactly the class of subtle bugs caught recently (JA encoding, ACK-loop hazards).

**Needed:**
1. Split along existing seams into collaborators: `NotificationReader` (recv/EINTR loops),
   `RouteEvaluator` (SupervisionMachine + fast-path bypass), `ProcFsInspector` (pid/tid/path
   canonicalization), `ResponseWriter` (continue/error marshalling), keeping
   `SupervisorSessionHandler` as a thin orchestrator under ~300 lines.
2. Preserve the documented invariants verbatim in the new units: every path sends CONTINUE or
   KILL_THREAD; the 0xAC handshake ordering; listener-FD close on session failure.
3. Characterize first: add golden-notification integration coverage BEFORE moving code so the
   refactor is behavior-locked (reuse SeccompDifferentialVerdictTest patterns where applicable).
4. No functional changes in the same series as the mechanical split.

## ❓ Open Questions
1. Should the fast-path bypass policy (classpath allowlisting) move behind a strategy interface at
   the same time, or strictly later? Bundling risks scope creep; separating doubles review cost.
