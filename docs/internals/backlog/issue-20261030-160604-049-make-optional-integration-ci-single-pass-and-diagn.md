---
title: "Make optional integration CI single pass and diagnosable"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/consumerVerification/kotlin/io/mazewall/verification/PitchExamplesAcceptanceTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixture.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixtureTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - ".github/workflows/ci.yml"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Make optional integration CI single pass and diagnosable

**Context:**
Operator requests CI/build redesign on PR562, not more demo changes. Current copied acceptance executes native/presentation suites in lock, strict and cache-reuse phases; temporary nested reports are not uploaded; the final required aggregate omits optional integration. Keep all existing correctness, native freshness, security, ownership, SDK/publication identity and measurement assertions, limits and independent runners. No dependencies, new worktree, separate plan/journal, fallback, timeout increase or scanner-policy changes.

**Needed:**
1. Native inline plan:1 dependencies-only lock seed, one strict real consumer pass and cached same-graph dry-run with real forced-fresh counter regression;2 durable checkout-owned unique evidence plus failure-safe nested JUnit/HTML retention and safe allowlisted uploads;3 run new app checks earlier, all legacy checks unchanged and fifteen measurement forks last, require all six CI lanes in the final join with executable status-matrix tests;4 focused checks, one independent scope review, full build once and committed-source acceptance, compare observed execution counts/timings and update PR562 without merge. Track checklist and rulings in this scoped item, not separate artifacts.

## Native execution checklist

Base `88367a1246704d82db6f364bc1f5ca11d8a764fa`. Operator explicitly requested
an inline plan and implementation for CI/build, not application behavior changes.
No separate plan/spec/journal or worktree. Existing budgets, dependencies, native
freshness, artifact identity, security and process-ownership assertions stay fixed.

- [x] Task1 dependencies-only lock seeding, one strict real run, same-graph cached dry-run.
- [x] Task2 durable checkout-owned evidence, failure-safe JUnit/HTML retention, allowlisted uploads.
- [x] Task3 earlier application checks, legacy matrix retained, measurements last, six-lane join.
- [ ] Task4 final focused checks, one independent scope review, full build and exact acceptance.
- [ ] Commit/update PR562 and inspect the actual hosted result; never merge or publish packages.

Pre-flight interfaces: buildConsumer(command, copy, evidence, tasks) owns the same
three phases for both applications. ConsumerFixture.optionalIntegrationEvidence
returns one unique durable run directory; retainTestReports exports only framework
report trees. PitchExamplesAcceptance.run returns the acquired copied-project root;
measure consumes it only after all correctness stages. The workflow and policy
guard share the exact six prerequisite names and failure-time diagnostic paths.

## Evidence so far

Task1 real Gradle forced-fresh counters first observed3executions instead of1, then
GREEN with one actual execution of each task. A same-graph dry-run on an accepted
real Koog consumer also reused its cache without executing actions (1second).
Tasks are not made cacheable to manufacture this saving. The real mandatory XML
inventory and publication/lock/checksum checks remain unchanged.

Task2 observed RED for an external temporary evidence directory and absent nested
failure reports, then GREEN for durable unique directories and exact XML/HTML bytes.
Additional checks cover aliased parents before external writes, report aliases with
suppressed retention failure, and early failure before any report exists. Existing
container bind mounts expose the caller-checkout output directly to artifact upload.
The workflow guard also observed RED when failure uploads were removed, then GREEN.

Task3's extracted actual CI shell first accepted integration failure (RED). It now
requires success for optional acceptance too, including cancelled/skipped/empty
results; all existing625/125/25 projections and Go guards remain exercised with
the additional prerequisite fixed to success where appropriate. The unchanged
publication job retains its explicit optional/aggregate default-push prerequisites.

Focused logs in `/tmp/opencode/`: `ci-pipeline-execution-count-red.log`,
`ci-pipeline-harness-red.log`, `ci-pipeline-harness-green.log`,
`ci-pipeline-harness-final.log`, `ci-pipeline-join-red.log`,
`ci-pipeline-diagnostics-policy-red.log`, `ci-pipeline-policy-green.log`,
`ci-plan-dry-run-proof.log`.

Iteration note: running root and buildSrc compilation concurrently collided in their
shared Kotlin incremental outputs. Checks now run serially; a normal forced task
recompilation restored coherent outputs without deleting shared caches. The source
guard's actual-policy failure message now includes the specific violation list.

Hosted baseline: successful run41082880463, head88367a12, optional job860seconds;
its acceptance step732seconds (12m12s). Raw job timing:
`/tmp/opencode/ci-pipeline-baseline-job.json`. This is a comparison baseline, not a
prediction or proof of an end-to-end speedup for the pending change.

Final focused inputs: root consumerHarnessTest PASS at
`/tmp/opencode/ci-pipeline-harness-reviewed-input.log`, including4pitch-harness and
6consumer-fixture cases with zero failures/skips in those suites. The serial
buildSrc CI-policy/actual-verifier checks PASS at
`/tmp/opencode/ci-pipeline-policy-scope-final.log`; YAML parsing, six dependencies,
changed local Markdown references, diff whitespace and backlog validation checked.
Formatting-only churn outside the changed CI-policy sections was removed; no
security lifecycle, scanner or Go workload implementation is altered.

## Independent review and one fix pass

One read-only scoped review (`88367a12..ab1012a5`), session
`ses_e72397f3dfferSFkxp8vkq2Yy8`: no Critical or Minor findings; one Important.
Report walking rejected leaf aliases but could follow intermediate source or
destination aliases before reaching the walked tree. New real-filesystem tests
observed RED for leaked outside sentinel content and outside writes through those
parents. The retention boundary now checks every path component before access and
checks destination entries too. Existing `use` suppression keeps the command's
original exception primary.

The same path check distinguishes definite absence from unreadable metadata:
only NoSuchFileException means no report yet. A protected-parent fixture first
observed RED when AccessDenied was silently treated as absence; permission errors
now remain suppressed retention failures, never warnings or fallback.
Logs `/tmp/opencode/ci-pipeline-alias-review-red.log` and
`/tmp/opencode/ci-pipeline-report-permission-red.log`. No second reviewer loop.

Final: Ruling: the reviewer left full/source gates and hosted artifact/performance
checks for execution after review; those remain completion requirements. Actual
release publication and cancellation delivery are not artificially triggered, and
older application features are outside this CI-only review — preserves the operator
approval/scope boundary — cost if wrong: unobserved external behavior remains
unqualified rather than being inferred from local tests.

---
<!-- id: issue-20261030-160604-049-make-optional-integration-ci-single-pass-and-diagn  file: issue-20261030-160604-049-make-optional-integration-ci-single-pass-and-diagn.md -->
