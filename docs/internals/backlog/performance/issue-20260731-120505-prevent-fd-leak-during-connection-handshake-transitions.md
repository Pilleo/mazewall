---
title: "Prevent FD Leak during Connection Handshake Transitions in ProfilerDaemonEngine"
severity: "HIGH"
status: "open"
priority: 8
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Prevent FD Leak during Connection Handshake Transitions in ProfilerDaemonEngine

**Context:**
In `ProfilerDaemonEngine.kt`'s `handleConnection`, the `finally` block of the connection handler is responsible for closing open file descriptors:
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
However, once `connection` transitions from `FdAttached` to `Active`, the `connection is SeccompConnection.FdAttached` check evaluates to `false`. If an exception occurs *after* transitioning to `Active` but *before* the session reactor loop in `handleSession` successfully enters its `try` block, the seccomp `listenerFd` is leaked. This is because `handleSession` relies on its own `finally` block to close `listenerFd`, but if it is never successfully entered or executed, `listenerFd` remains open forever.

**Needed:**
1. Refactor the `finally` block in `handleConnection` to cleanly close `listenerFd` if the connection has been established to either `FdAttached` or `Active` and the session reactor did not enter successfully or has crashed:
   ```kotlin
   } finally {
       clientSockets.remove(socketFd)
       socketManager.close(socketFd)

       val lFd = when (val c = connection) {
           is io.mazewall.ffi.networking.SeccompConnection.FdAttached -> c.listenerFd
           is io.mazewall.ffi.networking.SeccompConnection.Active -> c.listenerFd
           else -> null
       }
       // If the activeListeners list still contains lFd, it means handleSession's finally block has not removed/closed it, so we must clean it up here
       if (lFd != null && activeListeners.contains(lFd)) {
           activeListeners.remove(lFd)
           try { socketManager.close(lFd) } catch (_: Exception) {}
       }
   }
   ```
2. Ensure that any handshake exceptions (e.g. timeout on sending/receiving, socket close, or interrupted exceptions) are thoroughly cleaned up.

**Verification/Regression Tests:**
- Validate using mock connections that interrupting the handler after FD attachment but prior to session reactor execution successfully cleans up both socket and listener FDs.
- Run `./gradlew :profiler:test` to ensure connection handler handshakes continue working correctly.
