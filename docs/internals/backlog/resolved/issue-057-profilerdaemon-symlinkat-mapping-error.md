---
title: "`ProfilerDaemon` `SYMLINKAT` Mapping Error"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: 080dce3c-9a1c-42f3-ab5f-f5ff2a741ecd
paperclip_identifier: MAZ-248
---

# ✅ [RESOLVED]: `ProfilerDaemon` `SYMLINKAT` Mapping Error

**Status:** RESOLVED (June 2026) — see entry above for full details.
**Target:** `io.mazewall.profiler.engine.SyscallPathResolver` (logic migrated from `ProfilerDaemon.getPathArgs`)
**Fix:** `SYMLINKAT` has its own dedicated branch in `SyscallPathResolver` with the correct `(target, newdirfd, linkpath)` argument layout. Regression-guarded by `SyscallPathResolverTest`.
