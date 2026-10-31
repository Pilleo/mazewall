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

## Outcome

The outer producer/legacy scope reuses the hardened collector and existing
`*-reports` upload rule. Collection continues for safe modules after refusals,
rethrows all retention errors and preserves the original command failure. Overall
acceptance is printed only after collection succeeds.

Real failing cloned-JUnit and multi-module alias/error regressions passed. Hosted
[run41126859334](https://github.com/Pilleo/mazewall/actions/runs/41126859334) and
[current qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md) retain32producer/legacy XML reports with169passing cases
plus HTML. No workflow expansion, dependency, budget or test-selection change.
Open pending managed landing; no merge or package publication.

---
<!-- id: issue-20261030-203016-468-retain-producer-and-legacy-integration-failure-rep  file: issue-20261030-203016-468-retain-producer-and-legacy-integration-failure-rep.md -->
