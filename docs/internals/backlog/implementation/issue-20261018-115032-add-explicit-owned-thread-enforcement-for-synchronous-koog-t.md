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

## Implementation checkpoint

- `8f671221` adds the one-handle safe wait bridge; `c1bfd8a1` adds the public adapter and real native qualification; `ecb131b1` adds strict CI evidence and public guidance. Independent task reviews approved each, with no blocking findings.
- Host evidence includes 15 adapter cases and 18 root harness cases with zero failures/errors/skips; actual separate staged-JAR thread/Portal executions passed, as did the verbatim bounded-reader example and local documentation/style checks. Native results include caller isolation, inferred filesystem/socket denials, ownership/nested/admission checks and held cancellation through actual thread death/canonical drain.
- The optional adapter build with fatal configuration-cache checks passed; exact repeat reused configuration cache. No dependency, core/native implementation, existing Portal/benchmark or workflow change was needed.
- Inherited delta-filter warning debt is recorded separately as [issue-20261018-125543](../code_health/issue-20261018-125543-distinguish-delta-filter-fragments-from-effective-unsafe-prc.md), without silencing the warning or changing policy. Existing locale/toolchain/ADK limitations remain explicit.
- Integrated full-build/committed aggregate and broad branch review remain pending at this checkpoint. This issue is not closed/archived or evidence of arbitrary-code/heap isolation or forced termination.

## Committed local acceptance — 18 October 2026

Tested/reviewed executable producer **`bf98ee873b31d5253efb125e192da975841f39d3`**, candidate **`0.1.0-integration.bf98ee873b31`**. Later evidence-only Markdown bookkeeping does not rename that producer.

| Gate | Actual result |
|---|---|
| Optional Koog build with fatal configuration-cache checks, then exact repeat | Both exit 0; first stores configuration cache, repeat reuses it. Reused task results are not fresh native evidence. |
| `LC_ALL=C ./gradlew build --console=plain --max-workers=1` | Exit 0, 6m33s, 242 actionable tasks: 31 executed / 211 up-to-date. |
| `LC_ALL=C ./scripts/run_containerized_tests.sh :optionalIntegrationCheck --no-configuration-cache --max-workers=1` | Exit 0, 15m10s; real committed publication, separate Portal/thread JVM phases, JDBC/Security and finished-Boot/ownership negatives complete. |
| Owning parent JUnit | 1 test, 0 failures/errors/skips; both exact Portal and thread summaries once; matching producer above. |
| Broad independent review and completion supplement | Source/spec approved with no blocking code defect; finished full-build and committed aggregate/parent-JUnit conditions independently confirmed. |

Local evidence: `.superpowers/sdd/2026-10-18-koog-thread-enforcement/` contains per-task records, `full-build.log`/`.exit`, `optional-aggregate.log`/`.exit`, `committed-aggregate.junit.xml`, `committed-aggregate-receipt.json` and `final-review.md`. Retained parent XML SHA-256: `340badf33cd8e307063d71733079447d239cc3b8b6843e083dc6065c7b51667c`. The accepted new line is:

```text
KOOG_THREAD_V1_OK registry=upstream worker=platform fs=isolated socket=denied caller=unchanged virtual=true unconfined=true validation=pass overload=rejected cancellation=cooperative ownership=checked retirement=complete fallback=false replay=false
```

The disposable container's per-child raw logs were not independently copied; parent/phase/JUnit evidence and separate per-task native records are retained. No remote thread-feature CI/upload, push or merge was performed. Benchmark PR 535 was separately authorized, green and merged before this feature began.

**Remaining:** current full ADK validation is unavailable; current index validation is narrower. SpotBugs reports missing generated coroutine classes, including a new-adapter occurrence, so complete analyzer coverage is not claimed; recorded as [issue-20261018-132632](../code_health/issue-20261018-132632-qualify-spotbugs-analysis-of-generated-koog-coroutine-classe.md). Existing unsafe-prctl delta-warning and locale/toolchain limits remain separate. Operator CI-cache review also recorded [issue-20261018-132635](../testing/issue-20261018-132635-keep-ci-container-cache-restore-and-save-keys-stable-across.md), without changing CI in this feature. Keep this item open for operator/control-plane acceptance; no arbitrary-code, heap or forced-termination guarantee follows.

---

**Verification:** Opt-in `:integrations:koog:test`, ktlint and typed Detekt; root consumer harness/summary/style checks; strict staged-JAR `verifyFreshThreadNative` plus unchanged Portal native gate; included-module full build and actual committed optional aggregate. Use existing current index/link/whitespace validation and retain unavailable full ADK validation separately.

<!-- id: issue-20261018-115032  file: issue-20261018-115032-add-explicit-owned-thread-enforcement-for-synchronous-koog-t.md -->
