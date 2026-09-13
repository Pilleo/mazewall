---
title: "Harden Paperclip Telegram done-hook: no rebase of the operator working tree"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogResolver.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/README.md"
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: "d9ab0889-e165-4ec6-be82-7708aa7563d5"
paperclip_identifier: "MAZ-1086"
---

# 🔴 [Severity: HIGH]: Harden Paperclip Telegram done-hook: no rebase of the operator working tree

**Context:**
The historical Python bridge is no longer present. Resolution now belongs to
`BacklogResolver`/`ResolveTaskState`, which must remain explicit about its repository/worktree
authority and must never rebase an operator checkout or stage unrelated files.

**Needed:**
1. Keep resolution in the dispatcher (`MarkIssueAsResolved` / `ResolveTaskState`), not in a board notification side effect on an unknown cwd.
2. Resolve `backlogFile` from issue metadata only. Fail and notify if metadata is missing; do not glob `MAZ-` against timestamps.
3. If a commit is required, operate in a dedicated clone/worktree, add only the moved backlog file, and never rebase. Operator push stays manual or goes through orchestrator merge.
4. Tests: Python unit tests with tmp git repo (or a Kotlin test if the logic moves). Cases: missing `backlogFile` → no git; happy path move without rebase; dirty extra file not staged.
5. Do not swallow git failures. Alert Telegram and leave the Paperclip issue identifiable for retry.

---

**Verification:** Tests as above. The removed bridge path must not be reintroduced, and no
dispatcher resolution path may invoke `git pull --rebase`.
