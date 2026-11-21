---
title: "Fail differential seccomp checks when simulator has no verdict"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SeccompDifferentialVerdictTest.kt"
target_symbols:
  - "assertKernelVerdictMatchesSimulator"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Fail differential seccomp checks when simulator has no verdict

**Context:**
The differential helper classifies a null simulator result as neither blocked nor allowed, so it silently omits the probe from both kernel assertion loops. A missing verdict must invalidate test evidence instead of removing a test case.

Source evidence and the proposed oracle contract are recorded in the
[upstream research](../research/bpf-upstream-validation.md#2-differential-verification-silently-drops-a-missing-verdict).
No corrective code or new regression has been run for this issue.

**Needed:**
1. Add a regression with a no-RET program and require an assertion failure naming the probe; preserve the explicit benign-probe restrictions and existing successful allow/deny cases.
2. Include probe input and disassembly in the failure; verify the focused
   differential suite and full build before closing the issue.

---
## Repository-review revalidation — 2026-11-04

[BPF-09/TEST-06](../reviews/2026-11-04-repository-review-findings.md#bpf-09) confirms the
same missing-verdict partition, with a source-equivalent no-RET host witness. The original
integration helper/kernel were not executed by that witness. Require immediate attributable
failure before partitioning, plus valid benign allow/deny controls. Separate TEST-02 owns the
surviving-child KILL_THREAD oracle; neither correction substitutes for the other.

## Progress Evidence (2026-11-21)

- Updated [`SeccompDifferentialVerdictTest.assertKernelVerdictMatchesSimulator`](../../../../enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SeccompDifferentialVerdictTest.kt) so any `null` verdict from `BpfSimulator.simulate(program, nr, arch)` throws an immediate `AssertionError` reporting `nr`, `arch`, and `BpfProgram.unverified(program).disassemble()` before invoking the kernel oracle.
- Added regression `missing simulator verdict fails differential check with probe and disassembly` covering a no-RET BPF program (`ld [0]`), verifying the kernel oracle lambda is not invoked, along with benign single-RET `ACT_ALLOW` and `ACT_ERRNO(EPERM)` controls.
- Verified with `./gradlew :enforcer:ktlintCheck :enforcer:detektMain :detektWarningRatchet --no-daemon --console=plain` (`BUILD SUCCESSFUL`) and `./gradlew :enforcer:integrationTestFreshJvm --tests io.mazewall.seccomp.SeccompDifferentialVerdictTest --rerun-tasks --no-daemon --console=plain` (`BUILD SUCCESSFUL`; `8` tests, `0` skipped, `0` failures, `0` errors).

---
<!-- id: issue-20261103-123359-606-fail-differential-seccomp-checks-when-simulator-ha  file: issue-20261103-123359-606-fail-differential-seccomp-checks-when-simulator-ha.md -->
