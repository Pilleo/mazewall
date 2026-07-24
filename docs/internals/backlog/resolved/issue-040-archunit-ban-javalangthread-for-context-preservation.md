---
title: "ArchUnit: Ban `java.lang.Thread` for Context Preservation"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "unknown"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 245
---

# 🔴 [Severity: MEDIUM]: ArchUnit: Ban `java.lang.Thread` for Context Preservation

**Context:**
Direct usage of `java.lang.Thread` or standard `Executors` ignores `mazewall`'s thread-local containment states and structured concurrency requirements, leading to "context leaks" where sandboxed tasks execute unconstrained.


**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Unknown`
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
