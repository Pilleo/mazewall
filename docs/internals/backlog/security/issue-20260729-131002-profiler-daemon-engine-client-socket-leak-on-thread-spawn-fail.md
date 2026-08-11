---
title: "ProfilerDaemonEngine client socket leak on thread spawning failure"
severity: "HIGH"
status: "open"
priority: 8
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: ProfilerDaemonEngine client socket leak on thread spawning failure

**Context:**
In `ProfilerDaemonEngine.kt`'s `handleNewConnection()`, the reactor accept loop handles new connections:
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
    } catch (e: java.nio.channels.ClosedByInterruptException) {
        ...
    } catch (e: Exception) {
        System.err.println("WARN: handleNewConnection failed: ${e.message}")
    }
}
```
If the system runs out of resources (e.g. system-wide thread limit reached, native memory exhaustion), the call `Thread { ... }.start()` will throw an unrecoverable `Throwable` such as `OutOfMemoryError` or `InternalError` (specifically not subclassing `java.lang.Exception`).

**The Bug:**
Since `catch (e: Exception)` does not catch `OutOfMemoryError` (which extends `java.lang.Error`), this throwable escapes `handleNewConnection` and crashes the main accept loop entirely (which is a severe DoS vector).
More critically, even if the error were somehow caught, the accepted `clientFd` was already added to `clientSockets`, but the socket descriptor `clientFd` is **never closed**. Because the background handler thread was never successfully spawned, `handleConnection` never runs and cannot perform socket clean-up in its `finally` block, causing a permanent file descriptor leak of the UNIX socket.

**Needed:**
1. Ensure that any client socket FD accepted in `handleNewConnection` is safely and deterministically closed if thread creation or starting fails.
2. Structure the block so that `Thread { ... }.start()` execution is guarded by a robust `try-catch` covering all `Throwable` errors, or allocate and manage client socket ownership cleanly using a try-with-resources equivalent.
3. Write a unit test simulating native thread starvation to confirm that the accepted file descriptor is closed and the daemon remains resilient or fails closed safely.
