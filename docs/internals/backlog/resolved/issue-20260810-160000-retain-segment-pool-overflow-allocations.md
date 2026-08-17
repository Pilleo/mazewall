---
title: "Retain SegmentPool Overflow Allocations"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/memory/SegmentPool.kt"
  - "platform/src/test/kotlin/io/mazewall/ffi/memory/SegmentPoolTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Retain SegmentPool Overflow Allocations

**Context:** `SegmentPool` allocates overflow segments from a process-lifetime shared arena when concurrent rentals exceed its preallocated size. Release previously discarded overflow references once the fixed-size queue refilled. Because the shared arena cannot free individual allocations, repeated concurrency waves permanently consumed additional native memory without making those allocations reusable.

**Needed:** Retain every correctly sized returned segment so the pool grows to the observed peak concurrency and reuses those native allocations in subsequent waves. Preserve the configured pool size as the initial preallocation count and add regression coverage proving that an overflow segment is rented again after release.

**Resolution:** `release` now returns all correctly sized segments to the concurrent queue. The regression test verifies identity reuse for the initially pooled segment and its overflow allocation.
