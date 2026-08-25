---
title: "Extract Shared Syscall Probe Matrix Reusable by :profiler USER_NOTIF Filters"
severity: "LOW"
status: "resolved"
priority: low
component: "testing"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "platform/src/main/kotlin/io/mazewall/seccomp"
  - "profiler/src/test/kotlin/io/mazewall/profiler"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260823-171500"
---

# 🟡 [Severity: LOW]: Extract Shared Syscall Probe Matrix Reusable by :profiler USER_NOTIF Filters

**Context:** The differential test harness proposed in issue-20260823-171500 defines a syscall probe
matrix (matched NR, unmatched NR, nr==0 edge, high NRs, arg-inspection boundary values) plus expected
verdict derivation. The :profiler module compiles its own filters (`USER_NOTIF` mode via
`BpfFilter.build(..., profilingMode = true)`) and currently has no equivalent oracle-based testing.
If the matrix lives in enforcer-only test code, :profiler will either duplicate it or drift.

**Resolution (2026-08-23):** Operator chose production placement in :platform. Implemented:
`platform/src/main/kotlin/io/mazewall/seccomp/BpfSimulator.kt` hosts BOTH the reference interpreter
(`BpfSimulator.simulate`, including classic-BPF JA-in-K semantics) and `SyscallProbeMatrix`
(structural probes incl. nr==0 edge and synthetic high NRs, plus policy-derived matched probes).
Consumers: enforcer unit tests (BpfFilterTest delegates), enforcer differential integration suite,
and profiler pure-verdict mapping tests (`ProfilerFilterVerdictMappingTest`: profilingMode maps
ACT_ERRNO->USER_NOTIF, force-allows ioctl). Production placement also enables a future fail-closed
self-verify-before-install feature without new dependencies.

**Needed:**
1. Move the probe-matrix definition and expected-verdict model into shared source
   (`:platform`'s `io.mazewall.seccomp` package or a shared test fixture module).
2. Add a :profiler differential test that compiles filters with `profilingMode = true` and asserts
   simulator predictions for the notification path (RET_USER_NOTIF verdict mapping), without
   requiring a live supervisor socket for the pure-verdict layer.
3. Follow cross-module protocol: land :enforcer changes first, verify `:enforcer:check`, then wire
   :profiler.

