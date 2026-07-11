---
title: "`IterativeProfiler` infinite retry loop and failure on disjoint prefix file paths"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: `IterativeProfiler` infinite retry loop and failure on disjoint prefix file paths

**Context:**
**Hypothesis:** The `IterativeProfiler` checks if read is already allowed using a naive string `startsWith` check. If the workload accesses a path whose prefix matches an already allowed path but is a different, longer directory name (e.g., `/var/log-extra` when `/var/log` is allowed), the check falsely returns `true`. The profiler then attempts to add a *write* rule instead of a *read* rule, causing subsequent read attempts to continue failing and forcing the profiler into an infinite discovery retry loop that aborts after 20 retries.

If `currentPolicy` allowed read to `/var/log`, and a trapped read occurs on `/var/log-extra`, `isCurrentlyReadAllowed` evaluates to `true` (since `"/var/log-extra".startsWith("/var/log")` is true). So `updatePolicyForViolation` executes the `then` branch: `if (isCurrentlyReadAllowed) { builder.allowFsWrite(path) }`. Thus, it adds a write rule for `/var/log-extra` but NEVER adds a read rule! On the next retry, the thread tries to read `/var/log-extra` again, gets denied, and the same logic is executed. This continues until the retry count hits `maxRetries` (20), at which point the profiler crashes.


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
