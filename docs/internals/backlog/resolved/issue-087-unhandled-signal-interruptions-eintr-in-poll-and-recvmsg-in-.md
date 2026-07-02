---
title: "Unhandled Signal Interruptions (`EINTR`) in `poll` and `recvmsg` in `ProfilerDaemon`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Unhandled Signal Interruptions (`EINTR`) in `poll` and `recvmsg` in `ProfilerDaemon`

**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
**Context:** The `ProfilerDaemon` uses a `poll` and `recvmsg` loop to multiplex and read incoming `USER_NOTIF` events. If an asynchronous signal interrupts these downcalls, they will fail with `EINTR`.
**Fix:** Added retry logic using the `recover` extension in `ProfilerDaemonEngine.kt` to handle `EINTR` specifically.
