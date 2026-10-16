---
title: "Capture installation fallback once and retain failed-stage evidence"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationReceipt.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationProgress.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock"
  - "enforcer/src/test"
  - "enforcer/api/enforcer.api"
  - "tests/consumers"
  - "enforcer/README.md"
  - "docs/internals/designs/api/enforcer-public-api.md"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: 
  - "ContainedExecutors"
open_questions: false
---

# 🟡 [Severity: HIGH]: Capture installation fallback once and retain failed-stage evidence

**Context:**
C3: fallback is reread across native stages and receipt construction, allowing configuration mutation mid-attempt to change failure handling. Bypass receipts discard original native failure and irreversible progress.

**Needed:**
1. Reproduce fallback mutation during native installation and Landlock probing; freeze attempt/session fallback and preserve receipt progress and original failure.

## Evidence (6 October 2026)

- Base revision `deef5f2a`; C3 captured-configuration/failure-evidence slice. Four runtime regressions were reproduced: FAIL changing to bypass during native denial, bypass changing to FAIL, successful receipt reporting a later configuration, and Landlock probing changing failure handling.
- Native fixtures intercept both the raw seccomp syscall and prctl installation route. Mandatory mode verification reports the valid filter mode unless verification failure is the explicit scenario; an unrelated mock-mode failure cannot satisfy the mutation regression.
- `InstallationAttempt` selects fallback once before probing/applying native stages. Platform handling, CET failure handling, Landlock sessions/ABI validation/errno classification, and every normal receipt-return path use that value. Standalone Landlock sessions capture their selection at construction. No global configuration is overwritten by the library.
- Receipt `progress` records newly applied Seccomp/Landlock stages; `failure` retains the original exception (including its partial-installation cause chain) or the unsupported-feature reason. `requireInstalled()` rethrows that stored failure with original identity. These deltas do not certify inherited restrictions or CET coverage.
- Eight captured-fallback/evidence cases pass, including unsupported platform and Landlock bypass and Landlock+Seccomp application followed by verification failure. Full enforcer/profiler host suites and the intentional ABI/Java-constructor migration passed.
- Fresh-JVM allow-list checks initially exposed cold progress/failure classes loading after restrictive installation. Added both classes to the existing preloading closure; the two actual-kernel `AllowListTest` cases then passed without granting additional paths or syscalls.
- `./gradlew build integrationTest integrationTestFreshJvm` passed: 272 tasks, 81 executed, 191 up-to-date. Full caller-mapped Docker gate passed in 48s. Fresh packaged Java/Kotlin consumer/scope compiles and dependency-lock refresh passed.
- Application-owned capability/native-engine/runtime snapshots, full CET outcomes, and failed-worker quarantine/retirement remain C3/C7 work. This issue stays open for repository review.

---
<!-- id: issue-20261006-181603-339-capture-installation-fallback-once-and-retain-fail  file: issue-20261006-181603-339-capture-installation-fallback-once-and-retain-fail.md -->
