---
title: "Differential Kernel-vs-Simulator Integration Tests for Compiled BPF Filters"
severity: "MEDIUM"
status: "resolved"
priority: high
component: "testing"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies: []
---

# 🟠 [Severity: MEDIUM]: Differential Kernel-vs-Simulator Integration Tests for Compiled BPF Filters

**Context:** The JA misencoding bug (issue-20260823-140500) slipped through the entire unit suite
because the test oracle (`evalBPF` in BpfFilterTest) encoded the same wrong JA assumption
(jt-based offset) as the compiler — a shared-blindspot failure. The bug surfaced only as an
untargeted symptom (integration-test executor SIGSEGV) that could plausibly be misread as infra
flakiness. Unit simulation alone cannot catch compiler+oracle consensus errors; only the real
kernel is ground truth.

**Note:** The two originally-attached design questions were split into
issue-20260823-172000-forked-child-kill-verdict-probes.md and
issue-20260823-172100-shared-syscall-probe-matrix.md; this issue depends on neither for its core scope.

**Resolution (2026-08-23):** Implemented as `enforcer/src/integrationTest/.../SeccompDifferentialVerdictTest.kt`
(@NeedsFreshJvm), covering three archetypes: blacklist chunked scan (errno EPERM), BST fast path
(default ALLOW, ≤32 actions), and KILL_THREAD via child JVM per issue-20260823-172000. Every
archetype asserts (a) simulator predictions over the shared probe matrix, (b) post-install
liveness (would have caught issue-20260823-140500 deterministically), and (c) kernel-vs-oracle
consensus for matched and unmatched NRs. The single oracle is now production code:
`platform/io.mazewall.seccomp.BpfSimulator` (+ `SyscallProbeMatrix`) per issue-20260823-172100;
the unit-test interpreter in BpfFilterTest delegates to it.

**Needed:**
1. Add an integration test suite that, for each policy archetype (blacklist linear scan, BST
   fast path ≤32 entries, allow-list chunked scan, arg-inspection heavy, supervised ACT_NOTIFY),
   does:
   a. Compile the filter, run it through `evalBPF` for a probe matrix of syscall NRs
      (matched, unmatched, nr==0 edge, high NRs).
   b. Install the same filter on the real kernel (fresh thread/process per policy).
   c. Assert kernel verdicts (`ALLOW` / `errno` / killed) match simulator predictions for every
      probe. Any divergence fails with both instruction dumps attached.
2. Post-install liveness assertion: a benign syscall (e.g. `getpid`) must succeed immediately after
   installation — catches whole-program fall-through corruption in one line (would have caught
   issue-20260823-140500 deterministically).
3. Keep structural encoding pins (e.g. JA-in-k regression test) as cheap first-line guards; the
   differential suite is the deep backstop.

