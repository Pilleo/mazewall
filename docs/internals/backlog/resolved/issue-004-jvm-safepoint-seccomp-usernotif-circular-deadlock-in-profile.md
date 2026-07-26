---
title: "JVM Safepoint / seccomp USER_NOTIF Circular Deadlock in `ProfilerTraceListener`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: JVM Safepoint / seccomp USER_NOTIF Circular Deadlock in `ProfilerTraceListener`

**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.internal.ProfilerTraceListener.readNextEvent` and `processEvent`
**Context & Root Cause:** When `ProfilerTraceListener` received a seccomp `USER_NOTIF` event for a profiled thread (e.g., an `openat` call from a worker), the thread was suspended in kernel space awaiting the `SECCOMP_USER_NOTIF_FLAG_CONTINUE` response from the daemon. The daemon delivers that response only after receiving an ACK byte from the trace-listener. However, the trace-listener was calling `Thread.getStackTrace()` on the profiled thread *before* sending the ACK — inside `readNextEvent()`. `Thread.getStackTrace()` requires the JVM to stop the target thread at a safepoint. A thread blocked in the kernel (blocked on a syscall, not yet returned to user space) cannot reach a safepoint until it returns from the kernel. It cannot return from the kernel until the seccomp response arrives. The response cannot arrive until the ACK is sent. The ACK cannot be sent because the trace-listener is waiting for the safepoint. **Circular deadlock, no timeout, permanent hang.**
**Fix:** Removed the inline `Thread.getStackTrace()` call from `readNextEvent()` entirely. Reordered `processEvent()` to call `sendAckIfNecessary()` first (releasing the kernel-blocked thread), then `accumulateStackTrace()` (which calls `Thread.getStackTrace()` after the thread is free to reach a safepoint). The captured stack trace remains diagnostically accurate: after returning from the syscall, the thread is still executing within the same high-level call chain (e.g., `File.readText()` → the profiled lambda), so the stack frames reflect the code that triggered the syscall.
