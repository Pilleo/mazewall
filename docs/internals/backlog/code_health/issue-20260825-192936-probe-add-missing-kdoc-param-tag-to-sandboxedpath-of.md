---
title: "PROBE: Add missing KDoc @param tag to SandboxedPath.of"
severity: "LOW"
status: "open"
priority: high
dependencies: []
component: "docs"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/SandboxedPath.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 54e7152c-973d-48ff-b01a-957ec826c295
---

# 🟢 [Severity: LOW]: PROBE: Add missing KDoc @param tag to SandboxedPath.of

**Context:** Loop-probe task: deliberately trivial, mechanical, single-file. Exists to validate
the Paperclip dispatch → agent execution → PR → review pipeline end-to-end without domain risk.
**Needed:** SandboxedPath.companion.of(path, allowNonExistent) has @param path and @param allowNonExistent documented; verify both exist and match parameter order/names exactly. If either is missing or misnamed, add/correct it. No other edits.
