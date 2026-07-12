---
title: "Missing domain logic handling of SyscallResult in SupervisorDaemonEngine"
severity: "MEDIUM"
status: "resolved"
priority: 6
dependencies: []
component: "enforcer"
effort: "medium"
github_issue: 92
---

# 🔴 [Severity: MEDIUM]: Missing domain logic handling of SyscallResult in SupervisorDaemonEngine
**Context:** ArchUnit test `domainLogicMustHandleSyscallResults` will fail if raw `SyscallResult` types are used within the `io.mazewall.seccomp` or `io.mazewall.landlock` packages without handling `recover`, `map` or `fold`. However, this requirement is broadly missed in `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngine.kt` as well. For instance, when it processes the seccomp ACK buffer in `handleConnection`: `val ackBuf = arena.allocate(ACK_BUF_SIZE); ackBuf.writeByte(0L, PROTOCOL_ACK_BYTE); LinuxNative.withTransaction { LinuxNative.memory.write(socketFd, ackBuf, ACK_BUF_SIZE) }`. The result is entirely ignored. This ignores `EINTR` failures, resulting in a dropped handshake and deadlocks the JVM child thread.
**Needed:** Fix the result in `SupervisorDaemonEngine.processConnectionStep` so it loops when receiving `EINTR` and returns null if a real error occurs, using similar logic to `SupervisorSocketUtils.sendDescriptor`.
