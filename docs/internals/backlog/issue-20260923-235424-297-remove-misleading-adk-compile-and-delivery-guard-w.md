---
title: "Remove misleading ADK compile and delivery guard wiring"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["scripts"]
target_files:
  - ".agent-devkit.json"
  - ".agents/hooks.json"
  - ".agents/skills/file_structure/SKILL.md"
  - ".agents/skills/fix_backlog_item/SKILL.md"
  - ".claude/skills/file_structure/SKILL.md"
  - ".claude/skills/fix_backlog_item/SKILL.md"
  - ".codex/hooks.json"
  - ".codex/skills/file_structure/SKILL.md"
  - ".codex/skills/fix_backlog_item/SKILL.md"
  - ".grok/hooks/adk_guards.json"
  - "AGENTS.md"
  - "docs/internals/designs/profiler/tier-e-measured-hot-path-optimization-plan.md"
  - "scripts/compiler_guard.py"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Remove misleading ADK compile and delivery guard wiring

**Context:**
Post-edit ADK hooks and verificationCommands advertised Kotlin compilation as validation for Python and shell files, and the delivery stage was not configured. The operator requested removal rather than redesign. Preserve normal project tests and context-sync hooks.

**Needed:**
1. Remove repository ADK guard hook wiring and mandatory guard guidance, delete the unused legacy compiler guard, retain discovery/backlog tools, and validate the remaining hook JSON and Gradle build.
2. Keep generated ADK `setup --repair-hooks` out of this delivery: the installed binary still expects the removed hooks and would recreate them. An upstream opt-out is a separate tooling task.

---
<!-- id: issue-20260923-235424-297-remove-misleading-adk-compile-and-delivery-guard-w  file: issue-20260923-235424-297-remove-misleading-adk-compile-and-delivery-guard-w.md -->
