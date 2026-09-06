---
title: "Align orchestrator backlog validation with active Tier-E metadata"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt"
target_symbols:
  - "BacklogValidator"
open_questions: false

paperclip_issue_id: "5bc80a8f-fdb0-4228-8bb6-9edb715c3c0a"
paperclip_identifier: "MAZ-1067"
---

# 🟡 [Severity: MEDIUM]: Align orchestrator backlog validation with active Tier-E metadata

**Context:**
`./gradlew :tools:orchestrator:checkBacklog -PincludeOrchestrator=true` currently reports 34 errors for active Tier-E backlog metadata: unsupported components, target modules that no longer exist in `settings.gradle.kts`, and dangling dependencies. The lightweight repository validator passes, so automated flows receive contradictory backlog-integrity results. This is independent of the former missing `:tier-e-proto` Gradle project: `./gradlew help` now succeeds without that directory.

**Needed:**
1. Decide and document the supported project mapping for Tier-E work packages without reintroducing a nonexistent Gradle subproject.
2. Align the Tier-E frontmatter components, target modules, and dependencies with that mapping, or explicitly teach `BacklogValidator` how to represent planned-but-not-yet-created work.
3. Add a regression test for the accepted Tier-E metadata shape.
4. Verify both `./scripts/adkw check-backlog` and `./gradlew :tools:orchestrator:checkBacklog -PincludeOrchestrator=true` pass.

---
<!-- id: issue-20260906-012035  file: issue-20260906-012035-align-orchestrator-backlog-validation-with-active-.md -->
