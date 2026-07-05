---
title: "In-Process Stacktrace Analysis ClassLoader Deadlock in Supervisor"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "unknown"
effort: "medium"
github_issue: 46
---

# 🔴 [Severity: HIGH]: In-Process Stacktrace Analysis ClassLoader Deadlock in Supervisor

**Context:** During real-time (in-process) seccomp supervision using `USER_NOTIF`, when the sandboxed JVM thread is blocked on a syscall (e.g. `openat`) during lazy classloading, it holds the JVM's internal `ClassLoader` monitor. If the supervisor validation thread attempts to resolve the stack trace (which may trigger classloading of Kotlin stdlib or policy classes) or executes user-provided scoping policy, it blocks on the same `ClassLoader` monitor. This creates a permanent circular deadlock. This differs from the Profiler, which parses `strace` logs out-of-process and is completely free from tracee-side JVM locks.
**Needed:** A daemon-side fast-path bypass is required to intercept all file reads referencing the JVM's home directory (`java.home`), the application's classpath (`java.class.path`), and JVM startup agents (e.g., Jacoco). The daemon must resolve these to canonical absolute paths and inject the file descriptor immediately without delegating to the JVM validation thread, bypassing the scoping policy for standard classes.
