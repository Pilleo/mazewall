---
title: "Unhandled `SyscallResult` during Shutdown"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Unhandled `SyscallResult` during Shutdown

*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt`
*   **Context & Proof:** In `triggerDaemonShutdown`, `LinuxNative.networking.connect` is executed, and if successful, `write` is called. However, it blindly ignores potential `EINTR` or `ECONNREFUSED` (which could mean the daemon is already shutting down or socket is busy).
*   **Fix:** Wrapped the `connect` and `write` system calls in retry loops that catch `EINTR` to guarantee delivery of the daemon shutdown byte.
