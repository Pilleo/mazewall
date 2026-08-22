---
title: "Expand BranchRebaser Tests with Real Git Repository Simulation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/BranchRebaserTest.kt"
effort: "medium"
autonomy: "autonomous"
open_questions: true
---

# 🔶 [Severity: MEDIUM]: Expand BranchRebaser Tests with Real Git Repository Simulation

**Context:**
The `BranchRebaser` component manages automated branch rebases, self-healing merge sanitizations, and git rescue operations (to prevent loss of modified markdown backlog files). However, its current unit tests are heavily mocked and only perform string matching on mocked git commands. This is extremely fragile; if git CLI output formats or exit behaviors change, the mocks will still pass, but the orchestrator daemon will crash in production.

**Needed:**
1. Expand the orchestrator testing suite to run integration tests for `BranchRebaser` against real, dynamically-created Git repositories.
2. In the unit tests (e.g., in a new `BranchRebaserGitIntegrationTest.kt` or by expanding `BranchRebaserTest.kt`), create a temporary directory representing a real git repository:
   - Initialize the repository: `git init`
   - Commit some base files representing `master`.
   - Create a feature branch and commit modified target files and a backlog markdown file.
   - Introduce conflict commits on `master`.
   - Invoke `BranchRebaser.rebaseBranch` or `BranchRebaser.selfHealMerge` and verify that the rebase resolves successfully, conflicts are reported correctly, and rescued backlog files are preserved exactly as expected.
3. Clean up all temporary files and Git environments after each test run to prevent test state pollution or disk leakage.

**Verification/Regression Tests:**
- Validate that conflict counts and conflicted file lists returned by the rebase match actual files in conflict exactly.
- Verify that a self-healed branch correctly reverts modifications to disallowed files.
- Run `./gradlew :tools:orchestrator:test` to guarantee full verification.

## ❓ Open Questions
1. **Duplicate Issue Consolidation:** This issue duplicates `issue-20260729_153003-git-integration-testing-with-real-git-repos.md`. Should one of the two be marked as resolved/duplicate?
2. **Local Git Dependency vs JGit:** Should real git integration tests execute the local system `git` binary via `ProcessBuilder` (requiring `git` CLI installed on CI runners), or should they use an embedded JVM Git implementation like JGit?

