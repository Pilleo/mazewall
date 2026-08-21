---
title: "Residual Interface Segregation Violation (ISP) in `NativeEngine`"
severity: "HIGH"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
effort: "large"
target_modules: [":enforcer"]
target_files: ["enforcer/src/main/kotlin/io/mazewall/NativeEngine.kt"]
---

# 🔴 [Severity: MEDIUM]: Residual Interface Segregation Violation (ISP) in `NativeEngine`

**Target:** `io.mazewall.NativeEngine`
**Context:** While sub-engines (FileSystem, Networking) were extracted, the main `NativeEngine` interface still exposes low-level, unconstrained `syscall`, `ioctl`, and `poll` methods. Any component requiring the engine for simple file operations is unnecessarily exposed to raw syscall capabilities.
**Needed:** Segregate raw syscall operations into a separate `RawSyscallOperations` interface, ensuring higher-level components only depend on restricted, domain-specific traits.
