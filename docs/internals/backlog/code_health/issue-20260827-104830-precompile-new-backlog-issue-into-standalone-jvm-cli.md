---
title: "Precompile new_backlog_issue into standalone JVM CLI"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "scripts/new_backlog_issue.sh"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/NewBacklogIssue.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 4d6db4d7-2b54-4b2b-b8b7-62deb67352a9
paperclip_identifier: MAZ-689
---

# 🟢 [Severity: LOW]: Precompile new_backlog_issue into standalone JVM CLI

**Context:**
Currently, invoking `./scripts/new_backlog_issue.sh` runs `./gradlew -q :tools:orchestrator:newBacklogIssue -PincludeOrchestrator=true`. This causes Gradle to perform a complete configuration evaluation across all root and subprojects (evaluating build scripts, plugins, and dependencies), taking 6–8 seconds per issue creation. Because issue scaffolding is purely an AST inspection, YAML templating, and filesystem operation, it does not need Gradle daemon overhead for every run.

**Needed:**
1. In `tools/orchestrator/build.gradle.kts`, provide a task to compile a lightweight fat jar or binary distribution (or run via precompiled classes).
2. In `scripts/new_backlog_issue.sh`:
   - Check if the compiled CLI jar / classes exist (under `tools/orchestrator/build/libs/` or `tools/orchestrator/build/classes/`).
   - If present, execute `java -jar ... io.mazewall.orchestrator.NewBacklogIssueKt "$@"` directly with JVM startup latency (<300ms).
   - If not compiled, fall back to Gradle invocation or trigger a one-time build.
3. Verify that all CLI flags (`--non-interactive`, `--dry-run`, `--title`, `--file`, `--symbol`, `--dep`) work identically with sub-second execution speed.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-104830  file: issue-20260827-104830-precompile-new-backlog-issue-into-standalone-jvm-cli.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
