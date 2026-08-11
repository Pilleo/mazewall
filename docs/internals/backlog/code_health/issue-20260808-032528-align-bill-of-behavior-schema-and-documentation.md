---
title: "Align BillOfBehavior Domain Schema and Documentation"
severity: "LOW"
status: "open"
priority: 7
dependencies:
  - "issue-20260808-032527"
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingResult.kt"
  - "profiler/README.md"
effort: "medium"
autonomy: "autonomous"
---

# 🟢 [Severity: LOW]: Align BillOfBehavior Domain Schema and Documentation

**Context:** The profiler README advertises `behavior.networkEndpoints`, but the current `BillOfBehavior` data class exposes opens, write paths, syscalls, execs and an engine-level `TraceEvent` stack map without that property. Raw strings and engine event types also leak collection internals into the behavioral contract.

**Needed:** Define typed, versioned domain values for paths, executions, syscall observations and any genuinely captured network observations. Make Kotlin properties, JSON schema and README examples agree. Add golden serialization/round-trip tests and compile documentation snippets so nonexistent fields or incompatible schema changes fail CI.
