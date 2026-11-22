---
title: "Validate seccomp action payloads before construction and compilation"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":platform"
  - ":enforcer"
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/syscall/SeccompAction.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/seccomp/BpfFilter.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/artifact/CanonicalPolicyJson.kt"
  - "platform/src/test/kotlin/io/mazewall/core/SeccompActionTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/CanonicalPolicyArtifactTest.kt"
target_symbols:
  - "SeccompAction"
open_questions: false
---

# 🟡 [Severity: HIGH]: Validate seccomp action payloads before construction and compilation

**Context:**
Host-only reproduction: ACT_ERRNO(65536) is accepted and toKernelReturnCode masks its payload to zero. BpfFilter.resolveNativeAction also masks errno and trace IDs, while CanonicalPolicyJson rejects out-of-range payloads. Native installation effects were not exercised.

**Needed:**
1. Enforce consistent errno and trace payload ranges in action construction, including copy paths; retain canonical import validation. Test negative, zero, maximum and overflowing payloads and agreement between valid action encoding, BPF compilation and canonical round trips.

**Design-review follow-up (2026-11-03, finding 2):**
- Source inspection also finds inconsistent valid TRACE encoding: `SeccompAction.kt:112-122` omits `traceId`, while `BpfFilter.kt:97-99` includes its low 16 bits. `SeccompActionTest` currently expects the payload-free helper result, so update that expectation with an independent encoding regression.
- Validate constructor/copy inputs using the existing canonical payload contract and share the ordinary action encoder. Keep profiling-mode action substitution explicit; do not change BPF inspection order or the linear-scan design.
- Check nonzero TRACE payload agreement across helper encoding, compiled instructions and canonical round trips. Validate negative/zero/boundary/overflow cases without native installation; any native claim requires its own kernel evidence.

**Implementation evidence (2026-11-03):**
Constructor and copy validation now enforce errno `1..65535` and TRACE `0..65535`;
the shared encoder retains both payloads and the BPF compiler uses typed actions.
Focused action-boundary, compiler, canonical-policy round-trip and profiling verdict
tests pass. See the [scoped review](../../reviews/2026-11-03-bpf-generation-hardening.md).
The full `./gradlew build` passed on 2026-11-03 in 22m 40s (243 actionable tasks:
107 executed, 136 up-to-date); evidence: `/tmp/opencode/bpf-full-build-final.log`.

## Resolution Evidence (2026-11-21)
- Verified in `:platform:test` (`SeccompActionTest`) and `:enforcer:test` (`CanonicalPolicyArtifactTest`, `BpfFilterTest`) as well as full `./gradlew build -PmazewallKernelLane=BASELINE` (`7e62bacf7`, `ca28cfb18`).
- `SeccompAction` constructor/copy validation enforces errno `1..65535` and `ACT_TRACE` `0..65535` consistently across helpers, BPF compilation, and canonical serialization.

---
<!-- id: issue-20261028-232810-282-validate-seccomp-action-payloads-before-constructi  file: issue-20261028-232810-282-validate-seccomp-action-payloads-before-constructi.md -->
