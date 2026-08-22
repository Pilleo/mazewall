---
title: "Exec register rewrite must actually modify tracee registers before CONTINUE"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7iSmJE
---

# 🔴 [Severity: HIGH]: Exec register rewrite must actually modify tracee registers

**Review (2026-08-21):** STALE/DUPLICATE: rewrite is unimplemented (issue-20260817-033800) but current handler fail-closes instead of CONTINUE on original pathname.

**Review (2026-08-21):** STALE as a live CONTINUE-TOCTOU. `completeParentExecRewrite()` always NACKs; the handler **denies** (`sendSeccompError`) when rewrite is false. Remaining implementation is `issue-20260817-033800`. Fd leak after ADDFD+NACK is `113005-close-injected-exec-descriptor`.

**Do not:**
- CONTINUE the original `execve` so the program “works” while rewrite is unimplemented.
- Claim this issue is fixed by adding comments only.
- Implement SETREGS in this ticket without the dedicated rewrite issue’s design (deadlock with USER_NOTIF).

**Problem:**
- Register rewrite is not implemented (issue-20260817-033800)
- Parent only reads values and sends ACK, doesn't modify tracee
- CONTINUE executes original exec with original pathname
- TOCTOU vulnerability remains

**Impact:**
- Security: exec TOCTOU escape possible
- Supervised exec validation can be bypassed by concurrent path mutation

**Needed:**
1. Implement actual register rewriting in parent (via ptrace POKETEXT/POKEDATA)
2. Do not send CONTINUE unless register rewrite was verified to succeed
3. Verify rewritten registers in tracee before continuing

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3796525654
