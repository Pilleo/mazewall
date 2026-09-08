---
name: create_backlog_issue
description: >
  Create a structured ADK backlog issue. Use when filing a bug, gap, or task.
  Trigger on: new issue, backlog, file a bug.
---

# Create backlog issue

Do not invent issue filenames.

```bash
./scripts/adkw new-issue --title "<title>" --severity MEDIUM --file <path> --symbol <Symbol> --context "<why>" --step "<first needed step>"
./scripts/adkw check-backlog
```

`--title` is required. Unknown flags fail. The issue id matches the filename.

Keep one issue to one change. Put execution plans under `docs/superpowers/plans/` with `document_type: execution_plan` and `base_revision`.
