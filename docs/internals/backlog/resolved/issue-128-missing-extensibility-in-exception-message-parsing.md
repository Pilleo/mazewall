---
title: "🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing"
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
---

# 🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing

**Context:**
**Hypothesis:** The `ContainmentViolationDetector` is a singleton with a static list of matchers. Users cannot easily add custom detection logic for third-party libraries that wrap IOExceptions with non-standard messages.

While `registerMatcher` exists, its interaction with global state (`MATCHERS`) might be problematic in complex, multi-tenant classloaders (e.g., OSGi or certain App Servers).


**Needed:**
1. Allow passing an optional configuration or extending the detector via a ServiceLoader pattern for better modularity.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/enforcer/ContainmentViolationDetector.kt``
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
