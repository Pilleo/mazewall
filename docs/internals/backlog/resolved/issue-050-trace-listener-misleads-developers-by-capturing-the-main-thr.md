---
title: "Trace Listener misleads developers by capturing the Main Thread stack trace for unmapped child threads"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Trace Listener misleads developers by capturing the Main Thread stack trace for unmapped child threads

**Status:** RESOLVED (July 2026)
**Target:** `io.mazewall.profiler.internal.ProfilerTraceListener`
**Context:** A profiled workload spawns unmanaged child threads that execute trapped syscalls. When a child thread triggers a `USER_NOTIF`, the Trace Listener fails to resolve its TID to a Java `Thread` object. Previously it fell back to capturing the main worker thread's stack trace, creating highly confusing reports.
**Fix:** Removed the fallback. If a thread cannot be resolved in the `threadRegistry`, the listener records a sentinel stack trace element `"<untracked_descendant_thread>"` to maintain strict data integrity.
