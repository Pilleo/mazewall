---
title: "Missing BPF Instruction Limit Validation in `newSockFProg`"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "seccomp"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 219
---

# 🔴 [Severity: MEDIUM]: Missing BPF Instruction Limit Validation in `newSockFProg`

**Context:**
**Hypothesis:** If the generated seccomp filter contains more than the maximum permitted BPF instructions, downcasting the filter array size to a 16-bit short during `sock_fprog` structure allocation will cause a silent size truncation, leading to invalid/incomplete filter loading.

`Layouts.SOCK_FPROG` defines `len` as `JAVA_SHORT`. `MemoryImpl.newSockFProg` assigns `filters.size.toShort()` to `len`. The Linux kernel `bpf_prog_alloc` limits seccomp BPF programs to 4096 instructions (`BPF_MAXINSNS`). While 4096 fits within a 16-bit short, the `mazewall` JVM layer currently does not explicitly validate `filters.size <= 4096` before allocating the struct. If a malicious or auto-generated policy creates 5000 instructions, `toShort()` casts it, and the kernel receives a truncated filter, breaking security guarantees.


**Needed:**
1. Add an explicit `require(filters.size <= 4096) { "BPF program exceeds kernel maximum instruction limit" }` in `newSockFProg` or `BpfFilter.build`.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.seccomp.PureJavaBpfEngine``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
