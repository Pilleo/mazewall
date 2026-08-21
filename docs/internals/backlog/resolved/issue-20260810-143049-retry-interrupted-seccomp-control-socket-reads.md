---
title: "Retry Interrupted Seccomp Control-Socket Reads"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":platform"
  - ":enforcer"
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SeccompSessionHandlerTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟠 [Severity: MEDIUM]: Retry Interrupted Seccomp Control-Socket Reads

**Context:** A signal could interrupt the seccomp daemon's control-socket `read` with `EINTR`. The session handler treated every read error as evidence that the parent was dead, terminated the session, and allowed cleanup to close an otherwise healthy seccomp listener.

**Needed:** Retry control-socket reads when the native result reports `EINTR`, while continuing to terminate on other read errors. Verify through a fault-injected test that an interrupted read followed by a successful command read leaves the session active.

**Resolution:** The control-socket read now retries only `EINTR`. A regression test verifies two read attempts, continued reactor processing, and an unterminated session.
