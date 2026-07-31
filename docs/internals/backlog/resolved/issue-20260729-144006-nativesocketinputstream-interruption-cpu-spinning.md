---
title: "Prevent CPU spinning and infinite loops on EINTR inside NativeSocketInputStream during thread interruption"
severity: "HIGH"
status: "resolved"
priority: 8
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/NativeSocketInputStream.kt"
effort: "medium"
autonomy: "autonomous"
github_issue: 442
---

# 🔴 [Severity: HIGH]: Prevent CPU spinning and infinite loops on EINTR inside NativeSocketInputStream during thread interruption

**Context:**
The custom `NativeSocketInputStream` class manages the input stream for `ProfilerTraceListener` by wrapping native FFM `read` calls on UNIX domain sockets.

**The Bug:**
Inside the `read()` and `readWithRetry()` methods, the class retries the read operation inside `while (true)` loops if the result is a syscall error of `EINTR`:
```kotlin
    override fun read(): Int {
        while (true) {
            val res = LinuxNative.memory.read(socketFd, readBuf, 1)
            when (res) {
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value <= 0) return -1
                    return readBuf.readByte(0L).toInt() and BYTE_MASK
                }

                is LinuxNative.SyscallResult.Error -> {
                    if (res.errno == EINTR) continue
                    return -1
                }
            }
        }
    }
```
If the JVM worker or listener thread is interrupted (e.g. during profiling session shutdown, or via thread pools), the native `read` system call will repeatedly fail and return `EINTR`. Because the `while (true)` loop continues immediately on `EINTR` without checking `Thread.currentThread().isInterrupted`, the thread gets stuck in a permanent, tight high-CPU spinning loop, ignoring the interrupt and preventing clean garbage collection or thread termination.

This can result in thread leaks, high CPU utilization, or permanent lockups during profiler cleanup sequences.

**Needed:**
1. Explicitly check `Thread.currentThread().isInterrupted` at the beginning or within the retry loops of `read()` and `readWithRetry()`. If the thread is interrupted, break the loop and return `-1` (indicating EOF/failure) or propagate a `ClosedByInterruptException` to allow clean thread exits.
2. Ensure that the interrupted status of the thread is preserved (re-asserted via `Thread.currentThread().interrupt()`) if cleared.
3. Write a unit test that interrupts a thread reading from `NativeSocketInputStream` to verify that the stream exits cleanly without CPU spinning.
