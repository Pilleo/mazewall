---
title: Unhandled `TSYNC` edge cases during JIT classloading
severity: HIGH
status: resolved
priority: medium
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt
target_modules:
- :enforcer
component: enforcer
effort: medium
paperclip_issue_id: 84ecff78-bc1c-4034-a84c-336db331dac3
paperclip_identifier: MAZ-757
---

# 🔴 [Severity: MEDIUM]: Unhandled `TSYNC` edge cases during JIT classloading

*   **Dimension:** OS Invariants / Cascading Failure
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` (specifically `installFilter`)
*   **Failure Hypothesis:** When `installOnProcess` calls `seccomp` with `SECCOMP_FILTER_FLAG_TSYNC`, the Linux kernel applies the filter to all sibling threads synchronously. If the JVM is heavily multithreaded and a background JIT compiler thread (C1/C2) is currently executing a blocked system call (e.g., `openat` for lazy classloading) exactly when `TSYNC` takes effect, the syscall might be abruptly interrupted or subsequently denied with `EPERM` when retried.
**Context:** `PureJavaBpfEngine.installInternal` locks privileges and applies the filter using `SECCOMP_FILTER_FLAG_TSYNC`. The kernel ensures atomicity of filter application, but the JVM provides no safety guarantee that background threads are not actively engaged in IO or network calls that are about to be denied. While `mazewall` documents JIT `mmap(PROT_EXEC)` deadlocks, it does not explicitly handle TOCTTOU race conditions where `TSYNC` cuts off actively running operations, leading to non-deterministic JIT aborts in production.
*   **Cascading Risk Potential:** Medium stability risk. Can cause random, hard-to-debug JVM crashes during process-wide filter installation in high-traffic applications.
**Needed:** Document the inherent risks of `TSYNC` concurrency in `designs/core/security-considerations.md` and recommend applying process-wide policies only during application initialization (e.g. `public static void main`) before extensive multithreading or JIT activity begins.
