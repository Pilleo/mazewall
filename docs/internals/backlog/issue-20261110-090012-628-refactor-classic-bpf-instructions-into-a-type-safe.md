---
title: "Refactor classic-BPF instructions into a type-safe sealed hierarchy"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "platform/src/main/kotlin/io/mazewall/seccomp/BpfInstruction.kt"
target_symbols: 
  - "BpfInstruction"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Refactor classic-BPF instructions into a type-safe sealed hierarchy

**Context:**
P1 architecture/correctness (:platform, :enforcer, effort medium-large, risk medium-high, supervised refactor). Depends on the opcode-aware verifier fix (issue-20261110-085945-106). BpfInstruction is sealed but subclasses expose raw encoding fields, e.g. data class Jmp(code: Short, jt: Short, jf: Short, k: Int). This allows JA with a meaningful jt, conditional jumps with unsupported opcodes, Ret whose code is not BPF_RET, Ld with an incompatible code. The verifier JA bug is a concrete consequence of Jmp covering both jump kinds. Also: compileJaJump() uses the generic resolveLabel(), capping JA offsets at 255 although JA uses a 32-bit unsigned k (compilation limitation, not necessarily a vulnerability). Goal: represent the supported classic-BPF subset as strongly typed variants so invalid combinations are hard or impossible to construct. Not a general eBPF/cBPF opcode compiler. Illustrative shape: sealed interface BpfInstruction { LoadAbsolute, And, sealed interface Jump { Always(skip: UInt), Conditional(condition: Condition, operand: UInt, onTrue: UByte, onFalse: UByte) }, Return }; Condition is a closed enum/sealed type (eq, gt, bit-set). Requirements: (1) opcode derived from type, no caller-supplied opcode (Jump.Always always BPF_JMP|BPF_JA); (2) separate conditional/unconditional jumps without irrelevant fields; (3) model sock_filter widths: code u16, jt u8, jf u8, k u32, preserving bit patterns to native; (4) distinct offset resolution per instruction kind: conditional <=255, JA up to 32-bit bounded by 4096-instruction limit; reject backward/out-of-bounds; keep builder-owned label validation; (5) one single encoding boundary typed -> raw sock_filter, deterministic for all supported opcodes; (6) update all consumers exhaustively with when without silent default branches: BpfProgram.kt, BpfStaticVerifier.kt, BpfSimulator.kt, disassembly/diagnostics, FFM/native serialization, policy compilation and tests; raw/decoded external bytecode goes through a separate explicit validation boundary rather than weakening the typed model. Tests: construction (invalid combos unrepresentable); exact sock_filter fields per instruction type; conditional offsets 0, 255, and >255 rejected; valid JA forward jump >255 within size limit; byte-identical output for representative compiled policies before/after (except separately justified defect fixes); existing simulator-vs-kernel differential tests. Acceptance: separate Jump types; no inconsistent opcodes; deterministic serialization; verifier uses typed jump semantics; unsigned widths preserved; JA offsets >255 supported; simulator and disassembler handle every variant; builder states and label ownership preserved; unit and Linux integration tests pass; no new runtime dependencies. Related resolved: MAZ-214, MAZ-236, MAZ-473, MAZ-588 (extend, do not repeat). Out of scope: new syscall policy features, eBPF, replacing the DSL, rewriting native installation pipeline, new serialization framework. Principle: prefer compile-time enforcement over runtime checks when types can eliminate a whole class of invalid instructions.

**Needed:**
1. After the verifier fix lands, design the typed Jump.Always/Jump.Conditional hierarchy with a single raw sock_filter encoding boundary and capture byte-identical baseline output of representative compiled policies before refactoring

---
<!-- id: issue-20261110-090012-628-refactor-classic-bpf-instructions-into-a-type-safe  file: issue-20261110-090012-628-refactor-classic-bpf-instructions-into-a-type-safe.md -->
