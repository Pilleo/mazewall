---
title: "`IterativeProfiler` Logic Errors (Confirmed)"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "large"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: `IterativeProfiler` Logic Errors (Confirmed)

**Context:**
*   **Dimension:** State Machine Integrity & Failure Propagation
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt`
*   **Confirmed Proof:**
    1.  **Relative Paths:** `resolveAbsolutePath` explicitly returns `null` if the path does not start with `/`, causing a transition to `Failed`.
    2.  **Path Truncation:** `resolveAbsolutePath` backward scan stops at the first whitespace, truncating paths with spaces.
    3.  **Infinite Loop:** `updatePolicyForViolation` uses `path.startsWith(it.value)` which fails for disjoint prefix matches, leading to repeated denials of the same path.
    4.  **Context Loss:** Spawning a new `Thread` for each iteration loses `ThreadLocal` and MDC context, making diagnostics difficult.
*   **Needed:** Refactor `IterativeProfiler` to use a proof-of-progress state machine and proper path normalization.

**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt``
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
