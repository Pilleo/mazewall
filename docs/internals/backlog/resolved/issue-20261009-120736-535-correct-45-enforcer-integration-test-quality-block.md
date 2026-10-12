---
title: "Correct 45 enforcer integration-test quality blockers with causal failure evidence"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/landlock/LandlockTest.kt"
target_symbols:
  - "LandlockTest"
open_questions: false
---

# ✅ [RESOLVED]: Correct 45 enforcer integration-test quality blockers with causal failure evidence

**Context:**
At exact d0ce1d9f03131906cd3f8cb42a9b066c1c8e1200, retained maze-scanner-13 quality gate reports 45 hard errors in 15 other integration source files; PrctlBypassReproductionTest has zero. Explicit COORDINATOR4 continuation authorizes only evidence-listed test cause corrections, with no waivers or native policy/profile weakening. Historical full native build remains FAIL.

**Needed:**
1. Classify every retained file/rule/line against source contracts; use one fresh AO quality writer to retain actual exception evidence and fail-closed semantics, prove any semantic defect with meaningful existing-test RED/GREEN controls, pass focused quality plus exact integration Detekt and ratchet, then scoped private hook/commit and STOP for independent review.

## Authorized scope and immutable evidence

### Explicit COORDINATOR51 acquisition-authority scope correction

The outer coordinator's continuation explicitly admits one additional production
path, for **16 owned source paths total**: the original 15 below plus
`enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperProcess.kt`.
The only admitted production symbol is the internal read-only
`SocketHelperProcess.processHandle`, backed by the exact acquired private
`Process.toHandle()`. The caller retains that handle; its PID is derived only for
comparison. No `ProcessHandle.of`, observed child-set difference, private reflection,
or invented identity confers acquisition authority. Existing helper `close()` keeps
physical-retirement responsibility. This narrow internal seam does not change the
public API, protocol, policy, native layout, dependencies, or containment behavior.
All other production edits remain outside scope.

Sole source owner remains maze-scanner-46 at base
`e90b27c01a2697e4ace8f43369bbe4e955786b13`; coordinator51 added only this getter
while that owner was physically exited, before resuming it. F04/F05/F06/F08/F09 and
the five accepted groups remain frozen. F10/F11 caller acquisition rollback requires
meaningful same-boundary failure/physical-retirement controls, not a mirrored getter
test. Local creator47 configuration is backed up and narrowly extended with this
main path and its own worktree-local `opencode.json`; shared defaults remain read-only.
This scope amendment does not authorize unowned targets or writer access to root.
Operator index rows/counts and the separate prctl WIP are preserved. Coverage .82,
frozen inventories/exclusions, historical FAIL, and independent acceptance gates
remain mandatory. Actual preflight/evidence and handoff live in the ignored
`fresh-coordinator-51-checkpoint.md` and coordinator51 receipts.

Explicit operator continuation authorizes test-only cause corrections in these exact
15 files at base `d0ce1d9f03131906cd3f8cb42a9b066c1c8e1200`.
Paths below are relative to `enforcer/src/integrationTest/kotlin/io/mazewall/`:

| File | Findings | Contract/correction |
|---|---:|---|
| DeploymentOriginNativeTest.kt | 1 | Genuine JUnit TempDir injection, no invented path |
| PreparedOperationNativeTest.kt | 1 | Required close failure snapshot and fatal cause identity |
| enforcer/ContainedExecutorsTest.kt | 4 | Actual install Result classification, typed feature failure, required message |
| enforcer/ShutdownRaceConditionTest.kt | 4 | Expected interruption only; preserve fatal/unexpected worker failure and physical retirement |
| enforcer/VirtualThreadGuardrailTest.kt | 4 | Actual typed virtual-thread guard causes and required messages |
| enforcer/supervisor/SupervisorOpenHelperIntegrationTest.kt | 1 | Genuine JUnit TempDir injection |
| enforcer/supervisor/TraceeAuthorityIntegrationTest.kt | 1 | Genuine JUnit TempDir injection |
| landlock/LandlockTest.kt | 10 | Actual Future denial cause, required ABI message, rename denial cannot catch its own success failure |
| landlock/LandlockTsyncIntegrationTest.kt | 1 | Actual denial evidence vs unrelated sibling failure |
| seccomp/BpfHardeningTest.kt | 2 | Consumed physical-worker failure boundary; retain strict executable denial |
| seccomp/DedicatedProcessBaselineTest.kt | 1 | Physical sibling Result transport; typed denial remains |
| seccomp/MmapProtectionTest.kt | 2 | Consume actual warmup result; never discard Java/FFM/fatal failure |
| seccomp/ProcessContainmentTest.kt | 7 | Actual containment evidence, child failure propagation, required depth/feature contracts |
| seccomp/StackingIntegrationTest.kt | 2 | Actual typed depth-limit failure; unrelated/fatal failure propagated |
| seccomp/SupervisorProxyIntegrationTest.kt | 4 | Actual denial and fast-path evidence; diagnostic cleanup fatal-first merge |

Rules: LateinitUsage3, UnsafeCallOnNullableType12, TooGenericExceptionCaught19,
SwallowedException8, InstanceOfCheckForException2, PrintStackTrace1 =45.
Nested nullable chains are reported twice at VirtualThreadGuardrailTest36/89;
the original45 findings are retained without deduplication.

Durable coordinator artifacts under `.superpowers/sdd/2026-10-09-opencode-v1-zed-continuation/`:
`coordinator3-quality-hard-errors.json`, original integration Detekt XML,
`coordinator4-quality-classification.json`, `coordinator4-quality-scope.json`, and
`coordinator4-quality-brief.md`. Source inspection provenance: run-0d8b8d22740e;
new issue scaffold: run-2d038f80c6c5. `CleanupFailures.merge` retains identity and
promotes Error; `awaitOrFail` throws InterruptedException and timeout AssertionError.

No suppression/exclusion/inventory/threshold/name-regex waiver; no assertion/skip/
native pressure/timeout weakening; no production/native layout/BPF/policy/API/
dependency change. No strict executable-denial profile switch. Additional regression
file requires declared reason and coordinator review before admission. Actual semantic
defects require meaningful RED/GREEN existing-test controls, not mirrored tests.

Separate prctl WIP remains untouched in retired maze-scanner-13, SHA256
`2f701511d14d026805f5f068bf6ab6fbda0baa6def4e67342f3a37b2925116e7`.
Historical full native build remains FAIL. Quality writer stops after focused causal
checks, exact integration Detekt plus ratchet, mandatory private hook and scoped
commit; full build only after separate prctl correction is present. Fresh independent
review and serial integration are pending; this issue stays open until evidence.


## Closure evidence — Task 9 controller draft

**Proposed resolved, 2026-10-11. Tested integrated producer:
`dc6f7bf44b68f494a4cb609702dc97c72fb892c4`.** All original 45 sites in the declared
15 fixtures and the explicitly admitted acquired-`Process.toHandle()` getter
were handled through causal fixture/owner corrections, not configuration waivers.
The subsequent required failure-evidence/Proxy/mmap fixes were independently
reviewed and integrated within the accepted 32-path checkpoint. The final source
checks show **four current integration Detekt reports hard0**, **exact added
warnings0**, production ratchet PASS (**889 existing warnings**, not zero total
warnings). Other unchanged analyses reused their owned reports; this is not four
fresh static executions.

Final Proxy **30 PASS** includes the same-owner readiness-reserve control; actual
RED lost its reserve, GREEN retained **5,465,079,023ns**, real root exit143/reap and
stdout end. Permission/mapping boundaries require actual primary evidence rather
than unrelated/suppressed cleanup. Current P's combined gate passes **627 suites /
3,749 cases, 0 failures/errors**, all 20 Tests fresh, with every skip separately
classified. Unit/combined coverage floors remain unchanged and pass. This closes
the original fixture-quality obligation without claiming a whole-enforcer audit,
zero existing warnings, forced-KILL execution or universal descendant discovery.

Receipts/reviews: `task-3-fix-review.md`, accepted `task-3-fix2-review.md`,
`verification/task-3-fix2-final-static-2.*`, `.changed-file-signatures.json`,
`verification/task-3-fix2-final-{red,proxy-2}.*`, `task-4-root-review.md`,
`task-5-6-review.md`, `verification/combined.{reports,suite-acceptance,coverage-proof}.json`.
The fixed-1.5s buildSrc delayed-event Minor and disclosed static observation-gap/
process proof limits remain in the final acceptance draft; closing this issue
does not resolve or waive them.

---
<!-- id: issue-20261009-120736-535-correct-45-enforcer-integration-test-quality-block  file: issue-20261009-120736-535-correct-45-enforcer-integration-test-quality-block.md -->
