---
name: fix_backlog_item
description: >
  TDD protocol for an open ADK backlog item. Trigger on: fix backlog, implement
  this issue, resolve issue.
---

# Fix backlog item

1. Read the issue in `docs/internals/backlog/`. Answer its questions from the repo before asking a human.
2. `./scripts/adkw doctor` and `./scripts/adkw blast-radius <Symbol>`. If Codanna is not `READY`, record degraded evidence and continue.
3. Write a failing test. Do not implement until it fails for the right reason.
4. Implement the smallest fix. Do not swallow errors, disable tests, or weaken guards to get green.
5. `./scripts/adkw guard <changed-file> --stage syntax` then the project test command.
6. `./scripts/adkw check-backlog`. Do not resolve the issue if `./scripts/adkw check-delivery` reports a dirty worktree with unrelated files.
7. Set `status: resolved` and move the file to `docs/internals/backlog/resolved/`.
