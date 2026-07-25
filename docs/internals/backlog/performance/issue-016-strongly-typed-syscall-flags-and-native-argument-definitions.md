---
title: "Strongly Typed Syscall Flags and Native Argument Definitions"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
target_files: []
target_modules: [\":enforcer\"]
component: "enforcer"
effort: "medium"
github_issue: 309
---

# 🔵 [Severity: ENHANCEMENT]: Strongly Typed Syscall Flags and Native Argument Definitions

**Context:** Many `NativeEngine` methods use raw `Int` or `Long` for flags (e.g., `open(path, flags)`, `mmap(..., prot, flags)`). This is prone to transposition bugs where a flag from one syscall is accidentally passed to another.
**Needed:** Introduce specialized value classes or enums for common bitmasks.
1. Define `OpenFlags`, `MmapProt`, `MmapFlags`, `CloneFlags`, etc.
2. Update the `NativeEngine` trait to use these types instead of raw primitives.
3. Refine `RealNativeHelper.toLong` to handle these typed wrappers.
