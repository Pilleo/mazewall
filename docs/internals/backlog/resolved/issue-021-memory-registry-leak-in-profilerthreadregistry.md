---
title: "Memory Registry Leak in `Profiler.threadRegistry`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Memory Registry Leak in `Profiler.threadRegistry`

**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.Profiler`
**Context & Proof:** `Profiler.profile` registers the current thread via `threadRegistry[spid] = Thread.currentThread()`. There is no corresponding `remove` call in the `finally` block or completion callback.
**Fix:** Added a `finally` block to `Profiler.profile` to remove the TID from the registry once the workload is finished.
