---
title: "Consolidate Dual Policy Builder APIs (Legacy Policy.Builder vs PolicyBuilder/PolicyLists)"
severity: "LOW"
status: "resolved"
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

**Resolution (2026-08-23) — resolved as WON'T-MERGE-IMPLEMENTATIONS, layering decision documented:**
A deletion spike showed the two builders are NOT duplicates but different LAYERS with different
build products: `PolicyBuilder` is the definition-level engine DSL (`build(): PolicyDefinition`,
consumed by PolicyTransformer/IterativeProfiler/BillOfBehavior/presets), while `Policy.Builder`
is the public facade whose `build(): Policy<S, Uncompiled>` wraps that definition into a typed
Policy. Deleting either forces ~150 call-site churn or flips internal APIs to Policy-wrappers,
with zero safety gain: after issue-135554, every facade method is a pure one-line pass-through
(copy-on-promotion semantics live solely in PolicyBuilder and are covered by
PolicyCompilationCache/PolicyTest regression suites).

Guard instead of merge:
1. Facade delegation must remain pure pass-throughs — any logic added there (beyond promotion)
   is a review flag; the typestate-cast hole cannot regress silently because the aliasing
   regression tests fail on aliased state.
2. KDoc references updated to point at PolicyBuilder for engine-level docs.
