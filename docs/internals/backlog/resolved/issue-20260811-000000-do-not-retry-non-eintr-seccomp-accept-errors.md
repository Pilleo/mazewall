---
title: "Do Not Retry Non-EINTR Seccomp Accept Errors With a Blocking Accept"
severity: "MEDIUM"
status: "resolved"
priority: 7
dependencies: []
component: "enforcer"
target_modules:
  - ":platform"
  - ":enforcer"
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngineTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Do Not Retry Non-EINTR Seccomp Accept Errors With a Blocking Accept

**Context:** The seccomp daemon translated a non-`EINTR` `accept4` error into an unconditional call to `SocketManager.accept`. The real socket manager performs another blocking `accept4`, so the daemon could remain blocked indefinitely when no later client connected and could not complete global shutdown or listener cleanup.

**Needed:** Retry only `EINTR`. Return from connection handling after any other `accept4` error without issuing another accept, and retain regression coverage that verifies the blocking fallback is never invoked.

**Resolution:** `SeccompDaemonEngine.handleNewConnection` now returns after non-`EINTR` accept errors. The regression test fault-injects `EINTR` followed by another error and verifies that the socket manager's blocking accept operation is not called.
