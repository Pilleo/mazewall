---
title: "Union-Aware Self-Verification for Stacked Seccomp Filters"
severity: "LOW"
status: "open"
priority: medium
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/InstallSelfVerifier.kt"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260823-172003"
paperclip_issue_id: 18533426-3694-4d49-aec1-38c2983e3cb1
---

# 🟡 [Severity: LOW]: Union-Aware Self-Verification for Stacked Seccomp Filters

**Context:** Install-time self-verification currently skips when `priorFilterDepth > 0`
(InstallSelfVerifier.verify guard, added 2026-08-24): the kernel enforces the **union** of all
stacked filters on a thread, so a single-program oracle cannot predict verdicts. Empirically
confirmed by `ProcessContainmentTest.testThreadDepth`: after an earlier stacked layer explicitly
blocks `GETPID`, a later layer's program predicts ALLOW for getpid while the real kernel returns
EPERM — the verifier correctly flagged the divergence, but the divergence is *expected* stack
semantics, not a bug.

**Needed:**
1. Feed the verifier the **merged projection** instead of one program: `ContainerState` already
   maintains the union (`syscallActions` merged by priority + effective default). Build expected
   verdicts from that projection (via BpfSimulator against a synthesized program, or by extending
   the oracle to evaluate action maps directly).
2. Probe selection then covers: NRs denied by ANY layer (must stay denied), NRs allowed by ALL
   layers (liveness candidates), and arg-inspected NRs remain excluded as today.
3. Remove the `priorFilterDepth > 0` skip once union simulation lands; keep KILL_* exclusions.
4. Differential-suite tie-in: add one stacking scenario to `SeccompDifferentialVerdictTest` that
   asserts union semantics explicitly (deny-then-allow ⇒ deny).

## ❓ Open Questions
1. None.
