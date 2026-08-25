---
title: Integrate Mergiraf AST Syntax-Aware Git Merge Driver into Repository Configuration
severity: HIGH
status: open
priority: high
dependencies: []
component: ci
target_modules:
- :tools:orchestrator
target_files:
- build.gradle.kts
effort: small
autonomy: autonomous
paperclip_issue_id: 8bfa0932-bde2-4d26-9c47-560f8babc773
---

# 🔴 [Severity: HIGH]: Integrate Mergiraf AST Syntax-Aware Git Merge Driver into Repository Configuration

**Context:**
Standard line-oriented Git merges fail on non-overlapping code changes within the same file (such as two parallel agents adding separate methods or imports to the same class file), producing `<<<<<<<` conflict markers that halt branch rebases.

**Needed:**
1. Configure `.gitattributes` to route Kotlin (`*.kt`) and Java (`*.java`) source files to the `mergiraf` Tree-sitter AST merge driver, and markdown files (`*.md`) to `union` merge.
2. Create `scripts/setup_mergiraf.sh` helper script to install `mergiraf` and register its Git merge driver (`git config merge.mergiraf.driver ...`) for local developers and CI/container environments.
3. Verify that non-overlapping method additions to Kotlin source files merge cleanly without conflict markers.
