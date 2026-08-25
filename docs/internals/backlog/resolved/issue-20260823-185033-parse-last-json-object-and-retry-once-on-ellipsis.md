---
title: "Parse last JSON object and retry once on ellipsis"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
target_symbols:
  - "parseJsonObject"
  - "parseJsonStringField"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: false
---

# 🟡 [Severity: MEDIUM]: Parse last JSON object and retry once on ellipsis

**Context:**
vibe-acp (Mistral) echoed the example schema and `parseJsonObject` takes the first `{` through the last `}`. Combined with `"context":"..."` that produced an “approved” issue of ellipses. Jules does not parse ACP JSON; this is `--clarify` only. Fix the extractor and allow **one** retry asking for JSON without ellipsis. If retry still fails, keep the last structurally valid draft (FILL if author never succeeded). Never invent body text on the host.

**Needed:**
1. `parseJsonObject` selects the last complete `{…}` object (brace-depth), not first-`{` + last-`}`.
2. After parse, if context/needed are placeholder (`...` / `FILL:` / blank), throw so the author/investigate caller can retry.
3. `authorWithWeak` (and investigate) retry `complete` once with a short “JSON only, no ellipsis, do not echo the schema” user message.
4. Tests: raw text `schema {…} real {context: map grows, needed: 1. Cap.}` parses the real object; a first `...` response then a good second response is accepted; two `...` responses keep FILL.
5. No change to the no-ACP write path.

## Investigation
- 2026-08-23 vibe-acp `--clarify` wrote Context/Needed `...` with `review: approved`.
- `ISSUE_PAYLOAD_SCHEMA` no longer uses ellipsis; models still echo older examples.

## Important details
- Brace matching must ignore braces inside JSON strings (reuse the existing string-escape walk or keep tests to unquoted examples).
- Fail closed: do not copy AST snippets into Context to “save” a failed author.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-185033  file: issue-20260823-185033-parse-last-json-object-and-retry-once-on-ellipsis.md -->
