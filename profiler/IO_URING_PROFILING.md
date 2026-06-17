# The `io_uring` Profiling Challenge

Modern high-performance JVM workloads (e.g., Netty-based servers, database drivers) increasingly utilize **`io_uring`** for asynchronous I/O. However, `io_uring` presents a significant challenge for security profiling: **it is structurally blind to traditional unprivileged system call interception.**

This document explains why this happens and how `mazewall` provides alternative strategies to capture these asynchronous operations.

---

## 1. The "Structural Blind Spot"

Traditional profilers (like `ptrace`, `strace`, or `mazewall`'s Tier S `USER_NOTIF` supervisor) operate at the **system call boundary**. They intercept the OS thread the moment it issues a syscall (e.g., `openat`, `read`, `connect`).

`io_uring` bypasses this boundary:
1.  **Shared Memory Queues:** The application writes Submission Queue Entries (SQEs) containing file paths and commands directly into a shared-memory ring buffer.
2.  **Asynchronous Execution:** The application issues a single `io_uring_enter(2)` syscall to notify the kernel.
3.  **Kernel Worker Threads:** The actual file or socket operations are executed by kernel helper threads (`io-wq` workers).

Because the filesystem operations occur entirely within the kernel's worker threads, **no syscall boundary is crossed by the application thread.** Consequently, unprivileged supervisors are completely blind to which paths or network endpoints are being accessed via `io_uring`.

---

## 2. Profiling Strategies

`mazewall` provides three strategies to handle `io_uring` workloads, each with different trade-offs in terms of privilege and complexity.

### Strategy H: Hybrid Fallback (Recommended)
This is the most practical approach for most Java developers.
*   **Workflow:** Temporarily disable `io_uring` in your application (e.g., via a configuration flag or system property like `-Dio.netty.leakDetection.level=DISABLED`) during the profiling/test run.
*   **Mechanism:** The application automatically falls back to standard POSIX I/O (`epoll`, `read`, `write`). The unprivileged Tier S profiler can then transparently capture all syscalls and paths.
*   **Final Step:** Manually add `.unblock(Syscall.IO_URING_SETUP)` to the generated production policy to re-enable high-performance async I/O in the restricted environment.

### Strategy A: Iterative Learning (Unprivileged)
Used when you cannot or will not disable `io_uring` during profiling.
*   **Mechanism:** Leverage Landlock LSM's kernel invariant: **Landlock rulesets are inherited by `io-wq` worker threads.**
*   **Workflow:** Run the `IterativeProfiler`. When `io_uring` attempts to access a path that is currently denied by Landlock, the kernel blocks the async worker. The `IterativeProfiler` catches the resulting `AccessDeniedException` (or `EACCES` errno), whitelists the path, and retries the workload.
*   **Pros:** 100% unprivileged; catches real `io_uring` path operations.
*   **Cons:** Workload must be idempotent (it will be restarted multiple times).

### Strategy P: Privileged Tracing (eBPF)
Used for deep, transparent observability in environments where root/elevated privileges are acceptable.
*   **Mechanism:** Attach eBPF kprobes or tracepoints to the `io_uring` subsystem (e.g., `tracepoint:io_uring:io_uring_submit_req`).
*   **Privilege Requirements:** Requires `CAP_BPF`, `CAP_PERFMON`, and `CAP_SYS_PTRACE`. 
*   **Container Usage:** Running inside a rootful Docker/Podman container is **not enough** by itself. You must explicitly grant capabilities and mount kernel tracing interfaces:
    ```bash
    docker run --cap-add=BPF --cap-add=PERFMON --cap-add=SYS_PTRACE \
               -v /sys/kernel/debug:/sys/kernel/debug:rw \
               --ulimit memlock=-1:-1 ...
    ```
*   **Pros:** Zero changes to the application; captures everything natively.
*   **Cons:** High privilege requirement; not portable to standard serverless or unprivileged K8s environments.

---

## Summary Comparison

| Strategy | Privilege | Application Change | Best For |
| :--- | :--- | :--- | :--- |
| **Hybrid (H)** | Unprivileged | Config Toggle | Most application developers |
| **Iterative (A)** | Unprivileged | None (Idempotent only) | Batch jobs, non-configurable apps |
| **Privileged (P)** | **Root / eBPF** | None | Security researchers, local diagnostics |

---

## Troubleshooting

### "Missing io_uring paths in my policy"
If your generated policy is missing file paths but your app uses `io_uring`, the profiler likely ran in Tier S mode without the Hybrid fallback. The syscalls were invisible. Switch to **Strategy H** or **Strategy A**.

### "io_uring_setup fails with EPERM"
Standard container runtimes (like Docker/Podman) often block `io_uring` by default in their seccomp profiles. You may need to run with `--security-opt seccomp=unconfined` or a custom profile that whitelists the `io_uring_*` syscall family to profile them.
