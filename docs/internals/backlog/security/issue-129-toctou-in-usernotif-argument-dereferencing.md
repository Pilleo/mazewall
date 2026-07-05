---
title: "TOCTOU in `USER_NOTIF` Argument Dereferencing"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: TOCTOU in `USER_NOTIF` Argument Dereferencing

*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt`
*   **Failure Hypothesis:** When the profiler daemon receives a `SECCOMP_NOTIF` event, it reads memory from the target process using `process_vm_readv`. A malicious or concurrent thread in the target process could modify the memory arguments (e.g., a file path string) *after* the profiler reads it but *before* the kernel executes the syscall.
*   **Context & Proof:** This is a classic Time-of-Check to Time-of-Use (TOCTOU) vulnerability inherent in `ptrace` or `USER_NOTIF` architectures where arguments are passed by reference (pointers). The profiler might log or allow an action based on `path_A`, but the kernel might actually execute the syscall on `path_B`.
*   **Cascading Risk Potential:** Medium (Profiler Context). The profiler is designed for generating policies, not strict enforcement, so the security impact is lower. However, it leads to inaccurate profiles being generated if the application has race conditions in its syscall arguments.
*   **Recommendation:** Document this inherent limitation of `USER_NOTIF` profiling. Mention that `Landlock` is the preferred mechanism for robust, race-free filesystem restriction since it evaluates paths in the kernel space safely.
