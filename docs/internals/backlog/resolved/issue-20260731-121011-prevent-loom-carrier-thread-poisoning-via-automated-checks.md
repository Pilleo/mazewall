---
title: "Prevent Loom Carrier Thread Poisoning Via Automated Checks"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
effort: "medium"
autonomy: "autonomous"
github_issue: 458
---

# 🔴 [Severity: HIGH]: Prevent Loom Carrier Thread Poisoning Via Automated Checks

**Context:**
Project Loom Virtual Threads run multiplexed on top of underlying OS "carrier" threads (usually a ForkJoinPool). If a seccomp filter is applied locally to a thread (using `SECCOMP_MODE_FILTER` without `TSYNC` / thread-scoped), it permanently restricts the OS carrier thread. When the Virtual Thread unmounts, the carrier thread remains permanently restricted. Any other Virtual Thread subsequently scheduled on that poisoned carrier thread will inherit those seccomp restrictions, causing unexpected `EPERM` or fatal crashes in unrelated virtual threads.

While the codebase documents Loom carrier thread protection as an invariant, it relies on developers manually wrapping thread-scoped executors or using process-wide containment. There is currently no dynamic or static check that verifies if thread-local seccomp filters are being applied onto Loom carrier threads.

**Needed:**
1. Implement a dynamic runtime check in `ContainedExecutors.kt` and `SandboxDispatcher.kt` that detects if the current thread is a Loom carrier thread or virtual thread:
   - e.g., `Thread.currentThread().isVirtual` or checking if the running thread name belongs to ForkJoinPool carrier threads.
2. If an operator attempts to install a thread-scoped seccomp filter or thread-local Landlock sandbox onto a Virtual Thread or directly onto a carrier thread, automatically reject the operation with a descriptive `UnsupportedOperationException` explaining the risk of carrier thread poisoning, or automatically upgrade the installation to safe process-wide containment or wrapped executors.
3. Add a dedicated unit test in `ContainedExecutorsTest.kt` verifying that thread-scoped sandboxing is disallowed on virtual/carrier threads and fails-closed cleanly.

**Verification/Regression Tests:**
- Verify that standard thread-scoped containment on platform threads continues to work correctly.
- Run `./gradlew :enforcer:test` to verify all tests pass.
