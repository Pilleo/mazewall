---
title: "Symbolic Errno Mapping in `SyscallResult`"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: Symbolic Errno Mapping in `SyscallResult`

**Context:** When a syscall fails, `SyscallResult.Error` only provides the raw `Int` errno. Seeing `errno=1` is less helpful than `EPERM`.
**Needed:** Implement a symbolic mapping for POSIX error numbers.
1. Add a utility to map common `Int` errnos to their symbolic names (e.g., `1 -> "EPERM"`, `13 -> "EACCES"`).
2. Update `SyscallResult.Error.toString()` and `throwErrno()` to include this symbolic name for better developer feedback.
