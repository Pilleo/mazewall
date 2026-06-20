# Supervisor Proxy Pattern (FD Injection)

> [!IMPORTANT]
> **Status:** Designed (Not Yet Implemented)
> This document details the architectural design for a future enhancement. The Supervisor Proxy component does not yet exist in the codebase.

## 1. Background & Motivation

The current thread-scoped containment model in `mazewall` relies on kernel-side filtering (Seccomp-BPF) and path restrictions (Landlock LSM). While highly performant and secure for standard use cases, this model has strict limitations:
*   **No Context-Awareness:** BPF cannot inspect the JVM stack trace to make authorization decisions based on the *calling function*.
*   **Static Pathing:** Landlock cannot easily restrict dynamically generated temporary paths or strictly validate network targets (e.g., DNS resolution) without complex userspace synchronization.
*   **Confused Deputy Vulnerabilities:** Relying solely on userspace string parsing for file access is vulnerable to Time-Of-Check to Time-Of-Use (TOCTTOU) symlink attacks.

The **Supervisor Proxy Pattern** shifts complex, context-aware authorization from the kernel to a trusted out-of-process **Supervisor Daemon** (similar to the profiler's Tier S sidecar process) using Seccomp's `USER_NOTIF` feature and File Descriptor (FD) Injection (`SECCOMP_IOCTL_NOTIF_ADDFD`). This upgrades `mazewall` from a static syscall blocker to an intelligent, application-layer security gateway.

---

## 2. The Architecture: Fast-Path vs. Slow-Path

To maintain the high performance required of a JVM sandboxing library, we do not intercept every system call. We use a hybrid approach:

*   **The Fast-Path (BPF/Landlock):** 99% of high-volume I/O operations (`read`, `write`, standard `openat` within Landlock allowed paths) are handled directly by the kernel via `ACT_ALLOW`. Performance overhead is nanoseconds.
*   **The Slow-Path (Supervisor Daemon):** Rare, sensitive operations (e.g., `execve` for process spawning, `connect` for outbound networking, or privileged `openat` calls outside standard scopes) are mapped in BPF to `ACT_NOTIFY` (`SECCOMP_RET_USER_NOTIF`).

### Execution Flow
1.  Untrusted thread executes a sensitive syscall (e.g., `execve`).
2.  Kernel BPF filter catches it, pauses the thread, and alerts the Supervisor Daemon (running in a separate OS process via a Unix domain socket connection established using FFM and `sendmsg` for `SCM_RIGHTS`).
3.  Supervisor Daemon validates the request (see Authorization below).
4.  If approved, the Supervisor Daemon takes action based on the syscall type:
    *   **Value-only arguments:** Instructs the kernel to resume the syscall (`SECCOMP_USER_NOTIF_FLAG_CONTINUE`).
    *   **Pointer arguments:** MUST execute the operation itself and inject the resulting FD/return value via `SECCOMP_IOCTL_NOTIF_ADDFD` to prevent TOCTTOU attacks (never use `FLAG_CONTINUE` here).
5.  If denied, the Supervisor Daemon instructs the kernel to spoof an `EPERM` or `EACCES` failure.

---

## 3. Stacktrace Scoping (Context-Aware Auth)

The most powerful capability unlocked by the Supervisor Daemon is "Stacktrace Scoping." Because the Supervisor Daemon runs out-of-process to avoid GC safepoint deadlocks, it coordinates with the parent JVM process to inspect thread states. The Supervisor Daemon blocks the worker thread in-kernel, alerts the JVM process, and waits for a round-trip acknowledgment. Meanwhile, a trace listener thread within the JVM process safely and stably captures the blocked worker's Java stack trace via timing-safe `Thread.getStackTrace()` while the worker thread is suspended in kernel-space.

**Example Use Case:** A connection pool (like HikariCP) needs to establish outbound connections to a database.
*   **Legitimate Request:** The Supervisor Daemon intercepts `connect()`. The stack trace reveals `com.zaxxer.hikari.pool.HikariPool.createPoolEntry()`. The Supervisor Daemon authorizes the injection.
*   **Malicious Request:** An SSRF vulnerability is exploited in a web controller. The stack trace reveals `com.example.controller.WebhookController.fetchImage() -> java.net.Socket.connect()`. The Supervisor Daemon denies the request.

---

## 4. Mitigating the Confused Deputy Problem

The Supervisor Daemon is a highly privileged deputy acting on behalf of an untrusted client. This introduces severe security risks that must be mitigated.

### A. Preventing Stacktrace Spoofing
If an attacker can spoof the JVM stack trace, the Supervisor Daemon's primary authorization mechanism is compromised.
*   **Gadget Chains Betray Themselves:** Standard deserialization exploits (e.g., Jackson, XStream) require complex reflection chains to reach sensitive APIs. These reflection artifacts (`sun.reflect.NativeMethodAccessorImpl`) will be blatantly obvious in the stack trace and will automatically fail strict whitelists.
*   **Tier 1 Memory Protection (The Ultimate Backstop):** The only way to reliably spoof a stack trace without leaving reflection artifacts is to achieve Arbitrary Code Execution (ACE) and manually rewrite the JVM's internal thread structures in memory. `mazewall`'s mandatory **Tier 1 Process-Wide Baseline** uses `mprotect` restrictions (`NO_EXEC` / `W^X`) to prevent native shellcode execution. Without ACE, stacktrace spoofing is mathematically infeasible.

### B. Preventing TOCTTOU & Path Traversal (FD Injection)
When injecting File Descriptors, the Supervisor Daemon must never trust string paths provided by the untrusted thread. Userspace path canonicalization is vulnerable to symlink race conditions.
*   **`openat2` with `RESOLVE_BENEATH`:** The Supervisor Daemon must exclusively use the Linux 5.6+ `openat2` syscall with the `RESOLVE_BENEATH` flag. (Refer to the `KernelFeatureMatrix` in [containment_design.md](file:///home/leanid/Documents/code/java/jseccomp/docs/internals/containment_design.md) for kernel feature probing details). The Supervisor Daemon opens a safe "Root FD" (e.g., `/tmp/safe_zone/`). When processing a request, it calls `openat2(root_fd, requested_path, RESOLVE_BENEATH)`. 
*   **Kernel Enforcement:** If the `requested_path` contains `../` or absolute symlinks that attempt to escape the `root_fd` directory, the kernel guarantees an instant `EXDEV` failure. This provides race-free, kernel-enforced path confinement, ensuring the Supervisor Daemon cannot be tricked into opening host files like `/etc/shadow`.
### C. Out-of-Process Isolation as a Confused Deputy Defense
While the out-of-process architecture was primarily chosen to prevent JVM-wide GC safepoint deadlocks, it also provides key structural defenses against the Confused Deputy problem:
*   **Memory & State Isolation:** An attacker who achieves Arbitrary Code Execution (ACE) on the sandboxed JVM thread cannot corrupt the Supervisor Daemon's JVM/native heap memory. The daemon's internal state, authorization rules, stack trace whitelists, and cached root directory FDs remain physically inaccessible.
*   **FD Table Isolation:** The sandboxed thread has no access to the Supervisor Daemon's file descriptor table, preventing attacks that rely on manipulating or closing the supervisor's socket/files.
*   **Double-Sandboxing Bounds:** The Supervisor Daemon runs under its own independent, strict Landlock policy. Even if a bug in the daemon allows it to be tricked into performing unauthorized operations, its absolute execution envelope is restricted at the kernel level, mitigating the blast radius.
*   **Limits on Defense:** This architectural isolation only protects the supervisor's *integrity*. It does not prevent semantic-level confused deputy vulnerabilities, such as a developer configuring overly broad stack trace whitelists or logic errors within the JVM authorization callbacks.

---

## 5. Limitations & Known Attack Vectors

While the Supervisor Proxy is powerful, it introduces several complex edge cases that must be mitigated:

### A. The `USER_NOTIF` Pointer Argument TOCTTOU
Using `SECCOMP_USER_NOTIF_FLAG_CONTINUE` is unsafe for any system call that uses memory pointers for its arguments (like `connect`, `openat`, `execve`). A malicious thread could mutate the pointer data in memory *after* the Supervisor Daemon validates it, but *before* the kernel resumes the syscall.
*   **Mitigation:** For any syscall with pointer arguments, the Supervisor Daemon MUST copy the data, validate it, execute the syscall itself, and inject the resulting FD. `FLAG_CONTINUE` is strictly reserved for syscalls where all arguments are passed by value in registers.
*   **Note on Profiling vs. Enforcement:** While `FLAG_CONTINUE` is unacceptable for pointer arguments in an *enforcement* posture, it is acceptable in the *profiler's* (Tier S) posture, as the profiler only seeks to log the events and does not enforce security boundaries during observation.

### B. Virtual Thread (Loom) Carrier Pinning & Exhaustion (DoS)
When a thread triggers a `SECCOMP_RET_USER_NOTIF` trap, the kernel physically blocks the OS thread. Because this happens outside standard Java I/O APIs, the JVM does not know to unmount a Virtual Thread. The underlying OS Carrier Thread is pinned.
*   **Mitigation:** If an attacker spawns 10,000 virtual threads that all trap to the Supervisor Daemon, they will instantly exhaust the ForkJoinPool carrier threads, deadlocking the JVM. The Supervisor Daemon must implement strict rate-limiting, fast-fail mechanisms (e.g., thread-specific timeout bounds and maximum queue sizes of 100 pending notifications per carrier), and refuse to register untrusted virtual threads.

### C. Supervisor Resource Starvation (DoS)
A malicious thread can execute a tight `while(true)` loop calling an arbitrary blocked syscall. This floods the Supervisor Daemon's `USER_NOTIF` queue, maxing out the Supervisor Daemon's CPU and denying service to legitimate threads.
*   **Mitigation:** Implement a backpressure mechanism or rate limit per TID. If a thread exceeds the trap threshold (e.g., 50 traps/second), the Supervisor Daemon should immediately inject an `EPERM` without performing expensive stack walks or validations.

### D. In-Process Safepoint Deadlock (Why In-Process Fails)
If the Supervisor was run as an in-process thread, a JVM-wide Garbage Collection safepoint would pause the supervisor thread while a worker thread was blocked in the kernel awaiting a seccomp decision, leading to a permanent process-wide deadlock.
*   **Mitigation:** Run the Supervisor Daemon as a completely separate OS sidecar process. Its JVM safepoints are physically isolated from the main JVM, preventing GC safepoint deadlocks.

### E. Stack Walking Safepoint Latency
Relying on thread stack extraction to authorize requests is an expensive operation that may induce JVM-wide safepoints, adding severe and unpredictable latency spikes to network/file I/O.
*   **Mitigation:** To minimize safepoint latency and avoid JVM thread walkers during active GC sweeps, we restrict stack capture to timing-safe `Thread.getStackTrace()` while the worker thread is already stably suspended in kernel-space. Note that `StackWalker` is not suitable here because it can only walk the caller's own stack and cannot walk another thread's stack dynamically.

### F. FD Leakage & GC Relocation Race
When the Supervisor Daemon injects a File Descriptor into the target process, a race condition exists: the untrusted thread might crash or be interrupted *before* the Java wrapper (`java.io.FileDescriptor`) is created and registered with the JVM's Garbage Collector / Cleaner. If GC runs during this window, the raw OS FD will leak.
*   **Mitigation:** The architecture must track injected FDs via an ephemeral registry. If a thread fails to acknowledge receipt and wrap the FD within a short timeout window, the Supervisor Daemon must force-close the orphaned FD on the target process's behalf using `pidfd_getfd` or remote close coordination.