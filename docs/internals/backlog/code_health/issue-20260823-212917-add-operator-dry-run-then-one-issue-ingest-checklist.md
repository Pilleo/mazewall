---
title: "Add operator dry-run then one-issue ingest checklist"
severity: "LOW"
status: "open"
priority: high
dependencies:
  - "issue-20260823-181011"
  - "issue-20260823-212901"
  - "issue-20260823-212906"
  - "issue-20260823-212912"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/README.md"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: false
---

# 🟢 [Severity: LOW]: Add operator dry-run then one-issue ingest checklist

**Context:**
After parser extract (`181010`) and POST ingest (`181011`), the operator still needs a one-page checklist to prove the mirror on **one** real issue without switching off the daemon. Today MAZ-25 docs claim end-to-end while the kts still TODOs the POST.

**Needed:**
1. README subsection **Paperclip mirror (optional)**:
   - Keep `./scripts/run_orchestrator.sh` running.
   - `runPaperclipSync --dry-run` (exact Gradle command from `181010`) must print the same next issue the daemon would select.
   - One live POST for that issue only; confirm `paperclip_issue_id` in YAML.
   - Confirm Paperclip card has **no** agent run (see `212906`).
   - Confirm Jules/CI/merge still happen only in the daemon.
   - Do not crontab the kts.
2. Do not implement Bernstein, Agent OS, or a second SELECT_TASK.
3. No code change beyond README unless `181010` left the command name undocumented.

## Investigation
- `181011` is the POST; this issue is the operator proof, not a second ingest implementation.

---

**Verification:** README lists dry-run then one POST, and states the daemon remains the loop. `./gradlew :tools:orchestrator:checkBacklog -PincludeOrchestrator=true`.

<!-- id: issue-20260823-212917  file: issue-20260823-212917-add-operator-dry-run-then-one-issue-ingest-checklist.md -->
