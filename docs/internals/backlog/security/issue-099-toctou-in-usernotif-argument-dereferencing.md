---
title: "TOCTOU in `USER_NOTIF` Argument Dereferencing"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: TOCTOU in `USER_NOTIF` Argument Dereferencing

*   **Dimension:** TOCTOU & Concurrency
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
*   **Failure Hypothesis:** When the Profiler Daemon receives a `USER_NOTIF` for a syscall like `openat`, it uses `process_vm_readv` to read the path string from the tracee's memory. Because the tracee thread is stopped but other sibling threads in the same process are still running, a malicious or poorly synchronized sibling thread can rewrite the path string in memory *after* the BPF filter has triggered the notification but *before* the Profiler reads it.
*   **Context & Proof:** The Linux `SECCOMP_RET_USER_NOTIF` mechanism stops the thread making the system call. The daemon reads the arguments from the tracee's memory. Since memory is shared across threads, a TOCTOU (Time of Check to Time of Use) is possible. The kernel will eventually execute the syscall with the *current* memory contents, which might differ from what the profiler logged.
*   **Cascading Risk Potential:** Medium profiling inaccuracy. If the path changes, the `BillOfBehavior` might contain the pre-mutation or post-mutation path, leading to incorrect policies.
*   **Recommendation:** Document that the `USER_NOTIF` Tier S Profiler is vulnerable to concurrent memory mutation (TOCTOU) and is strictly intended for profiling trusted/benign workloads, not for intercepting malicious evasion attempts.
