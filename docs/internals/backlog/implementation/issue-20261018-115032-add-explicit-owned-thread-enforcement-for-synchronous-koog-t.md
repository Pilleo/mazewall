---
title: "Add explicit owned thread enforcement for synchronous Koog tools"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "integrations/koog/src/main/kotlin/io/mazewall/koog/ThreadToolAdapter.kt"
  - "integrations/koog/src/main/kotlin/io/mazewall/koog/ThreadInvocationAwait.kt"
  - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogThreadProbe.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "integrations/koog/src/test/kotlin/io/mazewall/koog/ThreadInvocationAwaitTest.kt"
  - "tests/consumers/koog-portal/build.gradle.kts"
  - "src/consumerVerification/kotlin/io/mazewall/verification/KoogThreadSummaryTest.kt"
  - "integrations/koog/README.md"
  - "tests/consumers/koog-portal/README.md"
  - "README.md"
  - "docs/internals/designs/core/execution-diagnostics.md"
  - "integrations/2026-10-06-mazewall-integration-foundation-design.md"
target_symbols: ["ThreadToolAdapter", "awaitThreadInvocation", "requireKoogThreadSummary"]
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Add explicit owned thread enforcement for synchronous Koog tools

**Context:**
The operator requested explicit per-thread Koog enforcement, selected the recommended trusted synchronous-action scope and subagent implementation, and approved continuing after green benchmark PR 535 merged. Work is assigned to this session on `feat/koog-thread-enforcement`, based on merge commit `e98ee5d0aaefd8a39b31512c5145b4df1efc19ca`.

Add a thin upstream `Tool` bound to the application's existing `ManagedRuntime` and prepared `InProcessDomain`; preserve the merged process-backed adapter and benchmark. This is not a fallback, arbitrary suspend-tool wrapper, heap/hostile-code isolation boundary or forced-termination mechanism. The local plan/spec are `docs/superpowers/plans/2026-10-18-koog-thread-enforcement.md` and `docs/superpowers/specs/2026-10-18-koog-thread-enforcement-design.md` (intentionally Git-ignored).

`:enforcer` is the exercised owner recognized by the already-present legacy scaffolder. The explicit file inventory above defines the allowed changes; no core/native enforcement source or policy behavior is authorized.

**Needed:**
1. Add a host-safe one-handle wait bridge: submit on the original caller, then interruptibly wait on host IO without callbacks on the restricted worker. Preserve body interruption/cancellation distinctions, original causes, cooperative cancellation and fatal-first request-failure handling; no replay or new executor.
2. Add `ThreadToolAdapter` with explicit owner/domain/action binding and passive genuine diagnostics. Qualify the upstream registry, permitted and denied filesystem operations, socket restriction, unchanged virtual/Unconfined callers, owner/nested/closed rejection, bounded data/admission and physical cleanup/drain in a separate published-only fresh JVM.
3. Require a strict unique thread summary in an additive native CI phase, preserving existing Portal/benchmark checks. Add corruption tests and public ownership/limitations/reproduction guidance, without dependency or native-policy changes.
4. Run focused host/native checks and independent per-task reviews, then integrated configuration-cache/full-build/committed-native gates and broad branch review. Keep unavailable validation explicit; do not push/merge this feature without a later operator instruction.

## Investigation
- Existing `ManagedRuntime` already installs the explicit process-wide baseline and prepares dedicated platform workers; the adapter must not install on coroutine/virtual callers or create a second execution engine.
- Restricted-thread result completion can execute direct callbacks there. Use coroutines 1.10.2 interruptible host-IO waiting, with future unwrapping outside its InterruptedException-catching block and submission before any context hop.
- Baseline adapter test command passed with six existing cases and no failures/errors/skips; the unchanged Test task was UP-TO-DATE, not a new native execution. Actual new tests must have fresh red/green evidence.
- Current ADK distribution remains absent. Preserve its explicit validation limitation; do not install tools or weaken checks. Existing current index validation is separate from full ADK validation.

## Side effects
- Adds an explicit Koog thread-backed Tool API and separate required published native CI phase; preserves Portal and all core/native enforcement implementations.

---

**Verification:** Opt-in `:integrations:koog:test`, ktlint and typed Detekt; root consumer harness/summary/style checks; strict staged-JAR `verifyFreshThreadNative` plus unchanged Portal native gate; included-module full build and actual committed optional aggregate. Use existing current index/link/whitespace validation and retain unavailable full ADK validation separately.

<!-- id: issue-20261018-115032  file: issue-20261018-115032-add-explicit-owned-thread-enforcement-for-synchronous-koog-t.md -->
