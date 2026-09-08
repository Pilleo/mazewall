---
title: "ArchUnit: Enforce `Errno` Capture Locality Wrapper"
severity: "ENHANCEMENT"
status: "resolved"
priority: low
dependencies: []
component: "ffi"
effort: "medium"
github_issue: 280
paperclip_issue_id: 2037cec9-3ceb-4dfc-9fbb-0d68a304f12c
paperclip_identifier: MAZ-225
---

# 🔵 [Severity: ENHANCEMENT]: ArchUnit: Enforce `Errno` Capture Locality Wrapper

**Target:** `io.mazewall.ffi` and `io.mazewall.NativeEngine`
**Context:** `AGENTS.md` explicitly warns that `errno` must be read *immediately* after an FFM downcall, or it will be overwritten by the JVM.
**Needed:** Use ArchUnit to ban direct calls to FFM `MethodHandle.invokeExact()` anywhere outside a dedicated `SyscallInvoker` utility, ensuring that the downcall and the subsequent `errno` capture are always atomically bound together.
