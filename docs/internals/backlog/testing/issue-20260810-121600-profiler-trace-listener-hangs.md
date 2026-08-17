---
title: "ProfilerTraceListener Hangs Due to ACK Buffer Size Mismatch"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
  - ":platform"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerConstants.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: ProfilerTraceListener Hangs Due to ACK Buffer Size Mismatch

**Context:**
The test `test wrap() executor shutdown waits for pending tasks and avoids ENOSYS` in `ProfilerIntegrationTest` hangs indefinitely during the build.
The root cause is a mismatch in the expected `ACK_BUF_SIZE` during the initial handshake between the daemon and the JVM listener. 

When the `SupervisorSeccompNotifInstaller` connects to the daemon and sends the listener FD, the daemon's `SeccompDaemonEngine.processConnectionStep` sends a handshake ACK (`PROTOCOL_ACK_BYTE`) back to the JVM. In `SeccompDaemonEngine.kt`, `ACK_BUF_SIZE` is hardcoded to `1L`, so it sends exactly 1 byte (`0xAC`).

However, the profiler's `HandshakeSession.kt` reads ACKs using the `ACK_BUF_SIZE` defined in `ProfilerConstants.kt`, which was recently increased to `4L` to allow reading `ACK` and `SHUTDOWN` bytes concurrently. Because `ProfilerTraceListener` uses a `DataInputStream` wrapping a `BufferedInputStream`, the mismatch itself doesn't cause `dis.readByte()` to hang (it successfully reads the 1 byte). 

Wait, if it reads 1 byte, why does it hang?
Wait, the `ProfilerConstants.ACK_BUF_SIZE = 4L` is used by the DAEMON to read ACKs from the JVM!
If the DAEMON uses `ACK_BUF_SIZE = 4L` to read ACKs in `HandshakeSession.kt` (`ioOps.read(socketFd, ackBuf, ACK_BUF_SIZE)`), but the JVM's `sendAck()` only sends 1 byte!
Ah! The `read` syscall blocks if it expects 4 bytes but the sender only sends 1 byte?
No, `read()` on a Unix socket will return short if data is available! It will return 1 byte immediately.
So why does it hang?
Wait, if it reads 1 byte, `read` returns 1. The loop in `readAndProcessAck` processes that 1 byte correctly.

Let's re-verify the hang! 
If `latch.await()` in the test hangs, it's because `latch.countDown()` in the submitted task never executes.
The submitted task never executes because the `pool` thread is stuck in `installProfilingFilterForThread()`.
`installProfilingFilterForThread()` is stuck at `proceedLatch.await()` because `readyLatch.countDown()` is never called in `runListenerLoop()`.
Why is `readyLatch.countDown()` never called?
Because `val handshakeAck = dis.readByte()` throws an Exception or blocks forever!
If it blocks forever, the daemon never sent the 1 byte `handshakeAckByte`.
Why did the daemon never send it?
Because the daemon is DEAD or `SeccompDaemonEngine.processConnectionStep` failed!
If the daemon is DEAD, it's because it was killed by an earlier test's `@AfterAll` calling `Profiler.shutdown()`?
Wait! `Profiler.shutdown()` DOES NOT kill the daemon!
But wait, `SupervisorProxyIntegrationTest` calls `SupervisorDaemonManager.getInstance().stop()` which DOES kill the daemon!
If `SupervisorProxyIntegrationTest` ran before `ProfilerIntegrationTest`, it killed the DAEMON PROCESS spawned by `SupervisorDaemonManager`.
BUT `ProfilerIntegrationTest` uses `daemonManagerProvider().getOrSpawnSharedDaemon()`!
Wait! `Profiler.kt` uses `ProfilerDaemonManager`.
Wait... let me check `Profiler.kt`!

This issue needs further refinement, but the hang is clearly caused by the `ProfilerTraceListener`'s initialization being deadlocked due to daemon unavailability or communication breakdown.

**Needed:**
1. Determine why `dis.readByte()` in `ProfilerTraceListener.runListenerLoop()` hangs during `test wrap() executor shutdown waits for pending tasks and avoids ENOSYS`.
2. Ensure `ProfilerDaemonManager` and `SupervisorDaemonManager` do not prematurely terminate shared daemon processes required by subsequent tests.
3. Verify if test classes need to be run in isolated JVM forks to prevent global state pollution (both for Seccomp filters and shared daemon processes).
