---
title: "ProfilerTraceListener Lacks Deterministic Lifecycle (AutoCloseable)"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: ProfilerTraceListener Lacks Deterministic Lifecycle (AutoCloseable)

**Status:** RESOLVED (June 2026)
**Context:** `ProfilerTraceListener` starts a background thread and reads from a socket. Currently, there is no standard way to signal shutdown or join the thread, leading to potential leaks or "half-dead" listeners during profiler restarts.
**Fix:** `ProfilerTraceListener` now implements `AutoCloseable` and ensures proper cleanup.
