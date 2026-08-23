---
title: "Install-Time Self-Verification Using BpfSimulator Probe Matrix"
severity: "MEDIUM"
status: "open"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "platform/src/main/kotlin/io/mazewall/seccomp/BpfSimulator.kt"
effort: "medium"
autonomy: "supervised"
open_questions: true
dependencies:
  - "issue-20260823-171500"
---

# 🟠 [Severity: MEDIUM]: Install-Time Self-Verification Using BpfSimulator Probe Matrix

**Context:** The differential suite proved kernel-vs-oracle consensus is cheap to obtain
(`SeccompDifferentialVerdictTest`). Today those guarantees exist only at test time. Production
currently verifies installation coarsely (`prctl(PR_GET_SECCOMP) == MODE_FILTER`, filter-depth
bookkeeping) but never validates *decision content* after install. The JA misencoding
(issue-20260823-140500) would have been caught deterministically in production by a single
post-install liveness probe instead of manifesting as SIGSEGV chaos.

**Needed:**
1. After successful filter install (non-supervised paths), execute a bounded self-check on the
   installing thread:
   - liveness: benign syscall (getpid via raw syscall, not JVM API) succeeds;
   - one matched probe per distinct native action group in the compiled program (EPERM-class probes
     invoked via raw syscall and verified against the simulator-predicted errno; ALLOW probes
     verified to succeed);
   - KILL_* groups are skipped in-process by design (issue-20260823-172000).
2. On mismatch: fail closed per operator fallback policy (default FAIL → throw with full program
   dump + probe table attached).
3. Gate behind opt-in constant/system property defaulting ON for debug/classifier builds; measure
   overhead (< ~1ms; it is a handful of syscalls) before enabling everywhere.
4. Reuse `SyscallProbeMatrix` from :platform so test-time and run-time verification cannot diverge.

## ❓ Open Questions
1. Should self-check results be cached per compiled-program identity (BpfNativeCache key) to avoid
   re-probing identical filters across wrapped-executor tasks?
