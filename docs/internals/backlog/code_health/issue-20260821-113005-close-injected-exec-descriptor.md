---
title: "Close the injected exec descriptor when rewrite fails"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912186
---

# 🟡 [Severity: MEDIUM]: Close the injected exec descriptor when rewrite fails

**Context:** When `SECCOMP_IOCTL_NOTIF_ADDFD` succeeds but the parent register rewrite is rejected—as it currently always is—the injected `O_PATH` descriptor already exists in the tracee, and this error response cannot remove it. Because the denied syscall never executes, `O_CLOEXEC` also does not close it, so repeated authorized exec attempts leak one tracee descriptor.

**Problem:**
- ADDFD succeeds but rewrite rejected
- Injected O_PATH descriptor exists in tracee
- Error response can't remove it
- O_CLOEXEC doesn't close it (syscall never executes)
- Descriptor leak on repeated exec attempts

**Impact:**
- Descriptor leak in tracee
- Resource exhaustion possible

**Needed:**
1. Close injected descriptor when rewrite fails
2. Or use a different mechanism that can be cleaned up
3. Prevent descriptor leak

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912186
