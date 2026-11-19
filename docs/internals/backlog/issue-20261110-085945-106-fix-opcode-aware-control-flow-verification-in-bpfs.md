---
title: "Fix opcode-aware control-flow verification in BpfStaticVerifier"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfStaticVerifier.kt"
target_symbols: 
  - "BpfStaticVerifier.enqueueJumpTargets"
open_questions: false
---

# 🟡 [Severity: HIGH]: Fix opcode-aware control-flow verification in BpfStaticVerifier

**Context:**
P0 correctness/security bug (:enforcer, effort small-medium, review required, no dependencies). BpfStaticVerifier treats every BpfInstruction.Jmp as conditional and enqueues index+1+jt and index+1+jf. For unconditional BPF_JMP|BPF_JA (0x05) the destination is index+1+unsigned(k); jt/jf are ignored by the kernel. The verifier can therefore accept a JA whose real target is out of bounds, miss a reachable non-RET path, analyze skipped instructions as reachable, and mark a program BpfStatus.Verified without validating its real control flow. Distinct from MAZ-588 (compiler JA encoding, already fixed; generator is correct, verifier is not). Related: MAZ-393 (docs/internals/backlog/resolved/issue-20260726_011928_07_bpf-jump-out-of-bounds-unverified.md), MAZ-588 (docs/internals/backlog/resolved/issue-20260823-140500-bpf-ja-segv-investigation.md). Requirements: (1) distinguish supported conditional jump opcodes from JA in enqueueJumpTargets; (2) JA yields exactly one successor from unsigned k, never jt/jf; (3) conditional jumps keep both jt and jf successor checks; (4) reject unsupported jump opcode combinations instead of guessing; (5) compute destinations in a wide integer type (Long) before bounds check, no Int overflow; (6) keep descriptive IllegalArgumentException and existing disassembly diagnostics; (7) no second instruction interpreter, do not bypass static verification; where appropriate reject noncanonical JA with nonzero jt/jf while keeping compiler output valid. Tests in BpfStaticVerifierTest.kt: A) JA k=5 then RET ALLOW (target 6) rejected; B) valid JA over an instruction to valid RET accepted; C) JA skipping a valid RET into a non-terminating last instruction rejected; D) conditional true/false targets incl. out-of-bounds still correct; E) k=0xFFFFFFFF treated as unsigned, rejected as out-of-bounds without overflow; F) unsupported jump opcode cannot pass. Acceptance: tests A-F pass; at least one fails on the original implementation; existing conditional tests pass; compiled filters byte-unchanged; no policy generation/installation changes; :enforcer:test passes; relevant Linux integration tests pass in isolation; PR explains the previous false-verification scenario. Out of scope: refactoring the instruction hierarchy (separate typed-instruction issue), installation sequencing, syscall policies, Landlock, compiler optimizations. Static verification must not be described as kernel verification or proof of a security guarantee.

**Needed:**
1. Add failing regression test A (JA k=5 out-of-bounds) to BpfStaticVerifierTest.kt, confirm it fails on current code, then make enqueueJumpTargets opcode-aware and add tests B-F

---
<!-- id: issue-20261110-085945-106-fix-opcode-aware-control-flow-verification-in-bpfs  file: issue-20261110-085945-106-fix-opcode-aware-control-flow-verification-in-bpfs.md -->
