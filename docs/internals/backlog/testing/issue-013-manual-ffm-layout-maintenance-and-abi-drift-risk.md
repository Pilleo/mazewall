---
title: Automated FFM layout ABI validation across supported architectures
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
paperclip_issue_id: "f9aee2f5-272c-45b4-a920-5351b271df91"
paperclip_identifier: "MAZ-1391"
---

# 🔴 [Severity: MEDIUM]: Automated FFM layout ABI validation across supported architectures

**Context:** `Layouts.kt` contains hand-coded `MemoryLayout` definitions for critical kernel structures (e.g., `sock_fprog`, `seccomp_data`, `landlock_ruleset_attr`). While `LayoutValidator` performs runtime alignment checks, it does not guarantee that the offsets match the actual target architecture's ABI if they differ (e.g., padding rules between x86_64 and AArch64).
**Needed:** Implement one canonical ABI validation strategy for the layouts in `Layouts.kt`.
1. Prefer a test-time native-header oracle that verifies offsets and sizes for every supported architecture.
2. If generated bindings are selected instead, keep architecture-specific artifacts behind the existing `Arch.current()` boundary.
3. This issue is the canonical record for both the former MAZ-1391 and duplicate MAZ-755 concerns.

**Merged duplicate:** MAZ-755 is superseded by this canonical issue; its separate record is retained
in `resolved/` only as a redirect for historical traceability.
