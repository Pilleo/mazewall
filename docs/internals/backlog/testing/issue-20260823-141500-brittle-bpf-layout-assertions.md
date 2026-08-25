---
title: "Brittle Instruction-Layout Assertions in BpfFilterTest (Adjacent-Pair Scans)"
severity: "LOW"
status: "open"
priority: medium
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/BpfFilterTest.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
paperclip_issue_id: e77bcf45-afbf-4f7b-a3e6-51feecc6a35f
---

# 🟡 [Severity: LOW]: Brittle Instruction-Layout Assertions in BpfFilterTest (Adjacent-Pair Scans)

**Context:** While fixing issue-20260823-135559, the test
`ALLOW_LIST mode generates RET ALLOW for listed syscalls` was found to pass **accidentally**: it
scanned for an adjacent `JEQ k=<readNr>` → `RET ALLOW` pair, and matched because `read` is syscall
number 0 on x86_64, so the old no-op unconditional jump (`JEQ 0, jt=jf`) aliased with it. The test
validated layout, not behavior, and silently depended on a coincidence of NR 0. It has been rewritten
to use the `evalBPF` simulator; other tests may share the pattern.

**Needed:**
1. Audit `BpfFilterTest` (and any other test that indexes raw `BpfInstruction` streams) for
   adjacency-scan assertions; replace with `evalBPF` simulations asserting decision outcomes per
   syscall number, including a non-zero NR control case.
2. Keep exactly one instruction-stream interpreter (`evalBPF`) as the shared oracle — consider
   extracting it to a test fixture so integration tests can reuse it.
3. Optional: add golden-stream regression snapshots only where layout itself is the contract (e.g.
   BST shape tests), clearly labeled as such.

