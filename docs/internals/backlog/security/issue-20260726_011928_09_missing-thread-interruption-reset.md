---
title: SupervisorSocketUtils swallows Thread.sleep InterruptedException without setting interrupt status
type: issue
status: open
priority: medium
labels: ["security", "enforcer", "deadlock"]
component: enforcer
target_modules: [":enforcer"]
target_files: ["io.mazewall.ffi.networking.SupervisorSocketUtils.kt"]
---

# Issue: `SupervisorSocketUtils` Swallows `InterruptedException`

## Context
`SupervisorSocketUtils.kt` is responsible for setting up the UNIX socket for the daemon communication. It contains loops with `Thread.sleep` to wait for the socket file or connection.

## The Bug
When `Thread.sleep` throws an `InterruptedException`, the catch block might be swallowing it without calling `Thread.currentThread().interrupt()`. If a graceful shutdown is triggered while the daemon manager is trying to establish a socket connection, the interrupt flag is cleared, and the thread will not exit its loop (or subsequent checks for `isInterrupted` will fail), causing a hang during shutdown.

## Recommendation
Check all instances of `Thread.sleep` in `SupervisorSocketUtils.kt` and `SupervisorDaemonManager.kt` to ensure `InterruptedException` properly re-asserts the thread interrupt status via `Thread.currentThread().interrupt()`.
