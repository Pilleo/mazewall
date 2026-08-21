---
title: "Keep the validation deadline while reading the full frame"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819861587
---

# 🔴 [Severity: HIGH]: Keep the validation deadline while reading the full frame

**Context:** When the JVM validation peer sends only part of a response and then stalls without closing the socket, the preceding poll deadline ends as soon as the first byte is readable and this blocking `readFully` waits forever for the remainder. The daemon handler and intercepted tracee thread then remain parked permanently, bypassing the timeout specifically intended to prevent validation deadlocks.

**Problem:**
- SupervisorSessionHandler.kt:477 - Poll deadline ends prematurely
- readFully blocks indefinitely on partial response
- Daemon handler and tracee thread remain parked
- Validation deadlock timeout is bypassed

**Impact:**
- Denial of service: thread hangs indefinitely
- Security: supervised syscall handling can be deadlocked

**Needed:**
1. Poll each remaining read against the original deadline
2. Or configure a bounded receive timeout
3. Ensure partial reads don't bypass validation timeout

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861587
