---
title: Memory Segment Pooling for Profiler USER_NOTIF
severity: ENHANCEMENT
status: open
priority: 2
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt
target_modules:
- :enforcer
component: enforcer
effort: medium
---

# 🔵 [Severity: ENHANCEMENT]: Memory Segment Pooling for Profiler USER_NOTIF

**Context:** The `seccomp_notif` and `seccomp_notif_resp` structures are used for every trapped system call. Continually allocating and zeroing these segments in the `reactorLoop` is inefficient.
**Needed:** Implement a simple `SegmentPool` for fixed-size FFM structures. Pre-allocate a small cache of aligned segments and reuse them across different notifications.
