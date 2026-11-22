---
title: "Make BPF verification proof immutable and enforce kernel instruction semantics"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":platform"
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt"
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfStaticVerifier.kt"
  - "platform/src/main/kotlin/io/mazewall/seccomp/BpfInstruction.kt"
target_symbols:
  - "BpfProgram"
open_questions: false
---

# 🟡 [Severity: HIGH]: Make BPF verification proof immutable and enforce kernel instruction semantics

**Context:**
The verification marker can be freely constructed, instruction lists remain mutable, and the static verifier follows jt/jf instead of k for JA and trusts arbitrary opcode/type combinations.
Retained builder aliases can alter completed output, foreign labels/termination tokens
can cross assembly ownership, and duplicate argument inspections silently shadow
later restrictions. Reproductions are retained in `BpfVerificationBoundaryTest` and
`BpfBuilderTest`; compiler-negative consumers are in `BpfCompileContractTest`.
The same ownership check is required for special handlers: a standard inspection
could otherwise shadow `Clone3Inspector`'s unconditional ENOSYS fallback.

**Needed:**
1. Constrain construction and instruction types, freeze programs, validate every instruction and control-flow destination, and retain verified proof through installation with regression evidence.

**Implementation evidence (2026-11-03):**
Implemented with concrete builder stages, immutable privately constructed programs,
closed instructions and checked native handoff. Compiler-negative consumers and
113 focused enforcer seccomp tests pass, including clone3 shadowing rejection.
See the [scoped review](../../reviews/2026-11-03-bpf-generation-hardening.md).
The full `./gradlew build` passed on 2026-11-03 in 22m 40s (243 actionable tasks:
107 executed, 136 up-to-date); evidence: `/tmp/opencode/bpf-full-build-final.log`.

## Resolution Evidence (2026-11-21)
- Concrete builder stages (`BpfBuilder`), immutable privately constructed `BpfProgram`, closed `BpfInstruction`, and verified native handoff verified in `:enforcer:test` and full `./gradlew build -PmazewallKernelLane=BASELINE` (`7e62bacf7`, `ca28cfb18`).

---
<!-- id: issue-20261103-113514-264-make-bpf-verification-proof-immutable-and-enforce-  file: issue-20261103-113514-264-make-bpf-verification-proof-immutable-and-enforce-.md -->
