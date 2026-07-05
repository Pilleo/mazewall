---
title: "Memory Segment Pooling for Profiler USER_NOTIF"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "ffi"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Memory Segment Pooling for Profiler USER_NOTIF

**Context:** The `seccomp_notif` and `seccomp_notif_resp` structures are used for every trapped system call. Continually allocating and zeroing these segments in the `reactorLoop` is inefficient.
**Needed:** Implement a simple `SegmentPool` for fixed-size FFM structures. Pre-allocate a small cache of aligned segments and reuse them across different notifications.
