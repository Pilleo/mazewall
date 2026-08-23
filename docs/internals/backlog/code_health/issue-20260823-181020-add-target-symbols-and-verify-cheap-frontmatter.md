---
title: "Extend backlog frontmatter with target_symbols and verify.cheap"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/BacklogParserEnhancedTest.kt"
  - ".agents/skills/create_backlog_issue/SKILL.md"
effort: "medium"
autonomy: "autonomous"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Extend backlog frontmatter with target_symbols and verify.cheap

**Context:**
Scheduler and Paperclip ingest only see `target_files` / `target_modules`. Planning wants method-level intent and a cheap test command without dumping ASTs into the issue body. `create_backlog_issue` already documents `target_files` for parallel slots. Missing optional fields: `target_symbols` (JVM fqcn/method) and `verify` / `verify.cheap` (host Gradle test slice) and `needs_kernel`. Validator today requires non-empty `target_files` for open issues; new fields must be optional so existing 91 issues stay valid.

**Needed:**
1. Parse optional YAML lists: `target_symbols`, `verify_cheap` (or nested `verify.cheap` if the parser can read nested maps without breaking current frontmatter). Prefer flat `verify_cheap: ["./gradlew :enforcer:test --tests …"]` and `needs_kernel: false` to avoid YAML map complexity in the current parser.
2. Expose them on `BacklogIssue`. Empty lists are valid.
3. Validator: do not require `target_symbols`. If present, each entry must be non-blank. `needs_kernel` if present must be `true`/`false`.
4. Update `.agents/skills/create_backlog_issue/SKILL.md` template with the new optional fields and a one-line comment that they are scheduler/worker contract, filled by humans or by `issue-20260823-181021`.
5. Tests in `BacklogParserEnhancedTest` for presence, absence, and quoted Gradle command strings.
6. Do not change `selectAndStartTasks` conflict logic in this issue.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.BacklogParserEnhancedTest --tests io.mazewall.orchestrator.BacklogValidatorTest`. Existing open issues still pass `checkBacklog`.
