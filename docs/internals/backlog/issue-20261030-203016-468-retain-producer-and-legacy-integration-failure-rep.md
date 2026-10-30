---
title: "Retain producer and legacy integration failure reports"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixture.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixtureTest.kt"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain producer and legacy integration failure reports

**Context:**
PR562 review: only the two copied applications retain full XML/HTML. SDK producer and legacy tests run in a disposable clone and their detailed reports are absent from caller-side CI artifacts; normal logs are short. Reuse the existing hardened collector and existing *-reports upload rule. Preserve failure identity, all safe reports even if another module cannot be retained, test selection, cache protocol, native freshness and budgets. User explicitly requests fix and push; no merge/publication.

**Needed:**
1. Write failing cloned-module retention and multi-project error-continuation regressions RED; wrap the complete producer/legacy execution scope in shared exception-safe report retention using existing collector; focused suite and full build, then push PR562 and verify ordinary hosted acceptance plus actual artifact inventory. No new dependencies/upload configuration or artificial failed CI run.

## Execution

Base `28c4f99c7d5668f17e1c51ce060b355df28b68c5`; operator requests fix and push.
- [x] Real failing JUnit module in a disposable Git clone: reports absent RED→retained GREEN.
- [x] Safe-module collection continues after refusals; original and all retention errors preserved.
- [x] Whole producer/legacy execution is covered; acceptance marker follows successful cleanup.
- [x] Focused full harness/build; prepare scoped fix for PR562.
- [ ] Ordinary hosted qualification and artifact inventory after push; record on the PR without a verification-only rerun.

ConsumerFixture.producerReports reuses the existing hardened collector across root,
SDK and legacy project report trees. Fixed project labels end in `-reports`, so the
existing upload rule applies without a workflow/configuration change. Collection
attempts every project, combines ordinary retention exceptions and rethrows them;
`use` retains the original failure as primary. No warning/fallback, new dependency,
test selection, cache-phase, native freshness or budget change.

The new regression runs an actual failing Jupiter test in `demos/cli-demo` inside
the existing disposable-clone helper. It requires the original XML to contain
DELIBERATE_MODULE_FAILURE before testing export, then compares retained bytes and
requires the HTML index. It uses installed JUnit runtime JARs, not new dependency
declarations. A second regression refuses two aliased module trees, still retains a
safe later module, preserves both errors and refuses qualification even when the
body otherwise succeeds. Logs `/tmp/opencode/ci-legacy-{reports-red,real-module-red,reports-green}.log`.

Local free disk is roughly600MB. No older operator artifacts or qualified source
trees are deleted to manufacture space. Full build/focused checks run locally;
the existing ordinary PR CI run supplies fresh source-bound acceptance and actual
artifact-inventory proof without another large local SDK clone or artificial run.

Final local consumerHarnessTest PASS, including11ConsumerFixture cases and4pipeline
cases. Full `./gradlew build -PincludeSpringIntegration=true --console=plain` PASS,
5m11s,253tasks (36executed/217up-to-date). Logs
`/tmp/opencode/ci-legacy-reports-harness-final.log` and
`/tmp/opencode/ci-legacy-reports-full-build.log`. The final acceptance marker is
returned from the report-retention scope and printed only after every collector
has completed successfully. Existing source/ownership guards remain unchanged.
Item stays open for the orchestrator-managed landing path; no merge/package upload.

---
<!-- id: issue-20261030-203016-468-retain-producer-and-legacy-integration-failure-rep  file: issue-20261030-203016-468-retain-producer-and-legacy-integration-failure-rep.md -->
