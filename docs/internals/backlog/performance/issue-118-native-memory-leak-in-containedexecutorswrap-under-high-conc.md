---
title: "Native Memory Leak in `ContainedExecutors.wrap` under High Concurrency"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Native Memory Leak in `ContainedExecutors.wrap` under High Concurrency

*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** The BPF program memory allocated via `nativeScope` might leak or be prematurely freed if the `seccomp` syscall is interrupted or delayed by a GC pause.
*   **Context & Proof:** `PureJavaBpfEngine.installInternal` uses `nativeScope { val built = locked.buildFilter(this, policy); built.applyFilter(arch, useTsync) }`. The `nativeScope` (typically `Arena.ofConfined()`) guarantees memory is freed when the block exits. If `seccomp` is called, the kernel copies the BPF instructions into kernel space. However, if `ContainedExecutors.wrap` submits thousands of tasks concurrently, and each task triggers a nested compilation/installation that throws an exception inside the `nativeScope`, the JVM might struggle to clean up the confined arenas promptly if the exceptions are caught and swallowed by the executor.
*   **Cascading Risk Potential:** Medium. In a highly dynamic environment, this could lead to native memory exhaustion.
*   **Recommendation:** Verify the `nativeScope` implementation correctly bounds the arena lifetime even when exceptions are thrown (e.g., ensure it uses `try-finally` internally), and consider caching the compiled `MemorySegment` for identical policies.
