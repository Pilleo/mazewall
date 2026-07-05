---
title: "Classloader Deadlock in JVM Validation Listener"
severity: "CRITICAL"
status: "open"
priority: 5
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: CRITICAL]: Classloader Deadlock in JVM Validation Listener

*   **Dimension:** JVM / OS Contention & Classloading Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt`
*   **Failure Hypothesis:** The JVM validation listener runs on a dedicated daemon thread and evaluates `StacktraceScopingPolicy`. During evaluation, it might trigger the classloader.
*   **Context & Proof:** If `scopingPolicy.authorize` executes and lazily loads classes (e.g., custom policy classes, string utilities), it acquires the JVM ClassLoader lock. If the tracee thread that triggered the syscall was holding the ClassLoader lock (because the syscall was an `open()` inside a classloading sequence that wasn't caught by the JDK fast-path), the validation listener will block waiting for the ClassLoader lock, while the tracee thread is blocked in kernel space waiting for the seccomp response. This results in a permanent process-wide deadlock.
*   **Recommendation:** Force eager classloading of all classes required by `JVMValidationListener`, `JvmStackInspector`, and `StacktraceScopingPolicy` before installing the seccomp filter on the thread, similar to the profiler warmup. Also, ensure the custom scoping policies are strictly verified not to perform arbitrary classloading.
