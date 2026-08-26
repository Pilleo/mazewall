---
title: "Fix broken Gradle build for fresh clones: missing tier-e-proto directory"
severity: "LOW"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "settings.gradle.kts"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: dcffe68a-4d59-49d5-be2d-d96dd19d20aa
---

# 🟢 [Severity: LOW]: Fix broken Gradle build for fresh clones: missing tier-e-proto directory

**Context:**
`settings.gradle.kts:45` declares `include(":tier-e-proto")`, but the `tier-e-proto/` directory does not exist in the working tree and is not gitignored either. On any fresh clone, every Gradle invocation fails immediately during configuration with:
`Configuring project ':tier-e-proto' without an existing directory is not allowed.` (Gradle 9.x "include_existing_projects_only" rule).
This blocks all developer and agent workflows (`./gradlew test`, `./gradlew build`, `./scripts/new_backlog_issue.sh`, coverage, lint) until someone manually creates the directory — a trap that is invisible in git history and undiscoverable from the error message alone.

**Needed:**
1. Decide the intended state of `:tier-e-proto` (operator decision): either commit a minimal placeholder project (`tier-e-proto/build.gradle.kts`, e.g. empty or with a stub task) or remove/guard the `include(":tier-e-proto")` line.
2. If the module is intentionally generated later, guard the include: `if (file("tier-e-proto").exists()) include(":tier-e-proto")`.
3. Update the hardcoded module allowlist in `BacklogValidator.kt:12` (`VALID_GRADLE_MODULES`) to match whatever settings.gradle.kts declares — this duplication is what currently makes `checkBacklog` reject `:tier-e-proto` even though Gradle accepts it; ideally derive it from settings or add a CI drift check.
4. Verify on a pristine clone (or `git clean -xdn` simulation) that `./gradlew help` succeeds without manual steps.
5. Add a CI smoke step that runs `./gradlew help` from a clean checkout so future configuration drift fails fast.

---

**Verification:** From a clean clone (no `tier-e-proto/`), run `./gradlew help && ./gradlew :tools:orchestrator:checkBacklog`.

<!-- id: issue-20260826-122817  file: issue-20260826-122817-fix-broken-gradle-build-for-fresh-clones-missing-tie.md -->
