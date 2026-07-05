---
title: "ArchUnit: Enforce `Errno` Capture Locality Wrapper"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "ffi"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: ArchUnit: Enforce `Errno` Capture Locality Wrapper

**Target:** `io.mazewall.ffi` and `io.mazewall.NativeEngine`
**Context:** `AGENTS.md` explicitly warns that `errno` must be read *immediately* after an FFM downcall, or it will be overwritten by the JVM.
**Needed:** Use ArchUnit to ban direct calls to FFM `MethodHandle.invokeExact()` anywhere outside a dedicated `SyscallInvoker` utility, ensuring that the downcall and the subsequent `errno` capture are always atomically bound together.
