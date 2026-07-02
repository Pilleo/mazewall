---
title: "Classloader Lock Deadlock in `JVMValidationListener` under `StacktraceScopingPolicy`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Classloader Lock Deadlock in `JVMValidationListener` under `StacktraceScopingPolicy`

**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.enforcer.supervisor.JVMValidationListener.runValidationReactor`
**Context & Proof:** When a sandboxed thread ($T_1$) issued an `openat` syscall during lazy JVM class loading, it held the JVM `ClassLoader` monitor. The seccomp supervisor blocked $T_1$ in kernel space. The supervisor validation thread ($S_1$) then attempted to execute Kotlin stdlib helpers (`toList`, `find` with lambdas) to convert the stack trace and resolve the syscall enum — but those helpers were themselves not yet loaded, requiring classloading. $S_1$ blocked waiting for the `ClassLoader` lock that $T_1$ held. Permanent deadlock.
**Root Cause:** The tracee JVM thread was suspended while holding a ClassLoader lock, while the listener thread tried to evaluate the scoping policy, triggering class loading of stdlib or policy classes on the same loader.
**Fix:** The initial stacktrace-based `isClassloaderActive` check was insecure (malicious code could bypass seccomp verification by triggering lazy class loading during exploits) and fragile. It was removed and replaced with a path-based Daemon-Side Fast-Path in `SupervisorSessionHandler`. The daemon intercepts all `SYS_OPEN`, `SYS_OPENAT`, and `SYS_OPENAT2` calls, normalizes the target path to an absolute path, and matches it against the JDK home directory (`java.home`). If it resides within `java.home`, the daemon directly opens and injects the FD, bypassing JVM-side policy evaluation completely and safely preventing lock contention during standard runtime classloading.
