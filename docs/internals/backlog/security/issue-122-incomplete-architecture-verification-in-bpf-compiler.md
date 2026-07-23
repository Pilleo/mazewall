---
title: "Incomplete Architecture Verification in BPF Compiler"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 201
---

# 🔴 [Severity: HIGH]: Incomplete Architecture Verification in BPF Compiler

**Context:**
**Hypothesis:** The BPF program builder checks the architecture (`checkArch(arch)`), but it might not handle the audit architecture value correctly for aarch64 (ARM64), leading to bypassed filters on non-x86 platforms.

The BPF filter needs to strictly validate the `AUDIT_ARCH` from `seccomp_data`. If the mapping for `Arch.AARCH64` yields an incorrect constant, or if the filter fails to reject mismatched architectures with `SECCOMP_RET_KILL`, a thread could bypass the filter by using an emulation layer or `execve`ing a binary of a different architecture (e.g., x86_32 on x86_64).


**Needed:**
1. Audit the `checkArch` emission logic to ensure it correctly loads the `arch` field from `seccomp_data` (offset 4) and jumps to a strict `KILL` action if it does not match the expected native architecture.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt``
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
