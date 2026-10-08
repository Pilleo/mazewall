---
title: "Isolate Git-local commit environment from Gradle foreign-repository tests"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "scripts/hooks/pre-commit"
target_symbols:
  - "pre-commit"
open_questions: false
---

# 🟡 [Severity: HIGH]: Isolate Git-local commit environment from Gradle foreign-repository tests

**Context:**
Commit-hook Git-local environment redirects foreign-repository test Git operations into the committing repository, corrupting index, refs and config.

**Needed:**
1. Reproduce in disposable real Git worktrees; unset only git rev-parse --local-env-vars names in the Gradle subprocess after audits.

**Task 4 implementation evidence (2026-10-08):**
- `scripts/tests/git_hook_environment_test.sh` copies the actual hook into disposable
  linked worktrees. Real Git exports the hook context; a minimal Gradle stub performs
  real foreign-repository config, add, commit and ref operations.
- Red: the original hook let these operations change the disposable committing
  repository's HEAD, refs, index, `core.bare` and identity. No real repository was
  used as a destructive fixture.
- Green: the minimal Gradle-only subshell clears Git's reported local names after
  audits. Four cases verify foreign isolation, unchanged gate arguments with/without
  portal inclusion, exit-23 Gradle failure aborting commit with hook exit 1, and
  staged forbidden-pattern rejection before Gradle. Original state snapshots match.
- Run with `flock <shared-verification.lock> bash scripts/tests/git_hook_environment_test.sh`;
  `bash -n scripts/hooks/pre-commit scripts/tests/git_hook_environment_test.sh` passes.
- Coordinator acceptance/integration remains required. Keep this issue open until
  that evidence is captured; fleet report contains exact logs and commit details.

**Independent review F1 follow-up:**
- The original default-index staged-audit case proves ordinary staged rejection,
  but cannot detect premature Git-local clearing: discovery from the hook cwd
  still finds the same default index. The stronger evidence claim is withdrawn.
- Added `alternate-index-audit`: a real disposable linked-worktree commit receives
  an explicit fixture-owned `GIT_INDEX_FILE`. Forbidden content exists only in
  that alternate index; default index and worktree contain safe content.
- Assert rejection before Gradle, byte-for-byte preservation of both indexes
  across the failed commit, and unchanged original HEAD/refs/config around the
  hook. The copied hook wrapper also verifies the supplied alternate index path.
- Prepare the alternate index with real `git write-tree` before its checksum
  snapshot, so Git's normal pre-hook cache-tree preparation does not masquerade
  as hook mutation.
- Red: a copied hook deliberately clearing Git-local names before audits passes
  the original four cases, but accepts the alternate-index forbidden commit and
  fails the new rejection assertion (exit 1). Green: the unchanged reviewed hook
  passes all five cases, with both index checksums and original state preserved.
- Exact red/green commands, logs and follow-up commit are recorded in the shared
  task-4 report; production hook remains unchanged from the reviewed implementation.

---
<!-- id: issue-20261008-011223-216-isolate-git-local-commit-environment-from-gradle-f  file: issue-20261008-011223-216-isolate-git-local-commit-environment-from-gradle-f.md -->
