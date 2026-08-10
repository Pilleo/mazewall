---
title: "Prevent global Supervisor daemon shutdown upon single session termination to support multi-connection resilience"
severity: "MEDIUM"
status: "resolved"
priority: 7
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngine.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: MEDIUM]: Prevent global Supervisor daemon shutdown upon single session termination to support multi-connection resilience

**Context:**
The `SupervisorDaemonEngine` is designed as a multi-client reactor. It maintains `MAX_CONNECTIONS = 200` and processes new accepted connection handshakes asynchronously using a fixed thread pool. Each active session is then handled inside `handleSession`.

**The Issue:**
Inside `SupervisorDaemonEngine.kt`'s `handleSession()` loop:
```kotlin
                    NativeArena.ofConfined().use { iterationArena ->
                        val action = with(iterationArena) {
                            sessionHandler.handleActiveListener(pollFds, notif, resp)
                        }
                        if (action is LoopAction.Break || action is LoopAction.Shutdown) {
                            triggerGlobalShutdown("session reactor break")
                        }
                    }
```
If `action` is `LoopAction.Break` or `LoopAction.Shutdown` (which occurs when the JVM tracee exits, closes its socket, or the seccomp listener FD is closed), the engine invokes `triggerGlobalShutdown("session reactor break")`. This immediately transitions the daemon's state to `ShuttingDown` and terminates the entire daemon engine process!

While a single-use profiling companion can exit upon session completion, `SupervisorDaemonEngine`'s multi-client accepting architecture (the 200 thread pool) expects to support multiple concurrent connection sessions. Terminating the entire daemon when any single connection is closed gracefully or closed by an error makes the daemon extremely fragile, completely defeating the purpose of the multithreaded accept loop.

This behaves significantly worse than `ProfilerDaemonEngine.kt`, which correctly exits only the current session thread while leaving the main daemon engine running to handle other/future connection sessions:
```kotlin
                        NativeArena.ofConfined().use { iterationArena ->
                            val action = with(iterationArena) {
                                sessionHandler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)
                            }
                            if (action !is LoopAction.Continue) break
                        }
```

**Needed:**
1. Refactor the session termination in `SupervisorDaemonEngine.kt` to only clean up and exit the current thread's connection session (similar to `ProfilerDaemonEngine.kt`), rather than calling `triggerGlobalShutdown` which shuts down the entire daemon.
2. Global shutdown should only be triggered if explicitly requested by a shutdown command on a control channel, or when the daemon process is directly terminated/interrupted (e.g. via stdin EOF or SIGTERM).
3. Update relevant tests to verify that the daemon continues accepting new connections after a previous client session disconnects.
