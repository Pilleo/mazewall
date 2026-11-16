---
title: "Avoid verbose verifier log exhaustion in Tier E diagnostics"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoder.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramLoadFailureEvidenceTest.kt"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/backlog/issue-20261104-082104-029-avoid-verbose-verifier-log-exhaustion-in-tier-e-di.md"
  - "docs/internals/backlog/README.md"
target_symbols:
  - "BpfProgLoadRequestEncoder"
  - "TierEBpfProgramLoadFailureEvidence"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Avoid verbose verifier log exhaustion in Tier E diagnostics

**Context:**
Cycle 001 retained same-call MZPL evidence for tier_e_marker: 398 submitted slots, errno28 ENOSPC, log_level1, log_true_size307202 versus capacity65536. Verbose logging demonstrably exceeds the fixed buffer; its finalization may mask an underlying verifier result. Preserve the failed capture and ordinary loader defaults.

**Needed:**
1. Use bounded stats-level verifier logging only for the explicit boundary diagnostic; prove default104-byte/level1 parity and diagnostic144-byte/level4 selection, then run the same freshly owned privileged workload and retain every result.

**Host result:** The encoder now requests stats-only level 4 for diagnostics;
ordinary requests retain level 1. The three focused encoder, layout and evidence
test classes passed 29 tests, and compile, distribution and quality checks passed.
The issue remains open pending fresh kernel and integration evidence; host tests
cannot establish whether the kernel load succeeds or another error is exposed.

---
<!-- id: issue-20261104-082104-029-avoid-verbose-verifier-log-exhaustion-in-tier-e-di  file: issue-20261104-082104-029-avoid-verbose-verifier-log-exhaustion-in-tier-e-di.md -->
