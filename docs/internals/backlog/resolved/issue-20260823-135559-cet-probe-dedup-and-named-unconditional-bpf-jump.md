---
title: "Deduplicate CET Probe/Guard Ladder and Introduce Named Unconditional BPF Jump"
severity: "LOW"
status: "resolved"
priority: medium
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟡 [Severity: LOW]: Deduplicate CET Probe/Guard Ladder and Introduce Named Unconditional BPF Jump

**Context:** Two shared-logic hygiene items:

1. **CET probe duplication.** The guard ladder `isLinux && isArchitectureSupported() &&
   Arch.current() == AMD64` plus the `archPrctl(ARCH_SHSTK_STATUS)` probe is copy-pasted across
   `Platform.isKernelCetSupported` (Platform.kt:176-190), `Platform.queryIntelCetStatus`
   (Platform.kt:219-239), and again inside `ContainedExecutors.armIntelCet` (ContainedExecutors.kt:434).
   A future change to the probe (e.g. caching, errno discrimination) must be replicated three times.
2. **Implicit unconditional-jump idiom.** `builder.jumpIfEqual(0, jt = X, jf = X)` is used as an
   unconditional jump in four places in `BpfFilter.emitLinearScan`/`emitBst`
   (BpfFilter.kt:313, 341, 381, 396). It is correct only because both jump targets are identical; a later
   edit that changes one target would silently make syscall number 0 (i.e. `read` on x86_64) take a
   different control-flow edge than every other NR. This deserves an explicit, named builder method.

**Resolution note:** CET guard ladder consolidated into `Platform.isCetPlatformEligible` + private `probeShstkStatus()`; `jumpUnconditional` added. Switching it to a true `BPF_JMP_JA` caused reproducible executor SIGSEGVs — deferred to issue-20260823-140500; the helper intentionally keeps emitting the proven self-comparison idiom.

**Needed:**
1. Extract a single internal helper, e.g. `CetProbe.queryStatus(): Long?` / `isAvailable(): Boolean`, that
   owns the guard ladder and the `archPrctl(ARCH_SHSTK_STATUS)` downcall; have all three call sites use it.
2. Add `BpfBuilder.jumpUnconditional(label)` (emitting `BPF_JMP|BPF_JA`, or the existing no-op compare)
   and replace all four occurrences in `BpfFilter`.
3. Add unit tests asserting the generated instruction stream for the BST and chunked-scan paths is
   unchanged after introducing `jumpUnconditional` (golden-instruction regression test).

