---
title: "Suboptimal BPF `RET` instruction placement in `emitLinearScan`"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: LOW]: Suboptimal BPF `RET` instruction placement in `emitLinearScan`

*   **Dimension:** Performance & Efficiency
*   **Target Area:** `io.mazewall.BpfFilter` (specifically `emitLinearScan`)
*   **Failure Hypothesis:** The BPF `emitLinearScan` generates a sequence of checks like `JEQ syscall_nr -> RET action; JEQ syscall_nr_2 -> RET action`. If the policy has a default action of `ACT_ALLOW` (blacklist) and blocks a small number of syscalls (e.g. `EXECVE`), every single allowed system call must jump through the entire block list before reaching the final `RET ALLOW` instruction at the end of the filter.
*   **Context & Proof:** `emitLinearScan` iterates over blocked syscalls and adds checks. The default action is appended at the very end. This structure means the "fast path" (allowed syscalls) is actually the "slowest path" through the filter, requiring N evaluations. Since most system calls are allowed in a typical application, the kernel evaluates the maximum number of instructions for every single standard file or network operation.
*   **Cascading Risk Potential:** Low performance risk, but contributes to unnecessary CPU overhead per system call.
*   **Recommendation:** Optimize the BPF compiler. If the default action is `ALLOW`, invert the logic: use a binary search tree or jump tables within the BPF bytecode to reach the decision faster, or early-exit if the syscall number falls outside the blocked ranges.
