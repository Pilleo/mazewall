---
title: "Downgrade read-only deny lists to thread-local scope"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/PolicyLists.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7kBdT
---

# 🟠 [Severity: MEDIUM]: Downgrade read-only deny lists to thread-local scope

**Context:** When `Policy.denyList { readOnly("/path") }` is used, `allowFsRead` returns a `Builder<ThreadLocalOnly>`, but this method discards that narrowed return value and the spec later returns the original builder as `Policy<ProcessWideSafe, ...>`. The resulting Landlock policy therefore passes the compile-time requirement of `installOnProcess` even though the scope hierarchy explicitly classifies filesystem rules as thread-local.

**Problem:**
- `denyList { readOnly(...) }` returns Builder<ThreadLocalOnly>
- Method discards narrowed return type
- Final policy has ProcessWideSafe scope
- Landlock policy passes installOnProcess check
- Thread-local filesystem rules applied process-wide

**Impact:**
- Type safety violation
- Scope hierarchy bypassed
- Thread-local restrictions incorrectly applied process-wide

**Needed:**
1. Retain the narrowed builder type from denyList operations
2. Expose this operation through a thread-local policy factory
3. Ensure scope hierarchy is respected in all policy builder methods

**AGENTS.md reference:** .agents/CODE_QUALITY.mdL5-L7

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825587185
