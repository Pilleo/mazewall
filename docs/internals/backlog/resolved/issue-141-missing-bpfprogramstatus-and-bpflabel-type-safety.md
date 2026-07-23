---
title: "Missing `BpfProgram<Status>` and `BpfLabel` Type-Safety"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 169
---

# 🔴 [Severity: MEDIUM]: Missing `BpfProgram<Status>` and `BpfLabel` Type-Safety

**Context:**
**Hypothesis:** The `BpfProgram.builder()` uses a builder pattern, but it might lack compile-time guarantees (phantom types) to ensure that `checkArch` is called *before* `loadSyscallNr`, or that `build()` is only called when the program is in a complete state.

If a developer modifies `BpfFilter.kt` and accidentally reorders the builder calls, the resulting BPF program might be structurally invalid (e.g., trying to load arguments before checking the architecture), leading to kernel rejection (`EINVAL`) only at runtime.


**Needed:**
1. Leverage Kotlin's type system (e.g., Phantom Types or a Type-State pattern) to enforce the builder sequence at compile time, matching the architectural constraints outlined in the design documents.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
