---
title: "Fix Jules Session ID Parsing Bug"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Fix Jules Session ID Parsing Bug

**Context:**
**Context:**
The `listSessions()` method in `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/JulesCli.kt` splits the CLI output by multiple spaces and checks if the first token is a valid Long (`id.toLongOrNull() != null`). Jules session IDs (e.g., `14927969181089226847`) exceed the maximum value of a 64-bit signed integer (`Long.MAX_VALUE`), causing `toLongOrNull()` to evaluate to `null` and drop the session from the list.

**Needed:**
Replace the Long conversion check in `JulesCli.kt` with a string-based digit check or use `ULong`/`BigInteger`:
```kotlin
// Change this:
if (id.toLongOrNull() != null) { ... }

// To this:
if (id.all { it.isDigit() }) { ... }
```

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
