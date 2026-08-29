---
title: "Memory Registry Leak in `Profiler.threadRegistry`"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: 0ef7da7e-8312-4d05-a1dc-771366bdc62d
paperclip_identifier: MAZ-219
---

# ✅ [RESOLVED]: Memory Registry Leak in `Profiler.threadRegistry`

**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.Profiler`
**Context & Proof:** `Profiler.profile` registers the current thread via `threadRegistry[spid] = Thread.currentThread()`. There is no corresponding `remove` call in the `finally` block or completion callback.
**Fix:** Added a `finally` block to `Profiler.profile` to remove the TID from the registry once the workload is finished.
