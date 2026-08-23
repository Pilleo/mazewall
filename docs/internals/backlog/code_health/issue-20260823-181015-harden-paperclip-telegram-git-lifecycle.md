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
  - "scripts/paperclip_telegram_bridge.py"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: Harden Paperclip Telegram done-hook: no rebase of the operator working tree

**Context:**
`scripts/paperclip_telegram_bridge.py` `sync_git_lifecycle` on Paperclip `status == done` glob-finds `docs/internals/backlog/**/issue*MAZ-N*`, rewrites frontmatter, `shutil.move`s to `resolved/`, then runs `git pull --rebase` with `cwd="."` (whatever process cwd is). On `UU` it aborts. It stages both old and new paths and commits. This can rebase the operator’s dirty tree, mismatch identifiers (`MAZ-25` vs `issue-20260823-…` slugs), and commit unrelated files. Orchestrator already has `MarkIssueAsResolved` that updates frontmatter and moves the file with tested parser helpers.

**Needed:**
1. Stop calling `git pull --rebase` from the bridge. Resolution of markdown is a dispatcher/orchestrator job (`MarkIssueAsResolved` / `ResolveTaskState`), not a Telegram SSE side effect on an unknown cwd.
2. If the bridge remains the trigger: resolve `backlogFile` from issue metadata only (already preferred). Fail and Telegram-alert if metadata is missing; do not glob `MAZ-` against timestamps.
3. If a commit is required, operate in a dedicated clone/worktree, add only the moved backlog file, and never rebase. Operator push stays manual or goes through orchestrator merge.
4. Tests: Python unit tests with tmp git repo (or a Kotlin test if the logic moves). Cases: missing `backlogFile` → no git; happy path move without rebase; dirty extra file not staged.
5. Do not swallow git failures. Alert Telegram and leave the Paperclip issue identifiable for retry.

---

**Verification:** Tests as above. `rg "git pull --rebase" scripts/paperclip_telegram_bridge.py` is empty.
