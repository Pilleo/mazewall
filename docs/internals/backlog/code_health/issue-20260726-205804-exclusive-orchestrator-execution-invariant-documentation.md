---
title: "Document Single Control Plane Development Invariants in AGENTS.md"
severity: "HIGH"
status: "open"
priority: 9
dependencies:
  - "issue-20260726-205803"
component: "docs"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "AGENTS.md"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Document Single Control Plane Development Invariants in AGENTS.md

**Context:**
To ensure human operators and AI agents align on the Single Control Plane architecture, repository guidelines in `AGENTS.md` must explicitly document the invariants governing Orchestrator-driven task execution and git branch safety.

**Needed:**
1. Update `AGENTS.md` with a dedicated "Single Control Plane Architecture" section detailing the core invariants:
   - No direct master pushes (all work enters via Orchestrator backlog items).
   - Serial PR merge queue (Orchestrator manages rebase & merge).
   - Strict working tree sanitization (no un-scoped backlog edits in PR diffs).
   - Mergiraf AST merge driver & union markdown configuration.
2. Provide guidance for operators interacting with Orchestrator via Telegram and CLI commands.
