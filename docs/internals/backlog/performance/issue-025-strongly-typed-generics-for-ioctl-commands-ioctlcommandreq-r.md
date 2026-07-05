---
title: "Strongly-Typed Generics for `ioctl` Commands (`IoctlCommand<Req, Res>`)"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "ffi"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Strongly-Typed Generics for `ioctl` Commands (`IoctlCommand<Req, Res>`)

**Target:** `io.mazewall.NativeEngine` and `io.mazewall.ffi`
**Context:** The backlog notes that `ioctl` fallback crashes happen because arguments are highly polymorphic and easy to misalign when reading memory.
**Needed:** Define `class IoctlCommand<Req : StructLayout, Res : StructLayout>(val code: Long)`. `NativeEngine.ioctl` would use these generics, ensuring the request/response payload structs strictly match the `ioctl` command code at compile time.
