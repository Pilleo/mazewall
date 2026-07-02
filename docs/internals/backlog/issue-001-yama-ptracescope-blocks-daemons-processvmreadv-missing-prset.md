---
title: "Yama `ptrace_scope` Blocks Daemon's `process_vm_readv` (Missing `PR_SET_PTRACER`)"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: HIGH]: Yama `ptrace_scope` Blocks Daemon's `process_vm_readv` (Missing `PR_SET_PTRACER`)

**Context:** When the test worker JVM spawns the supervisor daemon via `ProcessBuilder`, the daemon is born as a child process. By default, Linux Yama `ptrace_scope=1` restricts `ptrace` (and thus `process_vm_readv`) such that only ancestors can trace descendants. Because the daemon is a descendant of the test worker JVM, its attempts to read string arguments (e.g. `pathStr` for `SYS_OPENAT`) from the test worker JVM threads using `process_vm_readv` are denied with `EPERM`.
**Symptoms:** 
1. The daemon fails to extract the path and silently falls back to `-EPERM` for `handleInjectFd`.
2. This causes seccomp to return `EPERM` to the tracee JVM thread.
3. The tracee throws `java.io.FileNotFoundException (Operation not permitted)` during application file IO, or `NoClassDefFoundError` if the blocked read occurs during internal JVM class loading (e.g., when trying to load exception handlers).
**Needed:** The parent test worker JVM MUST invoke `prctl(PR_SET_PTRACER, daemonPid)` immediately after spawning the daemon process to explicitly grant the child daemon permission to read the parent's memory under restricted ptrace scopes. Fixed in `SupervisorDaemonManager.kt`.
