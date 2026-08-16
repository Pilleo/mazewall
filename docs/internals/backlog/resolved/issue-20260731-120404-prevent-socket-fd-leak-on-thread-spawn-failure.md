---
title: "Prevent Socket FD Leak in ProfilerDaemonEngine on Connection Thread Spawn Failure"
severity: "HIGH"
status: "resolved"
priority: 8
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt"
effort: "small"
autonomy: "autonomous"
github_issue: 462
---

# 🔴 [Severity: HIGH]: Prevent Socket FD Leak in ProfilerDaemonEngine on Connection Thread Spawn Failure

**Context:**
In `ProfilerDaemonEngine.kt`'s `handleNewConnection`, the accepted client socket is added to `clientSockets`, and a new OS thread is spawned to handle the connection:
```kotlin
    private fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        try {
            val clientFd = socketManager.accept(serverFd)
            clientSockets.add(clientFd)
            Thread { handleConnection(clientFd) }.apply {
                name = "conn-handler-${clientFd.value}"
                start()
            }
        } catch (e: InterruptedException) {
            ...
        } catch (e: Exception) {
            ...
        }
    }
```
If `Thread.start()` fails (e.g., throwing an `OutOfMemoryError` due to thread limits or virtual memory exhaustion), the exception is caught by the `catch (e: Exception)` block. However, the accepted `clientFd` is already in `clientSockets` in the open state, and is **never closed or cleaned up**! This results in a permanent file descriptor leak of the socket. Under resource constraints or connection spikes, this can quickly exhaust the system's open file limit and crash the daemon.

**Needed:**
1. Wrap the thread instantiation and start block inside a nested `try-catch` block inside `handleNewConnection`.
2. If any `Throwable` (including OOM and OutOfMemoryError) occurs after `socketManager.accept()` but before the handling thread starts successfully, ensure the accepted `clientFd` is removed from `clientSockets` and closed cleanly:
   ```kotlin
   var clientFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>? = null
   try {
       clientFd = socketManager.accept(serverFd)
       clientSockets.add(clientFd)
       Thread { handleConnection(clientFd) }.apply {
           name = "conn-handler-${clientFd.value}"
           start()
       }
   } catch (t: Throwable) {
       System.err.println("WARN: Failed to start connection handler thread: ${t.message}")
       if (clientFd != null) {
           clientSockets.remove(clientFd)
           try { socketManager.close(clientFd) } catch (_: Exception) {}
       }
       if (t is Error) throw t
   }
   ```
3. Implement a unit test in `ProfilerDaemonEngineTest.kt` that triggers thread-spawn simulation failure and asserts that the accepted file descriptor is closed and not leaked.

**Verification/Regression Tests:**
- Run `./gradlew :profiler:test` to verify that existing connection handlers still function correctly and close sockets cleanly.
