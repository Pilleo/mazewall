---
title: "ProfilerTraceListener Lacks Deterministic Lifecycle (AutoCloseable)"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: 52724f69-ee7d-4a55-a6c4-a7e9fbee8af1
paperclip_identifier: MAZ-228
---

# ✅ [RESOLVED]: ProfilerTraceListener Lacks Deterministic Lifecycle (AutoCloseable)

**Status:** RESOLVED (June 2026)
**Context:** `ProfilerTraceListener` starts a background thread and reads from a socket. Currently, there is no standard way to signal shutdown or join the thread, leading to potential leaks or "half-dead" listeners during profiler restarts.
**Fix:** `ProfilerTraceListener` now implements `AutoCloseable` and ensures proper cleanup.
