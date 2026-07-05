---
title: "ArchUnit Bypass: Swallowed SyscallResult in SupervisorSessionHandler"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "unknown"
effort: "medium"
---

# 🔴 [Severity: HIGH]: ArchUnit Bypass: Swallowed SyscallResult in SupervisorSessionHandler
**Context:** `SupervisorSessionHandler` explicitly swallows `SyscallResult` inside `withTransaction` blocks by appending `; Unit` after `LinuxNative.fileSystem.close(...)` at line 651, and `LinuxNative.ioctl(..., SECCOMP_IOCTL_NOTIF_SEND, ...)` at line 678. This bypasses ArchUnit validations and silently masks kernel errors if a target thread terminates during a syscall, violating the monadic result pattern described in `architectural_map.md`.
**Needed:** Remove `; Unit` and explicitly handle the `SyscallResult` using `.recover` to log or manage the `errno`.