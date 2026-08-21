---
title: "Preserve existing public enforcer package classes for binary compatibility"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainmentViolationException.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/SandboxDispatcher.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7iSmJD
---

# 🔴 [Severity: HIGH]: Preserve existing public enforcer package classes

**Context:** Existing applications compiled against `io.mazewall.enforcer.ContainmentViolationException` and `io.mazewall.enforcer.SandboxDispatcher` now fail linkage because these public classes were moved to `io.mazewall.enforcer.api` without compatibility definitions in the old package. Currently only `ContainedExecutors` has a compatibility facade in the old package.

**Problem:**
- Binary compatibility broken for consumers of moved classes
- Source compatibility broken for imports of old package names

**Impact:**
- Breaking change for existing users of the library
- Prevents smooth upgrades

**Needed:**
1. Add deprecated forwarding classes/objects in `io.mazewall.enforcer` package for:
   - `ContainmentViolationException` (typealias or deprecated class)
   - `SandboxDispatcher` (deprecated object)
2. Ensure all relocated public APIs have compatibility facades

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3796525635
