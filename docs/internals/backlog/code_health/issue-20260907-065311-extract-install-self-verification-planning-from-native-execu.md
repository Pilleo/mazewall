---
title: "Extract install self-verification planning from native execution"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/InstallSelfVerifier.kt"
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/SelfVerificationPlan.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/InstallSelfVerifierTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/SelfVerificationPlanTest.kt"
target_symbols:
  - "InstallSelfVerifier"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.seccomp.InstallSelfVerifierTest"
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Extract install self-verification planning from native execution

**Context:**
`InstallSelfVerifier` currently derives probe eligibility, expected outcomes, raw-syscall execution,
diagnostic publication, and memoization in one native-facing singleton. The policy decision is
deterministic but cannot be covered as a complete host-unit matrix without coupling tests to a
kernel. Separate that decision from execution while preserving its opt-in and fail-closed contract.

**Needed:**
1. Introduce an internal immutable `SelfVerificationPlan` that derives bounded probes from the
   compiled program, architecture, and optional merged `ContainerState`, without syscalls,
   cache mutation, or event publication.
2. Keep `InstallSelfVerifier` as the executor: run planned raw probes, compare actual and oracle
   outcomes, publish the current diagnostic, and memoize a program identity only after every probe
   passes. A mismatch or native failure must remain fail-closed.
3. Add named tables for ALLOW, errno-deny, KILL, argument-inspected, and union-aware cases, plus
   focused executor tests for mismatch and non-memoization after failure.
4. Run `./gradlew :enforcer:test --tests io.mazewall.seccomp.InstallSelfVerifierTest` and
   `./gradlew :enforcer:test --tests io.mazewall.seccomp.SelfVerificationPlanTest`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-065311  file: issue-20260907-065311-extract-install-self-verification-planning-from-native-execu.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
