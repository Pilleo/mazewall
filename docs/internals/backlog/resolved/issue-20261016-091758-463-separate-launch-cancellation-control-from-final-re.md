---
title: "Separate launch cancellation control from final retirement budget"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerLaunchProfileTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/AcceptedSocketCancellationIntegrationTest.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
  - "docs/internals/designs/enforcer/process-portal-design.md"
target_symbols: ["ProcessBroker"]
open_questions: false
needs_kernel: true
core_lock: true
autonomy: "supervised"
has_side_effects: true
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

## Reopened: cancelled accept producer

Run `39814424290` at `ac2c987c` failed final close in 3.61 seconds even with the
separate five-second budget. Its primary and suppressed cleanup exceptions name
no failed resource. Portal code is unchanged at the merged `594fdaca` baseline.
A fresh focused run passes, so that pass is not treated as a correction.

A deterministic real-broker regression now holds the sole virtual carrier until
an expired startup cancels its queued accept and enters executor shutdown. The
producer never enters native accept; the real child, output pump, listener and
endpoint retire, but `broker.close()` still throws the same incomplete-cleanup
exception. A task cancelled before dispatch never runs the producer's `finally`,
leaving its registered handoff permanently incomplete.

The approved correction scope is to certify producer completion only after its
executor has actually terminated, then forget only a fully retired handoff. Keep
failed descriptor retirement registered, preserve failures and all existing
startup/close budgets, and add no close-retry loop or test-only production hook.

The real-broker regression fails before the correction with the same primary and
suppressed `IncompleteBrokerCleanupException`, after its real process/descriptor/
endpoint checks pass. It passes after executor-terminal handoff reconciliation.
Focused validation passes 148 unit tests and 32 executed integration tests; the
existing delegated-cgroup prerequisite case remains skipped, not claimed as proof.
Owning ktlint/static analysis and `./gradlew build` pass (8m14s). Exact-head hosted
CI and review/merge approval remain required before resolving this item.

## Resolution

[PR #555](https://github.com/Pilleo/mazewall/pull/555) merged as
`723e1b4e6b88424c835c669b814b40ed07dc9843`. Its
[default-branch run 40090736520](https://github.com/Pilleo/mazewall/actions/runs/40090736520)
passed all validation lanes and publication. Retrieved hosted XML confirms the new
cancelled-producer regression executed with zero skips/failures, the original
cancellation/expiry case passed, and all 148 Portal unit tests passed. The separate
delegated-cgroup prerequisite case remains skipped and is not claimed as proof.
Independent review found no blocking issues; deadline, interruption, descriptor
ownership and physical-retirement assertions remain intact.

---
<!-- id: issue-20261016-091758-463-separate-launch-cancellation-control-from-final-re  file: resolved/issue-20261016-091758-463-separate-launch-cancellation-control-from-final-re.md -->
