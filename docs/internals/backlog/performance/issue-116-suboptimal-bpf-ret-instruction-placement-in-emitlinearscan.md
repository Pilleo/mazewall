---
title: "Suboptimal BPF `RET` instruction placement in `emitLinearScan`"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: LOW]: Suboptimal BPF `RET` instruction placement in `emitLinearScan`

*   **Dimension:** Performance / Macro-Architecture
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt`
*   **Failure Hypothesis:** The BPF linear scan compiler does not deduplicate identical return values. If many syscalls map to the same restrictive action, the resulting bytecode size will bloat unnecessarily, potentially hitting the Linux 4096 instruction limit.
*   **Context & Proof:** In `BpfFilter.emitLinearScan`, the code loops over all `syscallActions` and executes `builder.expect(nr) { ret(nativeAction) }`. This injects a jump instruction and a discrete `RET` instruction for every single mapped syscall. If 50 syscalls are mapped to `ACT_ERRNO`, it emits 50 separate `RET` instructions instead of jumping to a single shared `RET` block for `ACT_ERRNO`. This wastes BPF instruction slots and creates suboptimal CPU instruction cache usage inside the kernel.
*   **Cascading Risk Potential:** Low, but impacts bytecode efficiency. In scenarios with massive `DENY_LIST` policies, this bloat could push the BPF program size closer to the strict `BPF_MAXINSNS` limit (4096), causing `seccomp(2)` to inexplicably fail with `EINVAL` during installation.
*   **Recommendation:** Group the `syscallActions` by `nativeAction`. Iterate through the groups, emit jump chains for the syscall numbers, and place a single `RET <action>` instruction at the end of each chain using shared labels.
