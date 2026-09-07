---
title: "Extract Tier-E BPF program-load request encoder"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoder.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoderTest.kt"
target_symbols:
  - "TierEbpfEngine"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
has_side_effects: true

paperclip_issue_id: "166b8195-063e-4d29-acd3-64f609829cd2"
paperclip_identifier: "MAZ-1147"
---

# 🟡 [Severity: MEDIUM]: Extract Tier-E BPF program-load request encoder

**Context:**
`TierEbpfEngine.loadProg` mixes pseudo-map-FD substitution, program-name validation, FFM
`bpf_attr` encoding, the native `bpf` call, errno capture, and diagnostic translation. The request
construction is deterministic and security-relevant, but most of it is currently invisible to
host-unit tests.

**Needed:**
1. Extract an internal encoder that validates inputs, replaces pseudo-map FDs, and encodes a
   program-load request into a caller-owned confined arena. It must make no syscall.
2. Keep arena ownership, the `bpf` call, immediate errno capture, and kernel-result translation in
   `TierEbpfEngine`; retain every ABI field width and pointer layout.
3. Add host-unit tests that inspect the encoded `bpf_attr` for instruction count/address, license,
   log settings, program type, pseudo-FD substitution, boundary-length name, overlong name,
   missing pseudo-FD, and malformed instructions.
4. Apply the FFM safety checklist and run focused encoder/layout tests plus `./gradlew :profiler:test`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-065452  file: issue-20260907-065452-extract-tier-e-bpf-program-load-request-encoder.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->

## Resolution evidence (2026-09-07)

- `BpfProgLoadRequestEncoder` owns pure validation, pseudo-map-FD substitution, and caller-arena `bpf_attr` encoding; `TierEbpfEngine.loadProg` retains the syscall, immediate result handling, and verifier diagnostic translation.
- The encoder rejects non-ASCII and overlong names, absent pseudo-map descriptors, and register fields that cannot be represented in eBPF's four-bit register encoding.
- Host tests inspect program type, instruction count/address and packed value, GPL/license pointer, verifier-log settings/pointer, exact name boundary, pseudo-FD substitution, missing maps, and malformed registers.
- Verified: `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.engine.BpfProgLoadRequestEncoderTest` and `./gradlew :profiler:test`.
