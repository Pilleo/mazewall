---
title: "Perform the exec rewrite before acknowledging it"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819982838
---

# 🔴 [Severity: HIGH]: Perform the exec rewrite before acknowledging it

**Review (2026-08-21):** STALE as a live TOCTOU: completeParentExecRewrite() always ACKs false and the handler denies (does not CONTINUE) when rewrite fails. Remaining work is issue-20260817-033800 (implement rewrite), not this CONTINUE-path claim. Duplicate pair: 000010.

**Context:** When an authorized child reaches this path, all rewrite fields are discarded and the listener ACKs merely because `ADDFD` returned a nonnegative descriptor; the daemon then sends `CONTINUE`, which resumes the original pathname-based `execve`. Fresh evidence is that `completeParentExecRewrite()` explicitly documents that no rewrite occurs, so a sibling can still mutate the tracee's pathname.

**Problem:**
- Rewrite fields are discarded before ACK
- Daemon sends CONTINUE with original pathname
- Sibling threads can mutate pathname before exec
- TOCTOU vulnerability remains

**Impact:**
- Security: exec TOCTOU escape possible
- Supervised exec validation can be bypassed

**Needed:**
1. Perform actual register rewriting before ACK
2. Do not send CONTINUE unless register rewrite was verified
3. Implement ptrace POKETEXT/POKEDATA for register modification

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819982838
