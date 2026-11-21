---
title: "Reject x32 syscall numbers before native seccomp policy dispatch"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfBuilder.kt"
target_symbols:
  - "BpfBuilder"
open_questions: false
---

# 🟡 [Severity: HIGH]: Reject x32 syscall numbers before native seccomp policy dispatch

**Context:**
The architecture guard accepts AUDIT_ARCH_X86_64 without rejecting the x32 syscall bit; default-ALLOW policies then miss native syscall restrictions on x32-enabled kernels.

**Needed:**
1. Reject the unsupported x32 ABI before syscall inspection and cover architecture and syscall-number boundary verdicts.

**Implementation evidence (2026-11-03):**
The shared builder prologue rejects the x32 bit and 512–547 by audit ABI. Simulator
boundaries and copied-architecture metadata regressions pass. The native x86_64
regression ran without skips: baseline children survived and guarded children exited
with SIGSYS for `0x40000027`, `512` and `547`.
See the [scoped review](../../reviews/2026-11-03-bpf-generation-hardening.md).
The full `./gradlew build` passed on 2026-11-03 in 22m 40s (243 actionable tasks:
107 executed, 136 up-to-date); evidence: `/tmp/opencode/bpf-full-build-final.log`.

## Resolution Evidence (2026-11-21)
- `BpfBuilder` prologue rejects x32 ABI syscall numbers (`__X32_SYSCALL_BIT` / `512..547` on `AUDIT_ARCH_X86_64`), verified in `:enforcer:test` and `:enforcer:integrationTest` / full `./gradlew build -PmazewallKernelLane=BASELINE` (`7e62bacf7`, `ca28cfb18`).

---
<!-- id: issue-20261103-113654-623-reject-x32-syscall-numbers-before-native-seccomp-p  file: issue-20261103-113654-623-reject-x32-syscall-numbers-before-native-seccomp-p.md -->
