---
title: Manual FFM Layout Maintenance and ABI Drift Risk
severity: HIGH
status: open
priority: low
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/ffi/Layouts.kt
target_modules:
- :enforcer
component: enforcer
effort: medium
paperclip_issue_id: "b204d9d3-82d0-4d6b-a0c5-ef503a3c9f7e"
paperclip_identifier: "MAZ-1118"
---

# 🔴 [Severity: MEDIUM]: Manual FFM Layout Maintenance and ABI Drift Risk

**Context:** `Layouts.kt` contains hand-coded `MemoryLayout` definitions for critical kernel structures (e.g., `sock_fprog`, `seccomp_data`, `landlock_ruleset_attr`). While `LayoutValidator` performs runtime alignment checks, it does not guarantee that the offsets match the actual target architecture's ABI if they differ (e.g., padding rules between x86_64 and AArch64).
**Needed:** Implement a robust validation or generation strategy.
1. Use `jextract` as a test-time "oracle" to verify that `Layouts.kt` offsets match the ground-truth C headers for all supported architectures.
2. Alternatively, generate separate architecture-specific layouts and switch them at runtime via `Arch.current()`.
