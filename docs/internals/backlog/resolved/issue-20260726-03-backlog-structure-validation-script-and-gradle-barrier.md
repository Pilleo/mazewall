---
title: "Implement Automated Backlog Schema Validation Script & Gradle Build Barrier"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: ["issue-20260726-02"]
component: "orchestrator"
target_modules: [":tools:orchestrator"]
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt"
  - "build.gradle.kts"
  - "tools/orchestrator/build.gradle.kts"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Implement Automated Backlog Schema Validation Script & Gradle Build Barrier

**Context:**
Invalid frontmatter formatting, missing target fields, or broken dependency references in backlog issue files currently break `BacklogParser` at runtime inside the Orchestrator daemon rather than at build time.

**Needed:**
1. Create `BacklogValidator.kt` (or Gradle verification task) in `:tools:orchestrator`.
2. Validate that every backlog markdown file in `docs/internals/backlog/` has valid YAML frontmatter containing required fields: `title`, `severity`, `status`, `priority`, `component`, `target_modules`, `target_files`, `effort`, `autonomy`.
3. Validate that `dependencies` refer exclusively to existing backlog issue IDs (fail on dead references).
4. Wire the check into `./gradlew check` and `./gradlew build` so any invalid issue structure fails the build immediately.
