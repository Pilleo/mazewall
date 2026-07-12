---
title: "TOCTOU / Pointer Re-targeting via `sockaddrBytes` Mutation during Connect Validation"
severity: "CRITICAL"
status: "open"
priority: 5
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
---


# 🔴 [Severity: CRITICAL]: TOCTOU / Pointer Re-targeting via `sockaddrBytes` Mutation during Connect Validation

*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** Similar to the path-based TOCTOU, the supervisor reads `sockaddrBytes` from the target process, validates it in the JVM, and then uses `connectSocketInSupervisor` to perform the `connect()` syscall from the supervisor process.
*   **Context & Proof:** `connectSocketInSupervisor` uses the `sockaddrBytes` array that was read from the tracee's memory earlier. An attacker in the tracee could swap the memory contents of the `sockaddr` struct (e.g., changing the IP from a harmless destination to an internal, protected service on loopback) between the time the supervisor reads it and the time the supervisor performs the `connect`. The supervisor, acting as a confused deputy, connects to the malicious destination and returns the connected socket FD to the tracee.
*   **Recommendation:** Stop injecting FDs for `connect()` and `open()` based on user-space copies. If the scoping policy authorizes the action, simply use `SECCOMP_USER_NOTIF_FLAG_CONTINUE` so the kernel safely evaluates the syscall in the tracee using the final memory state.

## Solution Options

### Option A
(To be filled)

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ]

**Implementation Hints:**
-
