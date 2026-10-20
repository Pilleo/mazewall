---
title: "Require the complete Gradle verification build in the commit hook"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "ci"
target_modules: []
target_files:
  - "scripts/hooks/pre-commit"
  - "scripts/tests/git_hook_environment_test.sh"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Require the complete Gradle verification build in the commit hook

**Context:**
Operator requested that commits run the full build/verification gate. Active core.hooksPath is scripts/hooks, but pre-commit currently invokes test plus portal tasks with -x check, bypassing formatting, static analysis, configured coverage and the complete build graph. Updated real-Git regression run-9867962205a2 fails on the old test/-x check arguments; new hook must invoke build and propagate any failed verification as commit rejection while preserving staged audits and Git-local environment isolation.

**Needed:**
1. Replace the reduced hook task selection with build without excluding check, retain portal guards/locking/environment boundaries, and verify success, verification-failure rejection and audit/index preservation with the existing real-Git regression and an actual committing full build.

**Resolution:** The active hook selects build without excluding check and reports
full-build failure as commit rejection. All five real-Git regression cases and
shell syntax checks pass; staged/alternate-index audits, portal guards, locking
and foreign-repository isolation remain intact. The commit containing this change
is itself gated by the updated hook's actual complete build. See
[evidence](../../../plans/2026-10-17-full-precommit-quality-gate-evidence.md).

---
<!-- id: issue-20261017-085257-132-require-the-complete-gradle-verification-build-in-  file: issue-20261017-085257-132-require-the-complete-gradle-verification-build-in-.md -->
