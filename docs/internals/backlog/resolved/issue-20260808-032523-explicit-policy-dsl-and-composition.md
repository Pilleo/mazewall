---
title: "Make Policy Mode and Composition Semantics Explicit"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260808-032522"
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt"
effort: "large"
autonomy: "supervised"
---

# 🟡 [Severity: MEDIUM]: Make Policy Mode and Composition Semantics Explicit

**Context:** The primary builder mixes `defaultAction`, `allow`, `block`, `unblock`, `base`, filesystem allow rules and low-level exception flags. Generic `combine()` and `+` conceal that Seccomp actions, filter stacking and Landlock path rules compose differently. `unblock` also sounds capable of expanding permissions even though installed kernel restrictions are monotonic.

**Needed:** With breaking-change approval, provide separate allow-list and deny-list entry points with capability-level methods. Rename composition to restrictive semantics such as `restrictFurtherWith`/`intersection`, reject ambiguous combinations, and keep raw syscall actions under an advanced namespace. Add algebraic/property tests comparing composed policy definitions with sequential kernel stacking and compile tests for process/thread scope transitions.

**Resolution:** Additive `Policy.denyList` / `Policy.allowList` (`PolicyLists`) with `denyProcessCreation` / `denyNetwork` and `advanced { }`. Composition is `restrictFurtherWith` / `PolicyDefinition.intersection` (same algebra as `combine`). `Policy.mode` and `argumentRules` are inspectable. `unblock` KDoc states it cannot expand installed filters. Legacy builder remains. Kernel stacking equality is the existing combine algebra, not a second BPF stacker.
