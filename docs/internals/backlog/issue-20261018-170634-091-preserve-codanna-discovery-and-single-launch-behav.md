---
title: "Preserve Codanna discovery and single-launch behavior in ADK hooks"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "testing"
target_modules: []
target_files:
   - "scripts/adk-codanna-reindex"
   - "scripts/git_async_indexer.sh"
   - "scripts/hooks/post-commit"
   - "scripts/hooks/post-checkout"
   - "scripts/hooks/post-merge"
   - "scripts/hooks/post-rewrite"
   - "scripts/tests/adk_hook_indexing_test.sh"
target_symbols: []
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Preserve Codanna discovery and single-launch behavior in ADK hooks

**Context:**
Read-only review of pending local ADK imports reproduced a discovery regression and duplicate scheduling in exclusively owned disposable Git fixtures: find_codanna resolves a home-directory installation but the replacement helper silently skips it because it checks PATH only; the actual post-commit call graph launches two Codanna stubs through direct and git_async_indexer calls. The duplicate caller paths already exist in the base; the pending helper removes their best-effort busy check. Keep pending operator edits intact and qualify a separate scoped correction before publishing them.

**Needed:**
1. Reuse scripts/find-tool.sh discovery in the shared reindex helper and add a fixture proving a home-directory or CODANNA_BIN installation works without being on PATH.
2. Give the existing hook call graph one launch owner and add a real-hook stub regression that observes exactly one scheduled indexer per event.
3. Preserve optional-tool no-op behavior, nonblocking Git hooks and operator configuration. Run the focused hook fixture and full backlog validation; qualify the change separately from the already merged cache fix.

## Evidence

- Pending local `scripts/adk-codanna-reindex:5` uses only `command -v codanna`; the shared `scripts/find-tool.sh:25-35` supports an explicit executable and home-directory fallbacks. An isolated fixture placed a stub in its own `$HOME/.local/bin`, excluded it from PATH and confirmed the existing resolver found it while the replacement hook scheduled nothing.
- `scripts/hooks/post-commit:8-15` invokes both `scripts/git_async_indexer.sh` and the reindex helper. The async wrapper calls the same helper at lines 9-10. With the pending helper's busy check removed, one real hook invocation in a disposable Git repository launched two distinct Codanna stub processes. The checkout/merge/rewrite hooks share the same two paths.
- Retained local observations: `.superpowers/sdd/2026-10-18-ci-cache-key-stability/adk-review-probes.py`, `adk-review-probes.log` and `adk-review-probe-receipt.json`. The probes do not execute the real indexer and do not establish actual Codanna lock contention or duplicated full-index work.
- Operator-owned ADK scripts were not edited, staged or reverted during review. This item describes uncommitted import regressions, not a defect introduced by merged PR 537.

## Scope

Use the existing resolver as a read-only dependency and correct the shared helper/hook ownership with minimal changes and persistent tests. Archive installation, captured-command tooling, analyzer coverage, native enforcement and CI cache behavior are outside this item. No dependency, tool installation, worktree or user-work cleanup is authorized by this record.

---
<!-- id: issue-20261018-170634-091-preserve-codanna-discovery-and-single-launch-behav  file: issue-20261018-170634-091-preserve-codanna-discovery-and-single-launch-behav.md -->
