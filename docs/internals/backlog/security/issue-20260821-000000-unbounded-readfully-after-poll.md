---
title: "Unbounded readFully after poll deadline"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRT_kwDOScnnEM6avSh2
---

# 🔴 [Severity: P1]: Unbounded readFully after poll deadline

**Context:** When the JVM validation peer sends only part of a response and then stalls without closing the socket, the preceding poll deadline ends as soon as the first byte is readable and this blocking `readFully` waits forever for the remainder. The daemon handler and intercepted tracee thread then remain parked permanently, bypassing the timeout specifically intended to prevent validation deadlocks.

**Problem:**
- `SupervisorSessionHandler.kt:477` - After poll indicates data is available, `readFully` blocks indefinitely
- Daemon and tracee thread remain parked
- Validation deadlock timeout is bypassed

**Impact:**
- Denial of service: thread hangs indefinitely
- Security: supervised syscall handling can be deadlocked

**Needed:**
1. Poll each remaining read against the original deadline or configure a bounded receive timeout.

**Notes:** Per user instructions: "Leave open on GitHub (do not expand this patch) - Unbounded readFully after poll."
