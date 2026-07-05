---
title: "SbobParser Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "unknown"
effort: "medium"
---

# 🔴 [Severity: HIGH]: SbobParser Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths

**Target:** `io.mazewall.SbobParser` (specifically `pruneSubpaths`)
**Failure Hypothesis:** SbobParser's subpath pruning operates purely syntactically without resolving symlinks. If a staging environment contains a symlinked directory and a real nested directory, pruning will discard the nested path. When the parsed policy is applied, the symlink is rejected, and because the nested path was pruned, the entire tree is left blocked, causing production application crashes.
**Context & Proof:** In `SbobParser.kt`, `pruneSubpaths` syntactically normalizes and sorts path strings. If a profiled workload accessed both `/var/log` (a symlink) and `/var/log/app` (a real directory), the SBoB JSON lists both. `pruneSubpaths` prunes `/var/log/app` because it syntactically starts with `/var/log`. In production, when `Landlock.addRule` is invoked for `/var/log`, `O_NOFOLLOW` triggers a symlink rejection `ELOOP`, so the rule is skipped and no filesystem rule is added. Since `/var/log/app` was pruned, no rule is added for `/var/log/app` either. The application is completely blocked from accessing `/var/log/app` and crashes.
**Cascading Risk Potential:** High usability and stability risk. Causes deterministic, hard-to-debug runtime crashes in production environments when deploying SBoB policies across varying file systems or symlinks.
**Needed:** SbobParser's subpath pruning must be aware of symlink and directory boundaries, or `addRule` must not prune paths that could fail to resolve. A safer solution is to have SbobParser retain all paths and let `Landlock.applyRuleset` perform dynamic pruning after resolving canonical/real paths in the actual environment, or avoid pruning paths syntactically if they could be symlinks.
