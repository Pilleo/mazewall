---
title: "Repair seven unchanged profiler integration-test quality blockers in three fixtures"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProfilerStressTest.kt
  - profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt
  - profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotSupportedWorkloadMain.kt
target_symbols: []
open_questions: false
---

# ✅ [RESOLVED]: Repair seven unchanged profiler integration-test quality blockers in three fixtures

**Context:**
The unchanged all-module `detektIntegrationTest :enforcer:compileKotlin detektWarningRatchet`
verification ended exit1; ratchet was not reached. These seven hard errors are outside
the current quality21 source scope and remain unwaived. The three files match exact
e90b27c01a2697e4ace8f43369bbe4e955786b13 bytes. This is a separate test-only assignment,
admissible only after the active quality21 source owner and its commands physically end.

Pinned profiler XML SHA256: `6dc48fd30ab459da29e5bbd5d9d877e30367cb6682685395b56e33811cef0254`.
Exact messages, positions and source hashes are retained in coordinator75-profiler-seven-exact.json.

| File | SHA256 | Exact rules/lines | Verified local cause |
|---|---|---|---|
| ProfilerStressTest.kt | `a4481aa8f3f43d45030004ad6aa9e2033d1b54fbdea6f702789d935de99969d8` | TooGenericExceptionCaught:116 | Future.get Exception catch wraps the actual task failure; retain primary/fatal/interrupt semantics. |
| TierERootfulContainerEndpointIntegrationTest.kt | `b9f1ffac62b71845248e47950e23b5ee0aae7f116be281e802d2ed4f6634efc3` | UnsafeCallOnNullableType:448,583,633; ReturnFromFinally:361; TooGenericExceptionCaught:576 | Nullable failure-message assertions; actual Docker cleanup expression in finally; creation-gate releaser Throwable catch. Preserve real Docker retirement and cleanup failures. |
| hotspot/HotSpotSupportedWorkloadMain.kt | `19f5a0088fde762280aeb3196ba8b11f8d7174d08ecad2ccf07151821fac0e94` | TooGenericExceptionCaught:106 | Actual pending-ownership retryCleanup Exception catch; retain physical ownership and primary/fatal/interrupt classification. |

All seven diagnostic sites are integrationTest sources. This establishes test-source
scope, not permission to change profiler production/protocol/native/FFM/policy behavior.
If any such behavior becomes implicated, declare the precise scope, design and meaningful
tests before editing it. Do not expand to a whole-profiler audit.

**Needed:**
1. Admit one separate writer only after quality21 source owner and commands physically end;
   verify exact base, these three hashes, private permissions and bounded issue scope.
2. Repair the actual test causes without suppression, config/threshold/inventory changes,
   weakened assertions, budgets, pressure, profiles or swallowed permission/cleanup failures.
   Prove actual failing boundaries before semantic fixes; equivalent controls require strong
   contracts for primary/fatal/interrupt and physical child retirement.
3. Run durable locked scoped compile/static, compare exact source+rule+message diagnostics
   against the frozen baseline; run only meaningfully affected controls/native paths.
4. Require all-module hard0 and added-warning0 with the unchanged ratchet before claiming
   global quality GREEN. Current exit1 remains historical/unwaived until this evidence exists.


## Closure evidence — Task 9 controller draft

**Proposed resolved, 2026-10-11. Tested integrated producer:
`dc6f7bf44b68f494a4cb609702dc97c72fb892c4`.** The three declared test fixtures retain
actual Future/cleanup/creation-gate failure evidence, fatal/interrupt identity,
real Docker resource retirement and existing native profiles/budgets. The accepted
source/static checkpoint proves **hard0 / exact added-warning0 / unchanged ratchet**;
the historical seven-site exit1 is not rewritten.

Current P's exact combined invocation executes the owning profiler tests and
fresh-JVM selection. The real rootful Docker endpoint suite is **22 PASS / 0 skips**,
including cancellation/cleanup/identity/log-recovery controls. Correct profiler
stress routing uses the class's **direct `@NeedsFreshJvm`**, not an inherited base
annotation. Installed Tier E/HotSpot asset/native opt-ins and CET remain their own
prerequisites; endpoint success does not close them. Receipts: accepted
`task-3-fix2-review.md`, `task-4-root-review.md`, `task-5-6-review.md`,
`verification/task-3-fix2-final-static-2.*`, `verification/combined.suite-acceptance.json`
and exact frozen rootful/profiler XML. No production profiler audit or waiver follows.

---
<!-- id: issue-20261010-025904-711-repair-seven-unchanged-profiler-integration-test-q  file: issue-20261010-025904-711-repair-seven-unchanged-profiler-integration-test-q.md -->
