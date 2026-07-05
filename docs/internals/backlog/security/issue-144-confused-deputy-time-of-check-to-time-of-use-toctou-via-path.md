---
title: "Confused Deputy / Time-of-Check to Time-of-Use (TOCTOU) via Path Modification"
severity: "CRITICAL"
status: "open"
priority: 5
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: CRITICAL]: Confused Deputy / Time-of-Check to Time-of-Use (TOCTOU) via Path Modification

*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** The supervisor daemon reads a string argument (path) from the target process memory using `SupervisorProcessMemoryReader`, validates it with the JVM, and if allowed, directly delegates the `open()` call or `connect()` call from the supervisor itself using that path, handing back the resulting FD via seccomp inject FD.
*   **Context & Proof:** The `handleInjectFd` method opens the file `pathStr` (which was read *previously* by `processNotification` or `readAndHandleJvmResponse`) using `openFileInSupervisor` and injects the resulting file descriptor into the tracee. However, `pathStr` was read from the tracee's memory address space asynchronously. Between the time the memory was read and validated by the JVM, and the time the `SupervisorDaemon` executes `openFileInSupervisor()`, another thread in the tracee could mutate the memory string to point to a restricted file (e.g., from `/tmp/allowed` to `/etc/shadow`). The supervisor will then blindly open `/etc/shadow` and inject the FD back to the tracee.
*   **Recommendation:** Do not use string paths for FD injection when there is an untrusted shared memory boundary. Instead of delegating the `open()` to the supervisor, the supervisor should reply with `SECCOMP_USER_NOTIF_FLAG_CONTINUE` if authorized, letting the kernel perform the syscall safely in the tracee's context using the *current* state of the memory.
