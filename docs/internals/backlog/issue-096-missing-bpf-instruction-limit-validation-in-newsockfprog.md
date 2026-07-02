---
title: "Missing BPF Instruction Limit Validation in `newSockFProg`"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Missing BPF Instruction Limit Validation in `newSockFProg`

*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `io.mazewall.seccomp.PureJavaBpfEngine`
*   **Failure Hypothesis:** If the generated seccomp filter contains more than the maximum permitted BPF instructions, downcasting the filter array size to a 16-bit short during `sock_fprog` structure allocation will cause a silent size truncation, leading to invalid/incomplete filter loading.
*   **Context & Proof:** `Layouts.SOCK_FPROG` defines `len` as `JAVA_SHORT`. `MemoryImpl.newSockFProg` assigns `filters.size.toShort()` to `len`. The Linux kernel `bpf_prog_alloc` limits seccomp BPF programs to 4096 instructions (`BPF_MAXINSNS`). While 4096 fits within a 16-bit short, the `mazewall` JVM layer currently does not explicitly validate `filters.size <= 4096` before allocating the struct. If a malicious or auto-generated policy creates 5000 instructions, `toShort()` casts it, and the kernel receives a truncated filter, breaking security guarantees.
*   **Cascading Risk Potential:** Medium security defect. Can lead to silently incomplete sandbox policies if developers generate massive rulesets.
*   **Recommendation:** Add an explicit `require(filters.size <= 4096) { "BPF program exceeds kernel maximum instruction limit" }` in `newSockFProg` or `BpfFilter.build`.
