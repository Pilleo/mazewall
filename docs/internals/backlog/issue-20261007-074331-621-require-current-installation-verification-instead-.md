---
title: "Require current installation verification instead of program-global cached evidence"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/InstallSelfVerifier.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/InstallSelfVerifierTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/testing/GlobalTestState.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/FreshSelfVerificationNativeTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Require current installation verification instead of program-global cached evidence

**Context:**
A successful program-keyed verifier cache entry skips probes on a different native backend or worker and on a different merged filter state. Identical instructions are not proof of the current installation. Remove global verification evidence reuse and obsolete reset seams; preserve bounded safe probes.

**Needed:**
1. Reproduce divergent current backend after a successful identical-program verification and require fresh probes before accepting installation.

## Implementation and verification evidence

The program-global verification cache and obsolete reset seam are removed; callers are migrated without aliases. Every enabled verifier entry executes its bounded safe probe plan against the selected backend. Stacked verification requires merged filter state instead of silently skipping. Compilation caching and already-installed policy planning remain separate mechanisms.

Four regressions failed before implementation (`run-07803dc4b8f5`): repeated current verification was skipped; another backend's mismatch was concealed; a different merged tree was incorrectly attested; missing merged evidence was silently accepted. All four passed afterward (`run-c16a9fa8876c`). Host/profiler/ABI/quality and focused native checks passed (`run-e799e97d7248`). An isolated actual-kernel test verifies identical policies on two fresh workers and requires two successful verification outcomes, with zero skips/failures/errors.

`./gradlew build integrationTest integrationTestFreshJvm` passed in 6m53s: 272 tasks, 89 executed and 183 up-to-date (`run-08649965e567`). The full serial Docker gate initially encountered the already-recorded process-wide profiler intermittency (`run-64e6eb2197cd`, ACK errno 32); the unchanged full rerun passed in 65s (`run-21b6e1f2382c`). No profiler behavior/assertion/timeout/skip change is included. This issue remains open for the repository review path.

---
<!-- id: issue-20261007-074331-621-require-current-installation-verification-instead-  file: issue-20261007-074331-621-require-current-installation-verification-instead-.md -->
