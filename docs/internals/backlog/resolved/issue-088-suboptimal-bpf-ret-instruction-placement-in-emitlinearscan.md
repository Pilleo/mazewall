---
title: "Suboptimal BPF `RET` instruction placement in `emitLinearScan`"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "unknown"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 231
---

# 🔴 [Severity: LOW]: Suboptimal BPF `RET` instruction placement in `emitLinearScan`

**Context:**
**Hypothesis:** The BPF `emitLinearScan` generates a sequence of checks like `JEQ syscall_nr -> RET action; JEQ syscall_nr_2 -> RET action`. If the policy has a default action of `ACT_ALLOW` (blacklist) and blocks a small number of syscalls (e.g. `EXECVE`), every single allowed system call must jump through the entire block list before reaching the final `RET ALLOW` instruction at the end of the filter.

`emitLinearScan` iterates over blocked syscalls and adds checks. The default action is appended at the very end. This structure means the "fast path" (allowed syscalls) is actually the "slowest path" through the filter, requiring N evaluations. Since most system calls are allowed in a typical application, the kernel evaluates the maximum number of instructions for every single standard file or network operation.


**Needed:**
1. Optimize the BPF compiler. If the default action is `ALLOW`, invert the logic: use a binary search tree or jump tables within the BPF bytecode to reach the decision faster, or early-exit if the syscall number falls outside the blocked ranges.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.BpfFilter` (specifically `emitLinearScan`)`
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
