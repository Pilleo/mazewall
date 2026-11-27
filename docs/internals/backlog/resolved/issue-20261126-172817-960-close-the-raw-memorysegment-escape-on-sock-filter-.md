---
title: "Close the raw MemorySegment escape on sock_filter so a widened k does not compile"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":platform", ":enforcer"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/memory/MemoryWrappers.kt"
target_symbols:
  - "SockFilterSegment"
open_questions: false
---

# 🟡 [Severity: HIGH]: Close the raw MemorySegment escape on sock_filter so a widened k does not compile

**Context:**
SockFilterSegment.setK already takes Int, but the public segment property still accepts MemorySegment.set(JAVA_LONG, kOffset, ...). FFM's setter takes a separate ValueLayout, so Layouts.kt and the ffm_safety checklist cannot stop a wrong-width write. Make the typed wrapper the only write path, then the width rule is a compiler error and the checklist can go.

**Needed:**
1. Add a failing test that production code cannot write sock_filter.k through a raw JAVA_LONG MemorySegment.set. Hide or narrow SockFilterSegment.segment so only the typed setters and the native downcall boundary can touch the bytes.

## Resolution — 2026-11-26

The FFM packer owns the writable array and validates the instruction limit.
Each instruction uses the width-specific setters. Installation receives a
read-only program header, with the array and header in the same arena.

JDK 25 compilation accepted the Int setter and rejected the raw segment write,
the managed alias, the raw array factory, and the Long setter. The automated
visibility, two-instruction byte encoding, size-limit, and C ABI tests passed.
[Containment design](../../designs/enforcer/containment-design.md#sock_filter-8-bytes)

---
<!-- id: issue-20261126-172817-960-close-the-raw-memorysegment-escape-on-sock-filter-  file: issue-20261126-172817-960-close-the-raw-memorysegment-escape-on-sock-filter-.md -->
