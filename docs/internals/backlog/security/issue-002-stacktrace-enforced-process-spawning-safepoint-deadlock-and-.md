---
title: "Stacktrace-Enforced Process Spawning Safepoint Deadlock and Trace Propagation Gotchas"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: HIGH]: Stacktrace-Enforced Process Spawning Safepoint Deadlock and Trace Propagation Gotchas

**Context:** During the implementation of the AI Agent sandboxing PoC, we discovered that:
1. **Empty Stack Trace on `execve` inside child processes:** When a child process is spawned via `clone` or `vfork`, it executes `execve` under its own PID. The seccomp notify event is triggered on that child PID. Because the child PID is not a registered JVM thread, calling `Thread.getStackTrace()` on it returns an empty array, making it impossible to enforce stacktrace scoping policies on `EXECVE`/`EXECVEAT` directly.
2. **ClassLoader/Safepoint Deadlocks when supervising `CLONE`:** Spawning a JVM thread calls `clone` (or `clone3`). If `clone` is supervised, stacktrace inspection forces a JVM safepoint while the JVM holds internal thread-creation locks, leading to a permanent deadlock during `Thread.start()`.

**Needed / Workaround:**
To enforce stacktrace-based scoping for process execution safely:
1. Allow `CLONE` and `CLONE3` entirely to prevent safepoint deadlocks during thread creation.
2. Force the JVM to use `vfork` or `fork` for process spawning (`-Djdk.lang.Process.launchMechanism=vfork`).
3. Supervise `VFORK` and `FORK` to capture the calling stacktrace on the parent thread before the child process is created.

**The Real (JVM-Independent) Fix:**
To enforce stacktrace-based scoping for process execution safely and portably:
1. **BPF-Level Clone Flag Inspection:** Configure the BPF program to inspect the clone flags. If `CLONE_THREAD` (value `0x00010000`) is set (indicating a Java thread creation), the BPF filter must **allow it unconditionally**, bypassing seccomp interception and avoiding safepoint deadlocks. Only intercept `clone` when `CLONE_THREAD` is absent (which signals process creation).
2. **Parent Stack Trace Capture on Spawn Entry:** Intercept `vfork`, `fork`, and non-thread `clone` calls on the parent JVM thread. The JVM validation listener captures the parent's stack trace and registers the thread TID in a global `PendingSpawnRegistry` with its authorized stack trace before allowing the syscall to continue.
3. **State-Based Propagation on Child `execve`:** When the child process ($PID_{child}$) calls `execve`/`execveat`:
   - The seccomp filter intercepts `execve`.
   - The supervisor daemon reads `PPID` from `/proc/$PID_{child}/stat` to verify it is a descendant of the JVM.
   - The JVM validation listener queries the `PendingSpawnRegistry` to find the parent thread (which is guaranteed to be suspended by the kernel in `vfork`/`clone` with `CLONE_VFORK` until the child execs).
   - The listener evaluates the child's `execve` using the pre-authorized stack trace of the blocked parent thread.
   - Once the child execs, the parent thread returns and is removed from the `PendingSpawnRegistry`.
