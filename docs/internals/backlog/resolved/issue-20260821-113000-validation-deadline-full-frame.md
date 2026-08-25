---
title: "Keep the validation deadline while reading the full frame"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "platform/src/main/kotlin/io/mazewall/core/SocketIo.kt"
effort: "medium"
autonomy: "supervised"
related_pr: 512
related_thread: 3819861587
---

# 🔴 [Severity: HIGH]: Keep the validation deadline while reading the full frame

**Review (2026-08-21):** Still present. Duplicate `issue-20260821-000000-unbounded-readfully-after-poll` is closed; fix only this file.

**Review (2026-08-21, later):** Resolved. `SocketIo.readFully` requires a monotonic `Deadline` and `RawSyscallOperations`; it polls remaining time before each `read`. Expired or `poll==0` is `ETIMEDOUT`. `readAndHandleJvmResponse` shares one deadline across the wait-poll and the frame read, and closes the socket on timeout.

**Current tree:** `requestJvmValidation` polls the control socket with a shrinking `remainingTimeout`. When poll reports readable, it then calls `SocketIo.readFully(..., SUPERVISOR_RESPONSE_SIZE)` with **no** remaining deadline. `readFully` loops until `total` bytes or a non-`EINTR` error / zero-length read. If the JVM peer writes one byte and stalls with the socket still open, this blocks forever. The USER_NOTIF tracee stays in the kernel.

**Do not:**
- Raise `POLL_TIMEOUT_MS` or add `Thread.sleep`. That does not bound the post-poll read.
- Catch the hang and continue the syscall (`CONTINUE` / ALLOW). Fail closed: `sendSeccompError(EPERM)` (or equivalent deny).
- Put a timeout only on the first `read(2)` and leave the rest unbounded.

**Do:**
1. Keep the original deadline through the entire frame (poll each remaining read, or pass remaining millis into `readFully`).
2. On deadline: close the socket, deny the notification, return. Do not leave the daemon parked.
3. Partial frames are a protocol error, not a retry-forever condition.

**Tests:** Mock `NativeMemory.read` that returns 1 byte then never completes; assert the handler returns within the poll budget and sends an error response, not CONTINUE.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861587
