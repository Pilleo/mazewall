---
title: "Unhandled EINTR in `SupervisorSocketUtils.sendDescriptor`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Unhandled EINTR in `SupervisorSocketUtils.sendDescriptor`

*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt`
*   **Context & Proof:** `sendmsg` can be interrupted by a signal, returning `EINTR`. If not handled, descriptor passing will spuriously fail.
*   **Fix:** Wrapped the `sendmsg` call inside `SupervisorSocketUtils.sendDescriptor` in a retry loop on `EINTR` to guarantee file descriptors are passed correctly.
