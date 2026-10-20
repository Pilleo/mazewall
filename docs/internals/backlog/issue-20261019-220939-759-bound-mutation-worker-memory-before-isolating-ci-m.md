---
title: "Bound mutation worker memory before isolating CI mutation validation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: []
target_files:
  - ".github/workflows/ci.yml"
  - "enforcer/build.gradle.kts"
  - "platform/build.gradle.kts"
   - "profiler/build.gradle.kts"
   - "gradle/pitest-limits.gradle"
   - "gradle.properties"
   - "scripts/ci_resource_probe.py"
   - "scripts/tests/test_ci_resource_probe.py"
   - "buildSrc/build.gradle.kts"
   - "buildSrc/src/test/java/io/mazewall/build/PitestLimitsFunctionalTest.java"
   - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
   - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
   - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
   - "CONTRIBUTING.md"
   - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
needs_kernel: false
core_lock: true
effort: "medium"
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Bound mutation worker memory before isolating CI mutation validation

**Context:**
Planning-only operator request to improve CI without repeating memory exhaustion. Current CI passes -Pmazewall.pitest.threads=2, but enforcer and platform read System.getProperty(pitest.threads) and default4; profiler alone honors the project property. Two Gradle task workers may overlap module PIT processes. Normalize settings and bound per-runner concurrency before moving mutation work to a separate required runner. Preserve all mutation targets, thresholds, native freshness and exact-head publication gating. No implementation or merge authorized by this plan.

**Needed:**
1. Write and review memory-bounded CI specification and execution plan with precise tests, budgets and rollout gates.
2. Implement bounded settings, collect real memory evidence and only then isolate the serial three-module mutation lane on a separate runner.

## Planning checkpoint

Proposed spec: [memory-bounded mutation CI](../../superpowers/specs/2026-10-20-memory-bounded-ci-design.md).
Implementation plan: [memory-bounded CI](../../superpowers/plans/2026-10-20-memory-bounded-ci.md).
The operator approved both and explicitly selected subagent implementation on
20October2026, superseding the earlier inline method. Use task-scoped reviews
and a final whole-change review; approval does not authorize a merge.

The initial scope is mutation configuration, memory qualification and a separate
runner with a fail-closed required-check join. Build-tool lifecycle decoupling,
kernel sharding and additional cache archives remain separate follow-ups. The
new runner requires at least2GiB measured headroom, unchanged mutation floors and
zero OOM-kill increase. Heap limits are proposed caps, not an unmeasured guarantee.

## Implementation evidence (in progress)

- Task1 `d7cdaf63`: one shared Groovy Gradle limits script, real project/system
  override tests, configuration-cache changes and unchanged target/floor checks.
  Focused tests passed; task-scoped spec and quality review passed. The redundant
  root property was removed to preserve legacy `-Dpitest.threads` behavior.
- Task2 initial `1821ecf4`:32 unit tests passed; a real serial three-module mutation
  run passed all unchanged thresholds, with15,308,599,296bytes minimum sampled
  local headroom and zero new OOM kills. Later parser/cancellation corrections
  had unit/live-smoke/replay coverage rather than a repeat mutation workload.
- Task2 review found a late-cancellation finalization race. Fix `0cae7925` adds
  both dedicated RED/GREEN regressions; all34 unit tests and a final-code live
  smoke pass. Scoped re-review confirms the finding addressed, spec PASS and
  quality APPROVE. This checkpoint is not hosted final-code acceptance.
- Task3 `dbc4d084` plus minor-name alignment `27016eb1`: isolated host mutation
  runner, stable fail-closed required-check join and cache-output forwarding.
  Focused policy/root-policy/TestKit tests, actual YAML/base comparison and25
  real-shell prerequisite combinations pass. Task review found only the corrected
  display-name mismatch; whole-branch review will verify the final range.
- Complete local build on `27016eb1` passes:286 tasks in22m21s,212 executed,
  12 from cache and62 up-to-date. The final Python34-test rerun, backlog validation
  and whitespace checks pass. This is local verification, not a CI speed result.
- Final whole-branch review and cold/warm hosted memory qualification remain
  pending. PR545 is still the unmerged prerequisite; no implicit merge or
  branch-protection change is authorized.

---
<!-- id: issue-20261019-220939-759-bound-mutation-worker-memory-before-isolating-ci-m  file: issue-20261019-220939-759-bound-mutation-worker-memory-before-isolating-ci-m.md -->
