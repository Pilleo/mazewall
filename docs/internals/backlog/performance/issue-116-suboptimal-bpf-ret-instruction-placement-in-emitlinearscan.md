---
title: "Suboptimal BPF `RET` instruction placement in `emitLinearScan`"
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
---

# 🔴 [Severity: LOW]: Suboptimal BPF `RET` instruction placement in `emitLinearScan`

**Context:**
**Hypothesis:** The BPF linear scan compiler does not deduplicate identical return values. If many syscalls map to the same restrictive action, the resulting bytecode size will bloat unnecessarily, potentially hitting the Linux 4096 instruction limit.

In `BpfFilter.emitLinearScan`, the code loops over all `syscallActions` and executes `builder.expect(nr) { ret(nativeAction) }`. This injects a jump instruction and a discrete `RET` instruction for every single mapped syscall. If 50 syscalls are mapped to `ACT_ERRNO`, it emits 50 separate `RET` instructions instead of jumping to a single shared `RET` block for `ACT_ERRNO`. This wastes BPF instruction slots and creates suboptimal CPU instruction cache usage inside the kernel.


**Needed:**
1. Group the `syscallActions` by `nativeAction`. Iterate through the groups, emit jump chains for the syscall numbers, and place a single `RET <action>` instruction at the end of each chain using shared labels.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt``
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
