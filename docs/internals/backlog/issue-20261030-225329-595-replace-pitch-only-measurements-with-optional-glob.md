---
title: "Replace pitch-only measurements with optional global benchmark workflow"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".github/workflows/benchmarks.yml"
  - "build.gradle.kts"
  - "src/consumerVerification/kotlin/io/mazewall/verification/BenchmarkWorkflowTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/PitchExamplesAcceptanceTest.kt"
  - "demos/koog-document-agent/build.gradle.kts"
  - "demos/koog-document-agent/README.md"
  - "benchmarks/integration-boundaries/README.md"
  - "README.md"
  - "docs/presentation/PRESENTER_GUIDE.md"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Replace pitch-only measurements with optional global benchmark workflow

**Context:**
Operator explicitly rejects the pitch-only benchmark and approves the smallest global alternative: workflow_dispatch only, existing integration-boundaries harness, raw results plus artifact/source/environment identity, no timing thresholds or PR-gate dependency. Also document it. Remove pitch script/tests, benchmark-only Kotlin entry/tasks and mandatory root/optional acceptance wiring; preserve current global harness, historical locks/evidence, profiler workloads and all independent correctness/security/lifecycle checks. Work in current checkout with inline scope/progress, no new frameworks/dependencies/worktrees or model calls.

**Needed:**
1. Add executable workflow freeze/manual-isolation regressions RED; add manual read-only workflow reusing existing producer/bootstrap/resource probe/harness/validator and bounded logs with always-uploaded diagnostics; remove pitch-only code and callers; update public guidance and testing strategy; focused regression suite, actual local bounded native recipe if prerequisites allow, one independent whole-branch review then final full build and push existing PR without merge/publication. Historical benchmark receipts stay historical, not new qualification.

## Approved inline scope and progress

Base `b81281d2f82fd97dad263fe85ffcac172d74829d`. The user says the separate pitch
script will not be used, then explicitly approves the smallest optional global
job and documentation. This supersedes prior instructions to retain all15pitch
measurement forks; it does not remove independent correctness/lifecycle checks.
- [x] Identify pitch-only callers, tasks, tests, fixtures and guidance.
- [x] Execute actual workflow freeze regressions RED→GREEN (not string assertions).
- [x] Remove pitch driver/selftests/Kotlin entry/tasks and mandatory CI calls.
- [x] Add manual-only existing-harness workflow and document operation/evidence/limits.
- [x] Full host harness (48tests), YAML/shell syntax and reference/backlog checks.
- [ ] Actual bounded native workflow recipe from committed source.
- [ ] One independent whole-branch review, final full build, scoped push/PR update.

Ruling: keep implementation/progress here and execute natively in the existing
checkout—prior operator preference forbids separate plans/journals and worktrees.
Ruling: first global job runs only the existing integration-boundaries harness—no
new workload framework, app performance suite or profiler-matrix expansion was
approved. Hosted timings remain descriptive, not a baseline or regression gate.
Ruling: host Ubuntu26.04/JDK25 uses the existing resource probe and native harness
without container/policy fallback; kernel/permission failures remain run failures.
New coordinate locks/local publications do not rename or overwrite P617 evidence.

Two regressions execute the freeze and verification scripts extracted from the
actual workflow against owned Git/publication/JAR fixtures: valid worker bytes
mint the expected custody, unchanged inputs pass, tampered publication fails;
mismatched embedded worker bytes fail without custody. Missing workflow observed
RED in `/tmp/opencode/global-benchmarks-red.log`, GREEN in
`/tmp/opencode/global-benchmarks-green.log`. Fixture iteration failures (missing
owned log parents and duplicate CREATE_NEW evidence labels) were corrected in
test setup only; no production ownership checks were weakened.

Actual generated worker metadata and the existing package verifier require format2,
not the initially assumed format1. Corrected the fixture to the real format first,
observed failure of the actual freeze script, then corrected its format guard;
the full48-test host harness passes. RED log
`/tmp/opencode/global-benchmarks-format-red.log`, final focused log
`/tmp/opencode/global-benchmarks-harness.log`. YAML was parsed as manual-only,
read-only and independent, with always-uploaded diagnostics; every run block
passes `bash -n`. Actionlint is not installed, so that tool is not claimed.

---
<!-- id: issue-20261030-225329-595-replace-pitch-only-measurements-with-optional-glob  file: issue-20261030-225329-595-replace-pitch-only-measurements-with-optional-glob.md -->
