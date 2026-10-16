---
title: "Separate launch cancellation control from final retirement budget"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerLaunchProfileTest.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Separate launch cancellation control from final retirement budget

**Context:**
PR533 CI38877672965 fails selected launch cancellation and expiry cleanup at RequiredWorkerLaunchProfileTest.kt256 with IncompleteBrokerCleanupException. The fixture uses the same 200ms close budget both to force incomplete held-startup cleanup and to require all physical retirement afterward; its finally close can mask the earlier failure.

**Needed:**
1. Separate the deterministic held-launch cancellation control from bounded final physical retirement, preserve actual child and owner assertions, and verify the owning tests plus full and exact-head CI gates.

## Investigation and correction

- CI `38877672965`, exact PR533 head `5cad88ed`, failed the selected-launch test
  with `IncompleteBrokerCleanupException` from its final `broker.close()`.
  Downloaded XML confirms a 3.668-second case and an unadorned final-cleanup stack
  (`run-66fc554a619b`, `run-849e686372e8`). It does not establish that a live owner
  was leaked: the broker correctly refused to certify complete retirement.
- The same 200ms budget was used both to force incomplete cancellation while the
  real launcher was held and to demand complete physical retirement afterward.
  Final cleanup could also mask an earlier assertion or retirement failure.
- The cancellation control now waits until the broker enters Closing, interrupts
  an acquired platform-thread close caller while the actual launch remains held,
  and asserts incomplete cleanup, preserved interruption, retained acquisition and
  the live original child. It releases and physically joins that caller before
  requiring final retirement with an independent five-second budget. Startup
  expiry retains its real two-second deadline; no repeated-close retry loop is used.
- Cleanup uses the existing collector to attempt both startup completion and
  broker retirement, retaining primary evidence with fatal-first failure merging.
  Production admission, launch, containment and retirement code are unchanged.
- Focused owning lint and `RequiredWorkerLaunchProfileTest` passed in 21s
  (`run-b60de2804c9c`). **`./gradlew build` passed in 5m5s**
  (`run-056fdf4db361`); backlog validation and whitespace checks passed
  (`run-f59a758c5f6b`, `run-cd8068e9bac0`). The unchanged optional positive
  delegated-cgroup prerequisite is not claimed as a new native pass.
- Exact-head hosted CI remains the acceptance gate before the explicitly
  authorized PR merge. No failed check or incomplete retirement is waived.

---
<!-- id: issue-20261016-091758-463-separate-launch-cancellation-control-from-final-re  file: issue-20261016-091758-463-separate-launch-cancellation-control-from-final-re.md -->
