---
title: "Preserve safe prctl argument allowances under explicit syscall denial"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/SyscallInspector.kt"
target_symbols:
  - "UnsafePrctlInspector"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve safe prctl argument allowances under explicit syscall denial

**Context:**
The prctl inspector uses the policy action for its safe-option branch, so explicit PRCTL denial also denies thread naming and seccomp stacking despite enabled argument inspection and the JVM-floor invariant.

**Needed:**
1. Keep safe prctl options allowed while inspection is enabled, preserve dangerous-option denial and explicit unsafe-prctl opt-out semantics, and verify both supported architectures.

**Implementation evidence (2026-11-03):**
The inspected safe-option branch now returns ALLOW. Both architecture regressions
verify the six safe options under explicit PRCTL denial and retain unsafe-option
denial. The int-ABI high-word-garbage regression also passes.
See the [scoped review](../reviews/2026-11-03-bpf-generation-hardening.md).
The full `./gradlew build` passed on 2026-11-03 in 22m 40s (243 actionable tasks:
107 executed, 136 up-to-date); evidence: `/tmp/opencode/bpf-full-build-final.log`.

---
<!-- id: issue-20261103-115030-497-preserve-safe-prctl-argument-allowances-under-expl  file: issue-20261103-115030-497-preserve-safe-prctl-argument-allowances-under-expl.md -->
