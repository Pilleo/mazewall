---
title: "`IterativeProfiler` Path Truncation on Spaces"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: `IterativeProfiler` Path Truncation on Spaces

**Context:**
**Hypothesis:** When a profiled workload is denied access to a file whose absolute path contains spaces (e.g. `/var/log/my file.txt`), the `IterativeProfiler` incorrectly truncates the path at the first whitespace when parsing the exception message, returning an invalid path and failing to whitelist the correct resource.

In `IterativeProfiler.findPathEnd`, the backwards scan loop continues while `end >= 0 && (msg[end].isWhitespace() || msg[end] == '(')`. This strips trailing spaces. Then, `resolveAbsolutePath` scans backwards until it hits `!msg[start - 1].isWhitespace()`. This means that any spaces *within* the path itself will act as boundary markers, prematurely ending the path resolution. The profiler then attempts to whitelist the truncated snippet, leaving the actual file blocked.


**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `findPathEnd`)`
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
