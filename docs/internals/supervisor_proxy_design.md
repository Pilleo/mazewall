# Supervisor Proxy Pattern (FD Injection)

## 1. Background & Motivation

The current thread-scoped containment model in `mazewall` relies on kernel-side filtering (Seccomp-BPF) and path restrictions (Landlock LSM). While highly performant and secure for standard use cases, this model has strict limitations:
*   **No Context-Awareness:** BPF cannot inspect the JVM stack trace to make authorization decisions based on the *calling function*.
*   **Static Pathing:** Landlock cannot easily restrict dynamically generated temporary paths or strictly validate network targets (e.g., DNS resolution) without complex userspace synchronization.
*   **Confused Deputy Vulnerabilities:** Relying solely on userspace string parsing for file access is vulnerable to Time-Of-Check to Time-Of-Use (TOCTTOU) symlink attacks.

The **Supervisor Proxy Pattern** shifts complex, context-aware authorization from the kernel to a trusted JVM thread using Seccomp's `USER_NOTIF` feature and File Descriptor (FD) Injection (`SECCOMP_IOCTL_NOTIF_ADDFD`). This upgrades `mazewall` from a static syscall blocker to an intelligent, application-layer security gateway.

---

## 2. The Architecture: Fast-Path vs. Slow-Path

To maintain the high performance required of a JVM sandboxing library, we do not intercept every system call. We use a hybrid approach:

*   **The Fast-Path (BPF/Landlock):** 99% of high-volume I/O operations (`read`, `write`, standard `openat` within Landlock allowed paths) are handled directly by the kernel via `ACT_ALLOW`. Performance overhead is nanoseconds.
*   **The Slow-Path (Supervisor Proxy):** Rare, sensitive operations (e.g., `execve` for process spawning, `connect` for outbound networking, or privileged `openat` calls outside standard scopes) are mapped in BPF to `ACT_NOTIFY` (`SECCOMP_RET_USER_NOTIF`).

### Execution Flow
1.  Untrusted thread executes a sensitive syscall (e.g., `execve`).
2.  Kernel BPF filter catches it, pauses the thread, and alerts the Supervisor daemon.
3.  Supervisor validates the request (see Authorization below).
4.  If approved, Supervisor instructs the kernel to resume the syscall (`SECCOMP_USER_NOTIF_FLAG_CONTINUE`), or it executes the operation itself and injects the resulting FD.
5.  If denied, Supervisor instructs the kernel to spoof an `EPERM` or `EACCES` failure.

---

## 3. Stacktrace Scoping (Context-Aware Auth)

The most powerful capability unlocked by the Supervisor is "Stacktrace Scoping." Because the Supervisor runs within the same JVM, it can map the blocked OS Thread ID (TID) to the corresponding `java.lang.Thread` instance and inspect its stack trace.

**Example Use Case:** A connection pool (like HikariCP) needs to establish outbound connections to a database.
*   **Legitimate Request:** The Supervisor intercepts `connect()`. The stack trace reveals `com.zaxxer.hikari.pool.HikariPool.createPoolEntry()`. The Supervisor authorizes the injection.
*   **Malicious Request:** An SSRF vulnerability is exploited in a web controller. The stack trace reveals `com.example.controller.WebhookController.fetchImage() -> java.net.Socket.connect()`. The Supervisor denies the request.

---

## 4. Mitigating the Confused Deputy Problem

The Supervisor is a highly privileged deputy acting on behalf of an untrusted client. This introduces severe security risks that must be mitigated.

### A. Preventing Stacktrace Spoofing
If an attacker can spoof the JVM stack trace, the Supervisor's primary authorization mechanism is compromised.
*   **Gadget Chains Betray Themselves:** Standard deserialization exploits (e.g., Jackson, XStream) require complex reflection chains to reach sensitive APIs. These reflection artifacts (`sun.reflect.NativeMethodAccessorImpl`) will be blatantly obvious in the stack trace and will automatically fail strict whitelists.
*   **Tier 1 Memory Protection (The Ultimate Backstop):** The only way to reliably spoof a stack trace without leaving reflection artifacts is to achieve Arbitrary Code Execution (ACE) and manually rewrite the JVM's internal thread structures in memory. `mazewall`'s mandatory **Tier 1 Process-Wide Baseline** uses `mprotect` restrictions (`NO_EXEC` / `W^X`) to prevent native shellcode execution. Without ACE, stacktrace spoofing is mathematically infeasible.

### B. Preventing TOCTTOU & Path Traversal (FD Injection)
When injecting File Descriptors, the Supervisor must never trust string paths provided by the untrusted thread. Userspace path canonicalization is vulnerable to symlink race conditions.
*   **`openat2` with `RESOLVE_BENEATH`:** The Supervisor must exclusively use the Linux 5.6+ `openat2` syscall with the `RESOLVE_BENEATH` flag. The Supervisor opens a safe "Root FD" (e.g., `/tmp/safe_zone/`). When processing a request, it calls `openat2(root_fd, requested_path, RESOLVE_BENEATH)`. 
*   **Kernel Enforcement:** If the `requested_path` contains `../` or absolute symlinks that attempt to escape the `root_fd` directory, the kernel guarantees an instant `EXDEV` failure. This provides race-free, kernel-enforced path confinement, ensuring the Supervisor cannot be tricked into opening host files like `/etc/shadow`.
*   **Double-Sandboxing:** As an additional defense-in-depth measure, the dedicated Supervisor thread itself should be subjected to a strict Landlock policy, restricting its own host filesystem access to the bare minimum required for proxy operations.