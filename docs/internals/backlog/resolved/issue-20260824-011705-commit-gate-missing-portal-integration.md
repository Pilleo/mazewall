---
title: "Commit Gate Does Not Cover :portal Integration Tests"
severity: "LOW"
status: "resolved"
priority: medium
component: "ci"
target_modules:
  - "scripts"
  - ".github/workflows"
target_files:
  - ".git/hooks/pre-commit"
  - "scripts/hooks/pre-commit"
  - ".github/workflows/ci.yml"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟡 [Severity: LOW]: Commit Gate Does Not Cover `:portal` Integration Tests

**Context:** Today's portal debugging session found three stacked bugs (literal-template
classpath, missing producer dependency, worker idle self-exit), all caught only because someone
happened to run `:portal:integrationTest` manually. The pre-commit hook runs `./gradlew test`
(unit tests only), so portal regressions reach CI — or worse, reviewers — unnoticed. The suite is
fast (~12s now that the classpath wiring works).

**Needed:**
1. Append `:portal:integrationTest :portal:test` to the hook's test invocation
   (both `scripts/hooks/pre-commit` and the `.git/hooks` copy until `core.hooksPath` adoption is
   universal).
2. Same addition to the CI workflow's unit-test stage; keep fresh-JVM enforcer suites on their
   existing orchestration.
3. Guard against the known environment hazard: if `settings.gradle.kts` temporarily excludes
   modules (as during current refactors), the gate must degrade to a loud WARNING listing skipped
   modules instead of silently passing.

**Completed via the hybrid loop (2026-08-24):** ingested as MAZ-36, executed by Vibe ACP
Developer (`invocationSource: assignment`, run succeeded, 743KB log), native review pass,
approved, resolved. Implementation landed as commit edcb6ca1: portal tasks in pre-commit with
`check_module_available()` guards against module exclusion (loud WARNING on skip), plus
run_containerized_tests.sh CI inclusion. First end-to-end proof of the Paperclip hybrid loop:
ingest -> assign -> auto-dispatch -> implementation -> review -> approval -> markdown resolved.
