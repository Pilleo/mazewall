---
title: "Introduce Branch Sanitization and Checkout Checks in Orchestrator Merging Logic"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Introduce Branch Sanitization and Checkout Checks in Orchestrator Merging Logic

**Context:**
The orchestrator's git interaction layer (`GitHubCli.kt`) implements standard merge strategies in an isolated worktree `../temp-merge-<pr>` to integrate `origin/master` into feature branches. This prevents the primary working tree of the orchestrator from being clobbered or contaminated.

**The Problem:**
During this integration phase, the orchestrator issues native git commands sequentially (such as checkout, fetch, merge, and push). However, if there are unexpected untracked files or permissions/locking issues inside the worktree directory, or if a branch name contains shell-injection or bad git characters, command execution can succeed partially or fail silently, leading to corrupted worktree metadata, stuck lockfiles (`.git/index.lock`), or accidental branch deletions.

Furthermore, there is a lack of explicit branch name sanitization and validation checks. If a branch name is parsed incorrectly or malicious metadata is injected, running standard shell-based git checkouts inside the worktree poses high security and reliability risks.

**Needed:**
1. Introduce a strict **Branch Sanitization and Checkout Barrier** in the `GitHubCli.kt` / `GitHubClient` operations.
2. Validate that branch names strictly conform to git safety regexes (`^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$`) and do not contain shell control symbols.
3. Before executing any local worktree checkouts or merges, verify the target worktree is perfectly sanitary (no lockfiles, no uncommitted changes, correctly linked).
4. Introduce safety-guarded timeouts on all git processes to prevent a stuck remote git pull/push from hanging the daemon's accept/reactor threads indefinitely.
5. Cover these scenarios in `BranchRebaserTest.kt` using mock shell executions or isolated filesystem setups.
