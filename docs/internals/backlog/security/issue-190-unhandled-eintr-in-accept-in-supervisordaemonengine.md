---
title: "Unhandled EINTR in accept in SupervisorDaemonEngine"
severity: "MEDIUM"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Unhandled EINTR in accept in SupervisorDaemonEngine
**Context:** `LinuxNative.withTransaction { LinuxNative.networking.accept(...) }` in `handleNewConnection` of `SupervisorDaemonEngine.kt:134` does not check for or recover from `NativeConstants.EINTR`. If the supervisor daemon receives a signal during the `accept` call, it will silently fail the `SyscallResult.Success` cast, dropping the connection instead of retrying.
**Needed:** Use `.recover` to explicitly handle `EINTR` and loop/retry the `accept` call if interrupted.