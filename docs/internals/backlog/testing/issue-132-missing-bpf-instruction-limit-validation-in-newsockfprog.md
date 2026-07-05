---
title: "Missing BPF Instruction Limit Validation in `newSockFProg`"
severity: "HIGH"
status: "open"
priority: 5
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🔴 [Severity: MEDIUM]: Missing BPF Instruction Limit Validation in `newSockFProg`

*   **Dimension:** Performance & Efficiency / Macro-Architecture
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SockFProg.kt` (or wherever `newSockFProg` is implemented)
*   **Failure Hypothesis:** The kernel strictly limits BPF programs to 4096 instructions (`BPF_MAXINSNS`). If `PolicyDefinition.compile()` generates a filter exceeding this limit, `newSockFProg` might allocate it successfully, but the `seccomp` syscall will fail mysteriously with `EINVAL`.
*   **Context & Proof:** While there is a test checking this, there might not be explicit runtime validation before attempting the syscall. A complex policy with hundreds of file paths or network addresses could exceed the limit.
*   **Cascading Risk Potential:** Medium. `EINVAL` from seccomp is notoriously hard to debug.
*   **Recommendation:** Add an explicit check in `buildFilter` or `newSockFProg` to throw a descriptive `IllegalArgumentException` if the instruction count exceeds `BPF_MAXINSNS` (4096).
