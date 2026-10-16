---
title: "Recover broker cleanup regression controls from historical worktrees"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
target_symbols: []
open_questions: false
---

# Resolved: Recover broker cleanup regression controls from historical worktrees

**Context:**
Authorized review of 36 historical dirty worktrees found an unfinished broker test extension not covered by the current single-worker startup control: ordinary/fatal launch versus ordinary/fatal pre-native listener-close failures, exact retained-owner retry, and physical granted-FD retirement after interrupted admission. Other snapshots are integrated or superseded.

**Needed:**
1. Adapt only the useful broker assertions to current launch APIs, run the owning integration suite and full gate, commit to local fast-master, then remove the reviewed historical worktrees.

## Authorized scope and review

The operator explicitly authorized reviewing, integrating useful differences, and
removing the 36 previously inventoried dirty historical worktrees. The comparison
uses remote master `ec491374fc6a076444209bb982dbc14ef3d3522e` and local fast-master
`ec1a48de6118eb6a4ac597ce5c2d0e1d5e9dfdee`. No new worktree is required.

- The 32 old scanner worktrees contain overlapping quality-repair attempts. The
  inventory deduplicates to 39 non-build paths and 76 content variants, including
  two empty JVM attach markers. Existing integration acceptance records and current
  source retain the useful prctl, fatal/interruption, cleanup, and owned-runtime
  changes. Old borrowed-executor tests and retired shell-controller calls must not
  replace their current equivalents. The sole non-patch-equivalent historical
  commit, `e90b27c0`, is the pre-correction test-quality snapshot already superseded
  by accepted integration repairs.
- The final scanner-60 broker extension adds useful direct single-worker launch
  rollback controls. Recovered assertions exercise ordinary/fatal startup against
  successful/ordinary/fatal listener cleanup, exact failure identity and suppression,
  retention/retry of the same acquired owner, and idempotent physical retirement.
  The existing interrupted-admission case also verifies its actual granted FD
  before close and retirement afterward, allowing integer reuse for other resources.
  Current `JvmLaunchPreparation` and `PortalLaunchProfile` APIs remain intact.
- The final-acceptance worktree's four untracked candidate-dc6 locks are already
  byte-identical to master. The completion worktree's five scratch candidate-c049
  locks predate the accepted candidate617 consumer qualification and add no current
  producer/consumer contract. The inline worktree contains an old aggregate-task
  issue and index; current build-owned aggregate selection and regression fixtures
  already repair that defect. Its old index must not replace current backlog state.
- The native-profiler worktree contains generated CMake/object/class/test outputs
  and generated SBoB results. Worker-local `ao-default` configurations, attach
  markers, generated vulnerable-fixture shared objects and build caches are not
  missing application changes. No active process had a CWD in the 36 targets at
  review time.

## Verification

- `:portals:runtime:integrationTest --tests io.mazewall.portal.ProcessBrokerIntegrationTest`
  passed on OpenJDK25 in 26s (`run-3fa9603a5b33`): **24 tests, zero failures,
  errors or skips**. The six startup combinations are iterations of one existing
  test, not six additional JUnit cases.
- The first full build rejected the historical compact nested-loop spelling with
  `standard:multiline-loop` (`run-f2e981e221d7`). Explicit nested braces and scoped
  indentation fixed that formatting defect. The owning lint plus broker suite then
  passed in 36s (`run-afda2980017c`), followed by **`./gradlew build` PASS in 5m24s**
  (`run-7e92a736728c`). No lint/test/native gate was bypassed.
- Before removal, all 36 heads, status inventories and changed-file SHA-256 values
  still matched the reviewed snapshots (`run-165b8beb8366`). The repository-root
  historical core-publication custody contains 40 files and candidate617 custody
  contains 275 files, outside the target worktrees; those retained artifacts are
  unaffected by worktree removal.

## Completion

- Recovered source committed to local fast-master as
  `bbe0c1181095828548086be620edad447e4d1fd9` with the normal mandatory hook
  (`run-c396d4cf2def`).
- All 36 explicitly reviewed worktrees were removed with `git worktree remove`
  only after their unchanged contents, heads, and absence of active process CWDs
  were rechecked. Both directories and Git registrations are absent
  (`run-4e3081c96883`). No new worktree was created.

---
<!-- id: issue-20261016-082036-036-recover-broker-cleanup-regression-controls-from-hi  file: issue-20261016-082036-036-recover-broker-cleanup-regression-controls-from-hi.md -->
