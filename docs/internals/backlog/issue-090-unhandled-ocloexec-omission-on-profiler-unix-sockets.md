---
title: "Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets

*   **Dimension:** OS Invariants
*   **Target Area:** `io.mazewall.profiler.internal.ProfilerSocket`
*   **Failure Hypothesis:** The `ProfilerSocket` creates a `socket(AF_UNIX, SOCK_STREAM, 0)`. It does not apply the `O_CLOEXEC` (Close-on-Exec) flag. If the profiled JVM spawns a child process (e.g. via `ProcessBuilder`) while the profiler connection is open, the child process inherits the open socket file descriptor to the Profiler Daemon.
*   **Context & Proof:** `ProfilerSocket.kt` makes the raw Linux `socket` downcall. Because `SOCK_CLOEXEC` is not bitwise OR'd into the socket type, the descriptor remains open across `execve`. Although the Tier 2 policy might block `execve`, if a user allows `execve` (or uses Tier S process-wide profiling without blocking `execve`), child processes will unknowingly hold a reference to the daemon socket.
*   **Cascading Risk Potential:** Medium. File descriptor leak to untrusted child processes, potentially allowing children to write spoofed `USER_NOTIF` ACKs or keep the daemon connection alive indefinitely, preventing cleanup.
*   **Recommendation:** Always bitwise OR `NativeConstants.SOCK_CLOEXEC` into the `type` argument when calling `LinuxNative.socket`.
