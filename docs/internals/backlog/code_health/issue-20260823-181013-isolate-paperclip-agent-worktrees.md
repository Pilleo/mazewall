---
title: "Do not run Paperclip coding agents on the shared jseccomp working tree"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/README.md"
  - "scripts/run_orchestrator.sh"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: Do not run Paperclip coding agents on the shared jseccomp working tree

**Context:**
Live Paperclip agents “Founding Systems & Security Engineer” (grok_local), “Vibe ACP Developer”, and “Antigravity ACP Developer” use `cwd=/home/leanid/Documents/code/java/jseccomp`. Vibe was `running` while others are idle on the same checkout. Paperclip default `sharedWorkspaceConcurrency=auto` **allows** concurrent local runs. Orchestrator conflict slots are meaningless if a second control plane mutates the same dirty tree. Paperclip issue-worktree support exists in runtime but UI is gated (`doc/experimental/issue-worktree-support.md` in the Paperclip repo). `AGENTS.md` §8.1 already assumes one dirty tree and a stash protocol.

**Needed:**
1. Document a hard operator invariant in `tools/orchestrator/README.md`: Paperclip coding adapters MUST use an isolated git worktree (or Jules remote) per issue; never the operator’s primary checkout.
2. Add a small provision helper invoked by the dispatcher (script called from `run_orchestrator.sh` or a Gradle task), e.g. `git worktree add ../jseccomp-wt-<issueId> HEAD` and pass that path as adapter `cwd` / Paperclip execution workspace. Teardown on `done`/`cancelled`.
3. If the Paperclip project already has `executionWorkspacePolicy` JSON, set `sharedWorkspaceConcurrency: serialize` for the mazewall project as a belt-and-suspenders default until worktrees are per-issue.
4. Tests: unit-test the worktree path naming and that the helper refuses to use the repo root (compare canonical paths). Do not `git worktree add` inside `:tools:orchestrator:test` against the real clone; use a temp git repo fixture (pattern already in `StateHandlerTest`).
5. Do not implement intra-module parallel scheduling in this issue.

---

**Verification:** Fixture test proves the helper creates a worktree under a temp git repo and rejects repo-root cwd. README states the invariant. `./gradlew :tools:orchestrator:test`.
