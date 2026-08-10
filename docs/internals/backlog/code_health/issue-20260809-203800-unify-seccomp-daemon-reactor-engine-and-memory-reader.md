---
title: "Unify Seccomp Daemon Reactor Engine and Tracee Memory Reader in :platform"
severity: "MEDIUM"
status: "resolved"
priority: 8
dependencies: []
component: "enforcer"
target_modules:
  - ":platform"
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerMemoryReader.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Unify Seccomp Daemon Reactor Engine and Tracee Memory Reader in :platform

**Context:**
The out-of-process seccomp user notification (`SECCOMP_RET_USER_NOTIF`) architectures in `:enforcer` (Supervisor) and `:profiler` (Profiler) share substantial duplicate infrastructure that currently lives in separate modules:
1. `SupervisorDaemonEngine` and `ProfilerDaemonEngine` duplicate UNIX domain socket creation, client accept loops, connection limit enforcement (`MAX_CONNECTIONS = 200`), and `SCM_RIGHTS` listener FD handshake protocol (`0xAC` ACK byte).
2. `SupervisorSessionHandler` and `ProfilerSessionHandler` duplicate the 2-element `pollfd` reactor loop (`listenerFd` + `socketFd`), polling timeout mechanics, and control byte protocol (`0x53` Shutdown, `0x54` Pass-Through).
3. `SupervisorProcessMemoryReader` and `ProfilerMemoryReader` duplicate tracee virtual memory reading logic (`/proc/$pid/mem` vs `process_vm_readv`), null-terminated path extraction, and Yama `ptrace_scope` error handling.

Now that `:platform` has been extracted as a shared foundational module, this code duplication can be unified to improve maintainability, testability, and type safety across both subprojects.

**Needed:**
1. Extract a unified `SeccompDaemonEngine` and `SeccompSessionHandler` abstraction into `:platform` (`io.mazewall.platform.seccomp.daemon`).
2. Allow `:enforcer` and `:profiler` to reuse this shared reactor loop by providing custom `SeccompNotifHandler` strategies for notification processing.
3. Consolidate tracee memory reading into a unified `TraceeMemoryReader` within `:platform` (`io.mazewall.ffi.memory`).
4. Update `:enforcer` and `:profiler` engines to consume the shared `:platform` components and verify that all integration tests pass cleanly.
