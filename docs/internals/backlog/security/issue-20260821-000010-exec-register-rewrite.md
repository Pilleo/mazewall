---
title: "Exec register rewrite must actually modify tracee registers before CONTINUE"
severity: "HIGH"
status: "open"
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

**Context:** When an exec is authorized, the code sends `CONTINUE` after `requestParentRegisterRewrite()` reports success. However, the parent implementation explicitly only reads the six register values and acknowledges `injectedFd >= 0`; it never actually changes the tracee registers. The kernel therefore executes the original pathname-based `execve`/`execveat`, leaving the validated path pointer mutable by sibling threads and restoring the exec TOCTOU escape this code claims to prevent.

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
