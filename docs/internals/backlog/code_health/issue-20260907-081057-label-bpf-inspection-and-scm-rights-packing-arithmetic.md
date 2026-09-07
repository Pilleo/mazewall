---
title: "Label BPF inspection and SCM_RIGHTS packing arithmetic"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":platform"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/BpfFilterTest.kt"
target_symbols:
  - "BpfFilter"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.seccomp.BpfFilterTest"
needs_kernel: true
core_lock: false
effort: "small"
autonomy: "autonomous"
open_questions: false
has_side_effects: false

paperclip_issue_id: "c48a361a-5128-435d-8032-10b93745e961"
paperclip_identifier: "MAZ-1184"
---

# 🟢 [Severity: LOW]: Label BPF inspection and SCM_RIGHTS packing arithmetic

**Context:**
`BpfFilter.emitInspections` is an unlabeled walk of 64-bit high/low loads and jumps over `EqualsAny`/`EqualsAny32`/`MaskEquals`. `SAFE_PRCTL_OPTIONS` is the unnamed list `15, 16, 21, 22, 38, 39`. SCM_RIGHTS packing uses bare `CMSG_RIGHTS_LEN=20L`, `MSG_CONTROL_BUF_SIZE=24L`, and `CMSGHDR_DATA_OFFSET=16L` with no `CMSG_ALIGN` comment. Profiler transport still carries unused CMSG magic under `@Suppress("MagicNumber")`. Reviewers cannot check the arithmetic without kernel headers in their head.

**Needed:**
1. Comment each 64-bit load/jump in `emitInspections` with the BPF register/width meaning.
2. Name `SAFE_PRCTL_OPTIONS` entries (`PR_SET_SECCOMP`, `PR_GET_DUMPABLE`, …) next to the numbers.
3. Document `CMSG_RIGHTS_LEN` / `CMSGHDR_DATA_OFFSET` as LP64 `CMSG_LEN(sizeof(int))` / `CMSG_ALIGN(sizeof(cmsghdr))` in `SupervisorSocketUtils` (and delete unused profiler CMSG magic, or comment why it remains).
4. No behavior change. Run existing `BpfFilterTest` and socket-utils tests.

## Side effects
- None intended; comments and named constants only

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-081057  file: issue-20260907-081057-label-bpf-inspection-and-scm-rights-packing-arithmetic.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
