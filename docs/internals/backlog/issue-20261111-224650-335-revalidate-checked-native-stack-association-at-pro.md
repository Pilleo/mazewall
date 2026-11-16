---
title: "Revalidate checked native stack association at producer use"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeStackContexts.kt"
  - "platform/src/test/kotlin/io/mazewall/ffi/internal/RealNativeStackContextsTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedStackScopingPackagedBridge.kt"
target_symbols:
  - "RealNativeStackContexts"
open_questions: false
---

# 🟡 [Severity: HIGH]: Revalidate checked native stack association at producer use

**Context:**
Retained checked bootstrap must revalidate its exact original native deployment and process tuple before a later producer Reserve without repeating bootstrap or changing default behavior.

**Needed:**
1. Add a reference-gated checked revalidation operation preserving existing typed failure, cause, and fatal identity semantics.


**Checkpoint (2026-11-12):** Use-time reference checks and native deployment/tuple revalidation are implemented; the controlled positive returned the original Checked and distinct-reference requests refused. Tuple/error injection remains outside this evidence. Focused tests/static checks passed on the current cohort. This item remains open pending the combined completion gate and review; explicit fixture binding is not natural readiness. See [live stack scoping design](../designs/enforcer/live-stack-scoping.md).

---
<!-- id: issue-20261111-224650-335-revalidate-checked-native-stack-association-at-pro  file: issue-20261111-224650-335-revalidate-checked-native-stack-association-at-pro.md -->
