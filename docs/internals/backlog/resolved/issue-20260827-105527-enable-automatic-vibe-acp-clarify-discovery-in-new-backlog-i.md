---
title: "Enable automatic Vibe ACP clarify discovery in new_backlog_issue"
severity: "LOW"
status: "resolved"
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
paperclip_issue_id: b95aafd1-1fc3-4e29-a74d-8abf232a4247
paperclip_identifier: MAZ-620
---

# 🟢 [Severity: LOW]: Enable automatic Vibe ACP clarify discovery in new_backlog_issue

**Context:**
`new_backlog_issue.sh` only runs the LLM clarify loop (weak authoring, side-effect investigation, and strong review) when the `--clarify` flag is explicitly supplied. When `vibe-acp` or `agy` is installed locally on the system (`PATH`), developers and agents benefit from having questions, context, and needed steps automatically enriched by default without needing to memorize or pass extra flags.

**Needed:**
1. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/NewBacklogIssue.kt`:
   - Automatically enable `clarify = true` if `AcpCommandResolver.resolvePair(env)` successfully discovers an ACP binary (such as `vibe-acp`), unless `--no-clarify` is explicitly provided.
   - Add a `--no-clarify` flag to allow opting out when running in air-gapped or minimal test environments.
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt`:
   - Enhance JSON response parsing so that model outputs with markdown fences or conversational preambles are parsed reliably.
   - Default `has_side_effects` to `hits.isNotEmpty()` when the model omits an explicit boolean response so strong review is not aborted prematurely.
3. Test ACP auto-discovery and `--no-clarify` flag behavior.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-105527  file: issue-20260827-105527-enable-automatic-vibe-acp-clarify-discovery-in-new-backlog-i.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
