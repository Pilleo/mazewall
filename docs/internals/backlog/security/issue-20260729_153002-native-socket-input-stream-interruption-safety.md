---
title: "Enhance NativeSocketInputStream with Interruption Checking and Backoff Throttling to Prevent CPU Spinning"
severity: "HIGH"
status: "open"
priority: 8
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/NativeSocketInputStream.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Enhance NativeSocketInputStream with Interruption Checking and Backoff Throttling to Prevent CPU Spinning

**Context:**
The `NativeSocketInputStream` class is a custom `InputStream` implementation that reads bytes from a native socket descriptor using `LinuxNative.memory.read`.
The `read()` and `readWithRetry()` methods both execute a `while (true)` loop:
```kotlin
    private fun readWithRetry(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val count = Math.min(len.toLong(), BUFFER_SIZE.toLong())
        while (true) {
            val res = LinuxNative.memory.read(socketFd, multiBuf, count)
            when (res) {
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value <= 0) return -1
                    val actualLen = res.value.toInt()
                    ManagedSegment.copy(multiBuf, 0L, b, off, actualLen)
                    return actualLen
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
If the background worker thread of `ProfilerTraceListener` gets interrupted (for example, via `it.interrupt()` inside `close()` or `passThrough()`), the JVM thread state receives the interrupt status flag.
However, native calls to `LinuxNative.memory.read()` do not automatically throw `InterruptedException` when interrupted; they either set the thread's interrupt status and complete, or return `-1` with `errno` as `EINTR` (if a native signal interrupted the syscall).

Because `NativeSocketInputStream` immediately calls `continue` upon receiving `EINTR` without ever checking `Thread.currentThread().isInterrupted`, the thread can enter a high-CPU, tight spinning loop under active signal/interruption spikes. Furthermore, if the socket remains open, the worker thread may block/spin indefinitely, preventing clean exits, leaking JVM thread resources, and causing thread pool starvation.

**Needed:**
1. Enhance both `read()` and `readWithRetry()` inside `NativeSocketInputStream.kt` to explicitly check `Thread.currentThread().isInterrupted` at the beginning of each loop iteration.
2. If the thread is interrupted, immediately exit the loop, restore the interrupted status (if cleared), and throw an `InterruptedIOException` (or equivalent standard I/O exception) to cleanly abort the read sequence and bubble the error up to `ProfilerTraceListener`.
3. Add a small backoff yield or check if repeated `EINTR` calls are received consecutively to mitigate high-CPU spinning.
4. Implement a robust unit test verifying that `NativeSocketInputStream` terminates cleanly with an expected exception when the calling thread is interrupted during active read loops.
