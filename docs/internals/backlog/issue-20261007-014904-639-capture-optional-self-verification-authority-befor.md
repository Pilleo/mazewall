---
title: "Capture optional self-verification authority before installation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer, platform]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/InstallSelfVerifier.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationProgress.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/native/LinuxNative.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/InstallSelfVerifierTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/CapturedSeccompBackendNativeTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: [InstallSelfVerifier, InstallationAttempt, RuntimeSnapshot]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Capture optional self-verification authority before installation

**Context:**
The installation pipeline pins native authority, but optional self-verification still selects its gate after irreversible installation and uses mutable global raw-syscall traits for every probe. A native callback can change the property to disable previously required verification, or redirect denied probes after successful liveness. Default real-engine classification also consults the current backend instead of the selected snapshot.

**Needed:**
1. Capture verification selection with the attempt's native snapshot before installation; propagate it through the existing verifier, including union-aware and denied-only branches.
2. Keep the default real/mock classification attached to the immutable snapshot, and preserve explicit operator opt-in/out.
3. Prove backend/property replacement cannot redirect or silently disable verification; strengthen isolated actual-kernel capture acceptance with forced self-verification.

## Recorded regression evidence

`./gradlew :enforcer:test --tests '*InstallSelfVerifierTest*'` first failed two new cases (`run-0b63e7abc3bc`): liveness switched the global backend and denied CONNECT probes diverged; installation mode verification changed the property to false and installation incorrectly succeeded. Both cases passed after explicit capture, together with platform tests (`run-4db08da84544`).

This slice preserves existing program-keyed verification memoization. Unified application-owned context and memoization evidence scope remain follow-up work; no complete C3 architecture claim is made.

Full enforcer/profiler host tests, platform checks, ABI/quality and focused native gates passed (`run-fb5a3c44fffa`), after an initial command timeout. The strengthened isolated kernel test forces self-verification and passed without skips. `./gradlew build integrationTest integrationTestFreshJvm` passed in 3m36s, 265 actionable tasks (83 executed, 182 up-to-date), after adding the scaffolded issue to the backlog index (`run-e2d27f5c926a`). Serial full Docker verification passed in 62s (`run-54c95a6f71f6`). An earlier concurrent host/container run failed process-tree lifecycle checks; it is independently recorded in `issue-20261007-070828-910-investigate-container-process-tree-cleanup-failure.md`, without asserting a cause or lifecycle fix. Keep future shared-checkout gates serial.

---
<!-- id: issue-20261007-014904-639-capture-optional-self-verification-authority-befor  file: issue-20261007-014904-639-capture-optional-self-verification-authority-befor.md -->
