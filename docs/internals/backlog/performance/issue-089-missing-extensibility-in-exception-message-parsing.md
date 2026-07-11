---
title: "🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing"
severity: "HIGH"
status: "open"
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
**Hypothesis:** Different JVM languages, native wrappers, or custom `FileSystemProvider` implementations might throw exceptions containing localized error strings or unusual formatting when access is denied. The `DENIED_PHRASES` list in `ContainmentViolationDetector` is hardcoded.

`ContainmentViolationDetector` uses a fixed `arrayOf` strings (e.g., `"Operation not permitted"`, `"refusé"`). The `IterativeProfiler` uses this exact array to identify exception boundaries. If a user's framework throws a custom wrapper containing "Blocked by sandbox", the violation is completely ignored.


**Needed:**
1. Provide a public configuration hook in `IterativeProfiler` or `Policy` allowing developers to supply custom regexes or phrases for violation detection.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.profiler.iterative.IterativeProfiler` and `io.mazewall.enforcer.ContainmentViolationDetector``
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
