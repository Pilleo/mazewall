---
title: "Test InstallationReceipt seccomp×Landlock matrix including UNCHANGED"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260821-113003"
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/InstallationReceiptMatrixTest.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Test InstallationReceipt seccomp×Landlock matrix including UNCHANGED

**Context:** Partial apply is real. Linux has no transaction that applies Landlock and seccomp together or not at all. `InstallationReceipt.installed` and `landlockApplied` are independent. `FilterInstallationFailureTest` covers revert-vs-keep on throw. `InstallationReceiptTest` constructs receipts but does not drive `ContainedExecutors`. Production UNCHANGED success path is `issue-20260821-113003-report-already-active-landlock` (do not re-implement that fix here). This issue **only adds tests** in a new file.

**Needed:**
1. Create `InstallationReceiptMatrixTest.kt`. Do not edit `ContainedExecutors.kt` or `InstallationReceipt.kt` in this issue.
2. Host unit tests with `MockNativeEngine` + `Platform.setProvider` + `@AfterEach` resets (`LinuxNative.resetToDefault()`, `Platform.resetToDefault()`, registry empty).
3. Default fallback `FAIL` unless a case sets `WARN_AND_BYPASS` via `System.setProperty("io.mazewall.fallback", ...)` like `ContainedExecutorsCoverageTest`. Clear the property in `@AfterEach`.

**New cases:**

| # | Setup | Expect |
|---|---|---|
| A | Policy `block(EXECVE)` only; seccomp mock success | `installed=true`, `landlockApplied=false` |
| B | Policy `allowFsRead("/tmp")`; Landlock mock success; seccomp mock success | `installed=true`, `landlockApplied=true` |
| C | Preload `threadState.withLandlockPolicy(same paths)`; install same Landlock+seccomp policy; planner may skip Landlock apply (UNCHANGED) | `landlockApplied=true` (this is 113003). Assert `installed` explicitly from observed behavior, do not guess |
| D | Policy with Landlock paths; Landlock apply succeeds; seccomp `Error(22)`; fallback FAIL | throws; `threadState.landlockPolicy != null` |
| E | Same as D but `WARN_AND_BYPASS` | `installed=false`, `landlockApplied=true` |
| F | Policy with **no** Landlock; seccomp fails; `WARN_AND_BYPASS` | `installed=false`, `landlockApplied=false` |
| G | Policy with no Landlock and no extra blocks vs empty state | `landlockApplied=false` |

**Do not:**
- Re-apply Landlock on every install to flip the flag.
- Set `landlockApplied=true` merely because `enforceLandlock` is true when no ruleset exists.
- Edit `ContainedExecutors.kt` here (that is 113003). Cell C may fail until 113003 lands — that is why this issue depends on it.

**Verify:** `./gradlew :enforcer:test --tests io.mazewall.enforcer.InstallationReceiptMatrixTest`
