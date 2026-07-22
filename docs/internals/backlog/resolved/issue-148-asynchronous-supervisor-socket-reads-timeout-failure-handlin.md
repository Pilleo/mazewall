---
title: "Asynchronous Supervisor socket reads timeout failure handling"
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
github_issue: 163
---

# 🔴 [Severity: MEDIUM]: Asynchronous Supervisor socket reads timeout failure handling

**Context:**
**Hypothesis:** The `readAndHandleJvmResponse` waits up to 1 second (`POLL_TIMEOUT_MS`) for the JVM to validate the stack trace.

If the JVM `JvmStackInspector.inspect` or `scopingPolicy.authorize` takes more than `POLL_TIMEOUT_MS` (e.g., due to garbage collection pauses or complex policy evaluation), `poll` times out. The `SupervisorSessionHandler` logs a severe error and sends an `EPERM` error via `sendSeccompError` to the tracee. Later, when the JVM finally finishes and sends the response, the supervisor receives an unexpected response or ignores it, breaking the synchronization protocol.


**Needed:**
1. Remove the arbitrary timeout for the JVM validation step. The daemon should wait indefinitely (or loop on poll) for the JVM to respond. If the JVM hangs, the tracee should hang as well. Introducing timeouts at the IPC boundary leads to desynchronization and potential subsequent failure in tracking future system calls.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt``
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
