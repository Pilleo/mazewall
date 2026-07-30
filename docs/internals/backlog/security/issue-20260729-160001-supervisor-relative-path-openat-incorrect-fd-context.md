---
title: "Fix Confused Deputy and Incorrect FD Context during openat / openat2 Emulation in Supervisor"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Fix Confused Deputy and Incorrect FD Context during openat / openat2 Emulation in Supervisor

**Context:**
The `SupervisorSessionHandler` intercepts system calls to perform FD emulation and injection for approved decisions (e.g. for `open`, `openat`, `openat2`).
When handling relative paths inside `openFileInSupervisor`, the system resolves the `dirfd` argument:
```kotlin
val dirfd = if (nr == arch.open || pathStr.startsWith("/")) AT_FDCWD else args[0].toInt()
```
And then invokes `openat` using the tracee's `dirfd` value:
```kotlin
engine.fileSystem.openat(dirfd, pathSeg, io.mazewall.core.OpenFlags(flags))
```

However, file descriptor integers are strictly process-private and are evaluated relative to each process's private file descriptor table. The `dirfd` (e.g., `5`) refers to a directory open in the *tracee* process's context, NOT the supervisor's process context.
When the supervisor attempts to execute `openat` using that raw `dirfd` integer, the kernel evaluates it in the supervisor's FD table. This either:
1. Fails with `EBADF` (if the supervisor does not have descriptor `5` open).
2. Or, opens the file relative to whatever file descriptor `5` represents in the supervisor process (e.g., an internal IPC socket, an irrelevant directory, etc.), causing a "Confused Deputy" / security boundary bypass.

Since `resolveAbsolutePath` already successfully resolves the fully normalized, real absolute path using `/proc/$pid/fd/$dirfd`, the supervisor has the complete, correct absolute path.

**Needed:**
1. Update `openFileInSupervisor` (and `handleInjectFd` if needed) to use the fully resolved absolute path with `AT_FDCWD` for all open-like syscalls, rather than trying to open relative to the tracee's raw `dirfd` value.
2. Ensure that the resolved path is passed cleanly from `processNotification` to the inner `handleInjectFd` and `openFileInSupervisor` methods, rather than passing the raw `pathStr`.
3. Add robust unit/integration tests that exercise `openat`/`openat2` on relative paths using a specific non-standard `dirfd` to ensure the supervisor correctly resolves and emulates the operation using the absolute path.
