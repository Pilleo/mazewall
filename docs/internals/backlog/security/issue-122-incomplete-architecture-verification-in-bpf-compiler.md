---
title: "Incomplete Architecture Verification in BPF Compiler"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: HIGH]: Incomplete Architecture Verification in BPF Compiler

*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt`
*   **Failure Hypothesis:** The BPF program builder checks the architecture (`checkArch(arch)`), but it might not handle the audit architecture value correctly for aarch64 (ARM64), leading to bypassed filters on non-x86 platforms.
*   **Context & Proof:** The BPF filter needs to strictly validate the `AUDIT_ARCH` from `seccomp_data`. If the mapping for `Arch.AARCH64` yields an incorrect constant, or if the filter fails to reject mismatched architectures with `SECCOMP_RET_KILL`, a thread could bypass the filter by using an emulation layer or `execve`ing a binary of a different architecture (e.g., x86_32 on x86_64).
*   **Cascading Risk Potential:** High. Architecture mismatch is a classic seccomp bypass vector.
*   **Recommendation:** Audit the `checkArch` emission logic to ensure it correctly loads the `arch` field from `seccomp_data` (offset 4) and jumps to a strict `KILL` action if it does not match the expected native architecture.
