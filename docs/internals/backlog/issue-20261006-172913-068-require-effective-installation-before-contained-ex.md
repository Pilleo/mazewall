---
title: "Require effective installation before contained executor target entry"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationReceipt.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/Platform.kt"
  - "enforcer/src/test"
  - "enforcer/README.md"
  - "docs/internals/designs/api/enforcer-public-api.md"
target_symbols: 
  - "ContainedExecutorWrapper"
open_questions: false
---

# 🟡 [Severity: HIGH]: Require effective installation before contained executor target entry

**Context:**
C3/A06: explicitly configured bypass currently permits ordinary contained executor tasks to run after an installed=false receipt. Required filesystem evidence must also be checked before target entry.

**Needed:**
1. Reproduce target entry under WARN/SILENT bypass and incomplete Landlock evidence, require installation, and preserve receipt registration closure.

## Evidence — required Seccomp/Landlock task-entry gate

- Base revision `a63c2d26`; revised integration task C3, reviewed finding A06. Default fallback remains FAIL; this corrects explicitly configured bypass escaping through an ordinary contained executor.
- `RequiredTaskEntryTest` covers FAIL/WARN_AND_BYPASS/SILENT_BYPASS across unavailable Seccomp, unavailable Landlock, native Seccomp EPERM, and applied Landlock followed by Seccomp EPERM. Both prctl (thread installation) and raw-syscall routes are injected accurately. Twelve cases pass, with no target entry after failure; partial Landlock remains published on the failed worker.
- Eight WARN/SILENT cases failed before the guard; all four default-FAIL cases already passed. The corrected native-denial fixture was independently rerun with the guard temporarily removed and reproduced the same eight failures, then the guard was restored and full checks passed.
- `RequiredInstallationReceiptTest` originally exposed installed Seccomp being accepted without requested filesystem enforcement. Three receipt cases now pass: complete Seccomp+Landlock, missing required Landlock rejection, and failed Seccomp despite applied Landlock rejection.
- The wrapper stores its receipt before calling `requireInstalled()` and keeps existing `finally` closure. A rejection cannot lose supervisor-registration ownership. The receipt's failure wording preserves the possibility of already-applied irreversible restrictions.
- Two older "successful install" fixtures actually bypassed post-install verification because their native mock reported Seccomp mode zero. They now report the verified filter mode and assert positive retained worker depth under FAIL. The pre-apply failure fixture now injects the actual prctl installation call and inspects the worker's state rather than an unrelated caller state.
- Full enforcer/profiler host suites and ABI checks passed. `./gradlew build integrationTest integrationTestFreshJvm` passed: 272 tasks, 76 executed, 196 up-to-date. Full caller-mapped Docker gate passed in 1m2s.
- An independent procfs owner-death test race observed during the initial full suite is recorded in `issue-20261006-174206-021-make-socket-helper-owner-death-procfs-observation-.md`; its focused rerun and subsequent full gates passed. No helper lifecycle fix is claimed here.
- This is the Seccomp/Landlock target-entry slice. Captured installation/runtime/capability/fallback configuration, complete feature outcomes including CET, and owned worker retirement remain unfinished C3/C7 work. This issue stays open for repository review.

---
<!-- id: issue-20261006-172913-068-require-effective-installation-before-contained-ex  file: issue-20261006-172913-068-require-effective-installation-before-contained-ex.md -->
