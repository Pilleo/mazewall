---
title: "Add auto-inference of target_modules from target_files in new_backlog_issue"
severity: "LOW"
status: "resolved"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PathModules.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueTemplateGenerator.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: bb48beea-40d3-48e2-8dca-26438244d1d6
paperclip_identifier: MAZ-690
---

# 🟢 [Severity: LOW]: Add auto-inference of target_modules from target_files in new_backlog_issue

**Context:**
Currently, when creating a new backlog issue with `new_backlog_issue.sh`, users and agents must either pass `--module` explicitly or rely on default module assignments. However, file paths passed via `--file` already specify the exact Gradle module directory hierarchy (e.g. `enforcer/src/...` $\rightarrow$ `:enforcer`, `tools/orchestrator/src/...` $\rightarrow$ `:tools:orchestrator`, `profiler/src/...` $\rightarrow$ `:profiler`). Auto-inferring `target_modules` directly from `target_files` reduces human error and ensures `checkBacklog` consistency.

**Needed:**
1. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PathModules.kt`:
   - Add a resolver method `inferModulesFromFiles(files: List<String>): List<String>` that maps paths against Gradle project root folders (`settings.gradle.kts`).
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueTemplateGenerator.kt`:
   - Merge inferred module names with any explicitly declared `--module` entries so `target_modules` in generated YAML frontmatter is automatically populated.
3. Add unit tests in `tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt` verifying that passing only `--file enforcer/src/.../Policy.kt` produces `target_modules: [":enforcer"]`.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-105538  file: issue-20260827-105538-add-auto-inference-of-target-modules-from-target-files-in-ne.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
