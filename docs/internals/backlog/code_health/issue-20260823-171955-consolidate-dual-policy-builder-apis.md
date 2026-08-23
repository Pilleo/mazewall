---
title: "Consolidate Dual Policy Builder APIs (Legacy Policy.Builder vs PolicyBuilder/PolicyLists)"
severity: "LOW"
status: "open"
priority: medium
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyLists.kt"
  - "docs/internals/backlog/resolved/issue-20260823-135554-policy-builder-typestate-cast-hole.md"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies: []
---

# 🟡 [Severity: LOW]: Consolidate Dual Policy Builder APIs

**Context:** Two parallel builder stacks coexist: the legacy `Policy.Builder` wrapper (delegating to
`PolicyBuilder`) and the modern `PolicyBuilder`/`PolicyLists`/`PolicyDefinition` DSL used by presets
and deny/allow-list specs. After the type-state fix (issue-20260823-135554, copy-on-promotion), both
are sound but ~300 lines of pure delegation remain, doubling documentation surface, test matrix, and
the risk of divergent semantics evolving independently. Operator has ruled out backwards-compatibility
constraints.

**Needed:**
1. Pick ONE public builder surface (recommend `PolicyBuilder` + `PolicyLists` specs; keep
   `Policy.builder()`/`threadLocalBuilder()` as thin aliases if ergonomic).
2. Delete the legacy `Policy.Builder` inner class; migrate remaining internal call sites and KDoc
   references (e.g. `Landlock.kt:87-89`, `ProcessPolicies.kt:44`).
3. Update README/presentation examples; run full doc sweep for `Policy.Builder`.
4. ArchUnit rule asserting no new delegation wrappers around `PolicyBuilder` are introduced.

