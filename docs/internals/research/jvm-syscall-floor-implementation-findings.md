# Implementation Findings: JVM Invariant Syscall Floor

This document records the technical pitfalls and requirements discovered during the initial attempt to expand the JVM invariant syscall floor (Issue #98 / Issue-075).

## 1. Networking Subsystem Requirements

The JVM's networking stack (both OIO and NIO) requires a broader set of syscalls than initially anticipated. Blocking these results in JVM internal assertion failures and immediate aborts (`SIGABRT`).

### Critical Missing Networking Syscalls:
- `getsockopt`: Used by NIO to check the status of non-blocking connection attempts and retrieve error codes.
- `setsockopt`: Required for configuring basic socket parameters (buffers, timeouts, keep-alives).
- `getsockname` / `getpeername`: Frequently used during address resolution and state verification.
- `recvfrom` / `recvmsg` / `sendto` / `sendmsg`: Essential for any actual data transfer, even if the connection is managed by NIO.

## 2. Modern JDK Coordination (Loom, GC, Handshakes)

JDK 21+ and modern GCs (ZGC, Shenandoah) utilize several newer Linux-specific primitives that are often missing from standard seccomp whitelists.

### Critical Coordination Syscalls:
- `rt_sigqueueinfo`: Used for precise signal delivery during cross-thread handshakes (replacing some uses of `futex`).
- `sched_getaffinity` / `sched_setaffinity`: Critical for Loom's `ForkJoinPool` to manage carrier thread NUMA scheduling.
- `eventfd2` / `pipe2`: Used for internal JVM signaling and "unparking" virtual threads.
- `clock_nanosleep`: Preferred timing primitive for modern `Thread.sleep()` and park/unpark logic.
- `futex_waitv`: Used by modern JDK versions for multi-futex wait scenarios (Loom).

## 3. BPF Register Garbage (64-bit Platforms)

On 64-bit architectures (AMD64, AARCH64), registers are 64 bits wide, but many syscall arguments (like `ioctl` request codes or `prctl` options) are defined as 32-bit integers.

### The Pitfall:
BPF filters that perform 64-bit equality checks (`JEQ`) on registers will often fail because the upper 32 bits may contain "garbage" (random values left over from previous operations or register reuse).

### The Solution:
The BPF generator must be hardened to perform **32-bit comparisons** for these arguments. This involves loading only the lower 32 bits (the LO word) and performing the comparison against that, ignoring the HI word entirely.

## 4. Syscall Mapping "Wiring" Failures

The `mazewall` library uses a multi-stage mapping system:
1. `Syscall` Enum
2. `SyscallMapper` (routing)
3. Specialized Mappers (`ProcessSyscallMapper`, `FsSyscallMapper`, etc.)
4. `Arch` Data Class (architecture-specific numbers)

**Finding:** Adding a syscall to the `Arch` class is insufficient. If it is not explicitly added to the `when` branches in the intermediate Mappers, `Syscall.NAME.numberFor(arch)` will fall into a default branch and return `-1`, even if the architecture constructor contains the correct value.

## 5. Brittle Unit Testing

Existing unit tests for BPF generation were highly brittle because they asserted the exact instruction sequence and register offsets.

**Finding:** Optimization of the BPF generator (e.g. removing HI-word loads for 32-bit hardening) broke these tests. Future tests should use more robust assertions that verify "property existence" (e.g. "Does the filter handle NR X?") rather than exact instruction layouts.

## 6. Architectural Test Isolation

Synthetic stress workloads like `JvmFloorWorkload` trigger significant JIT and GC activity that can affect the stability of the test runner itself.

**Finding:** These workloads should be relocated to a shared test source set (`src/sharedTest`) and run in isolated JVM processes via `IsolatedProcessTester` to prevent "poisoning" the shared Gradle worker threads.
