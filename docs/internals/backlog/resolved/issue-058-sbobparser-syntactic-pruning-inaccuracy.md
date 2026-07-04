---
title: "`SbobParser` Syntactic Pruning Inaccuracy"
severity: "HIGH"
status: "resolved"
---

# 🔴 [Severity: MEDIUM]: `SbobParser` Syntactic Pruning Inaccuracy

**Target:** `io.mazewall.SbobParser.kt` (specifically `pruneSubpaths`)
**Context:** Pruning relies on syntactic `normalize()` and `startsWith()` checks. If a parent path is a symlink to a different filesystem branch, syntactic pruning is invalid and can lead to incorrect permission grants.
**Needed:** Document this limitation or switch to a more robust pruning strategy that considers the physical inode structure.
