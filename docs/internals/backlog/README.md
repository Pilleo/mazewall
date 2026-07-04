# Code Issues Backlog

This directory contains modularized backlog items for architectural and security findings. Keeping issues separated improves context efficiency for both developers and AI coding agents.

## Auto-Generated Issue Registries

Individual issue files (`issue-*.md`) created under this directory are automatically scanned, parsed, and registered in the project's architectural maps. Instead of manually maintaining indexes here, consult the dynamically generated knowledge maps:

*   **[Enforcer Module Knowledge Map](../maps/enforcer_map.md)** — Maps design documents, source files, and open issues for the `:enforcer` module.
*   **[Profiler Module Knowledge Map](../maps/profiler_map.md)** — Maps design documents, source files, and open issues for the `:profiler` module.

## Registering a New Issue

To log a new bug, architectural gap, kernel-level nuance, or security vulnerability, simply create a new markdown file under the appropriate subdirectory:

*   `docs/internals/backlog/security/` for security/vulnerability findings
*   `docs/internals/backlog/performance/` for performance findings
*   `docs/internals/backlog/testing/` for testing findings
*   `docs/internals/backlog/code_health/` for other code/architectural health improvements

Ensure your file starts with YAML frontmatter containing `title`, `severity`, and `status`:

```markdown
---
title: "Title of Issue"
severity: "HIGH/MEDIUM/LOW/CRITICAL/ENHANCEMENT"
status: "open"
---

# 🔴 [Severity: HIGH]: Title of Issue
**Context:** ...
**Needed:** ...
```

Once the file is saved, run `./gradlew generateKnowledgeMap` (or `./gradlew check` / `./gradlew build`) to automatically parse and link the issue in the respective knowledge maps.
