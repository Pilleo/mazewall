---
title: "Generic Type Safety for `MemorySegment` Payloads"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Generic Type Safety for `MemorySegment` Payloads

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.NativeEngine` and `io.mazewall.profiler.engine`
**Context:** Native interfaces blindly accept untyped `MemorySegment` objects. This allows a developer to pass a segment initialized with the wrong layout (e.g., passing a `sockaddr` to a `poll` call), leading to memory corruption or kernel rejections.
**Fix:** Introduced `ManagedSegment` (Confined/Shared) in `io.mazewall.ffi.memory` to ensure type and scope safety.
