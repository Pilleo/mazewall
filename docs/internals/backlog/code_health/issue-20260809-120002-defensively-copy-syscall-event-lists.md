---
title: "Defensively copy SyscallEvent list inputs"
severity: "MEDIUM"
status: "open"
priority: 7
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/SyscallEvent.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/SyscallEventTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟠 [Severity: MEDIUM]: Defensively copy SyscallEvent list inputs

**Context:** `SyscallEvent` claims that captured arguments and paths are immutable, but its public constructor stores caller-provided `List` references directly, and `resolved` also stores the supplied paths directly. A caller can pass a `MutableList` and mutate an event after it has been inserted into a hash-based collection, changing both equality and hash code. This reintroduces the state-mutation risk that the resolved `LongArray` issue intended to remove.

**Needed:** Establish immutable snapshots at the event boundary without making a breaking API change. Use a construction strategy that copies `args`, `paths`, and non-null `stackTrace`, and make `resolved` snapshot its input. Add regression tests that construct events from mutable lists, mutate the originals, and verify the event values, equality, and hash-based lookup remain stable. Run `./gradlew :profiler:test` and `./gradlew :profiler:check`.
