---
title: "ProfilerDaemonEngine seccomp listener FD double-close mitigation"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: ProfilerDaemonEngine seccomp listener FD double-close mitigation

**Context:**
Inside `ProfilerDaemonEngine.kt`, when a session ends or fails, the seccomp listener FD (`listenerFd`) is closed in `handleSession`'s `finally` block:
```kotlin
} finally {
    activeListeners.remove(listenerFd)
    socketManager.close(listenerFd)
}
```
If a connection terminates unexpectedly, `handleConnection`'s `finally` block also performs cleanup:
```kotlin
} finally {
    clientSockets.remove(socketFd)
    socketManager.close(socketFd)
    if (connection is io.mazewall.ffi.networking.SeccompConnection.FdAttached) {
        val lFd = connection.listenerFd
        activeListeners.remove(lFd)
        socketManager.close(lFd)
    }
}
```

**The Vulnerability:**
In a highly concurrent system, if the state is desynchronized or if both `finally` blocks are executed (due to asynchronous exceptions, interrupts, or shutdown signals triggering simultaneously), the same `listenerFd` could be closed twice.
This exposes the daemon to a severe **double-close** race condition. If between the first and second `close()` calls another thread opens a completely unrelated file descriptor (such as a database connection, network socket, or transaction log file) and receives the recycled file descriptor number, the secondary, redundant `close()` will silently close that unrelated file descriptor. This results in hard-to-diagnose silent data corruption, broken network connections, or unauthorized file access.

**Needed:**
1. Refactor file descriptor management in `ProfilerDaemonEngine.kt` to prevent double-closing.
2. Ensure that any `close()` operation on `FileDescriptor` is safe, idempotent, and performs state mutation atomically.
3. Wrap file descriptors in a thread-safe closeable representation (like `AtomicBoolean` guarded close handlers or checking if the FD remains registered inside `activeListeners` before invoking `socketManager.close()`), guaranteeing that the underlying native close downcall is executed exactly once.
4. Add a unit test verifying that redundant close calls do not execute the underlying system close more than once.
