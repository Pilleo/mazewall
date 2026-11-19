---
title: "Restore intended enforcer and profiler mutation target inventories"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "build"
target_modules: [":enforcer", ":profiler"]
target_files:
  - "enforcer/build.gradle.kts"
target_symbols:
  - "pitest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Restore intended enforcer and profiler mutation target inventories

**Context:**
Repository-wide review, 2026-11-04: [TEST-01](../reviews/2026-11-04-repository-review-findings.md#test-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Consolidated priority:** P2/medium. BUILD-03 is an alias of TEST-01, not a second task. This is a check-selection/assurance gap: omitted targets invalidate the claimed mutation denominator, but do not independently prove a runtime bypass. Repair alongside the concrete product defects without weakening thresholds.

**Category:** confirmed source defect in gate selection. **Confidence:** high. **Release boundary:** core enforcer and profiler assurance; not evidence of a runtime bypass. **Status:** NEW cross-module extension of an already demonstrated selector-migration failure class.

**Trigger/evidence:** Run the declared PIT tasks on current packages. Several explicit patterns match no intended class:

| Declaration | Current implementation identity |
|---|---|
| `enforcer/build.gradle.kts:239` `io.mazewall.Policy*` | `io.mazewall.enforcer.api.Policy`, `PolicyDefinition`, related policy classes (`.../api/Policy.kt:1,60`, `PolicyDefinition.kt:1,23,76–150`) |
| `enforcer/build.gradle.kts:240` `io.mazewall.BpfFilter*` | `io.mazewall.enforcer.internal.seccomp.BpfFilter` (`.../internal/seccomp/BpfFilter.kt:1,30`) |
| `enforcer/build.gradle.kts:241` `io.mazewall.SbobParser*` | `io.mazewall.enforcer.api.SbobParser` (`.../api/SbobParser.kt:1,52`) |
| `enforcer/build.gradle.kts:243` `io.mazewall.enforcer.PolicyCombining*` | No current declaration; composition now resides in `Policy`/`PolicyDefinition` |
| `profiler/build.gradle.kts:853–854` `io.mazewall.profiler.engine.{SyscallPathResolver,ProfilerSessionMachine}*` | `io.mazewall.profiler.internal.engine...` (both owning source files, line 1) |
| `profiler/build.gradle.kts:855–856` `io.mazewall.profiler.{ProfilingCoverage,BillOfBehavior}*` | `io.mazewall.profiler.api...` (`api/ProfilingCoverage.kt:1`, `api/BillOfBehavior.kt:3`) |

The corresponding test selectors also drift: enforcer `PolicyTest`/`SbobParserTest` at `enforcer/build.gradle.kts:264,266`; profiler path/session/BoB/API test selectors at `profiler/build.gradle.kts:873–877`. Their real package declarations are under `enforcer.api`, `profiler.internal.engine` and `profiler.api`. `ProfilingCoverageTest` itself still has its old test package, so do not mechanically relocate every test selector.

**Concrete escape:** Change the BPF compiler's decision behavior or profiler coverage/completeness behavior: the mutation gate never generates mutants for these omitted classes. Other surviving targets allow PIT to run on a nonempty, smaller denominator; its configured percentages cannot establish the omitted slice. The script `15-assurance-static-check.py` confirmed four empty enforcer production patterns and four empty profiler production patterns against existing compiled outputs, corroborated by current source declarations. No actual PIT run was performed here.

**Smallest complete fix:** Correct intended production and test identities, including the real composition owners, without reducing thresholds or silently dropping original scope. Add a fail-closed inventory assertion for **each declared selector**, not merely “some target matched.”

**Regression/qualification:** The owning lead should run actual enforcer/platform/profiler PIT and inspect mutated source/class inventories. Deliberately move one declared identity in a disposable fixture and require inventory failure; confirm meaningful mutants in the previously omitted compiler/policy/coverage slices are exercised. Thresholds may expose real assertion debt once the denominator is restored; do not preemptively weaken them.

**Mapping/dependencies:** Adjacent resolved `docs/internals/backlog/resolved/issue-20261015-162735-795-align-mutation-selectors-with-migrated-platform-te.md` fixed this class of problem for platform only. Platform's five current production/test selectors match. No enforcer/profiler equivalent found in active/resolved searches. This repair precedes interpreting the strategy's mutation floors (`testing-strategy.md:192–202`) or adding more percentage-only gates.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235256-471-restore-intended-enforcer-and-profiler-mutation-ta  file: issue-20261103-235256-471-restore-intended-enforcer-and-profiler-mutation-ta.md -->
