---
title: "Prevent High CPU Spinning in SupervisorSocketInputStream on Interruption"
severity: "HIGH"
status: "resolved"
priority: 8
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketInputStream.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Prevent High CPU Spinning in SupervisorSocketInputStream on Interruption

**Context:**
The `SupervisorSocketInputStream` class is a custom `InputStream` implementation in the `:enforcer` module that reads bytes from a native socket descriptor using `LinuxNative.memory.read`.
The `read()` and `read(ByteArray, Int, Int)` methods execute loops that retry on receiving `EINTR`:
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

**The Vulnerability & Concurrency Risk:**
If the calling thread gets interrupted, the native `read` syscall does not automatically raise a JVM exception. Instead, it completes, setting the thread's interrupted status flag, or returns `-1` with `errno` as `EINTR` (if interrupted by a signal).
Because `SupervisorSocketInputStream` immediately calls `continue` upon receiving `EINTR` without checking `Thread.currentThread().isInterrupted`, it can enter a tight, high-CPU spin loop under active signals or interrupts.
This is identical to the interruption vulnerability discovered in `NativeSocketInputStream` under `:profiler` (`issue-20260729_153002-native-socket-input-stream-interruption-safety.md`), but is present in the core containment `:enforcer` module.

**Needed:**
1. Enhance both `read()` and `read(ByteArray, Int, Int)` inside `SupervisorSocketInputStream.kt` to explicitly check `Thread.currentThread().isInterrupted` at the start of each loop iteration.
2. If the thread is interrupted, immediately abort, restore the interrupted status (if cleared), and throw an `InterruptedIOException` (or equivalent standard JVM I/O exception) to bubble the interruption up and prevent CPU spinning.
3. Add a small backoff yield or throttle if repeated consecutive `EINTR` returns are encountered.
4. Implement a robust unit test verifying that `SupervisorSocketInputStream` terminates cleanly with an expected exception when the calling thread is interrupted during active read loops.
