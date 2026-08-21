---
title: "Preserve requested close-on-exec state on injected FDs"
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
related_thread: 3819861583
---

# 🟡 [Severity: MEDIUM]: Preserve requested close-on-exec state on injected FDs

**Context:** For supervised `open`/`openat` calls without `O_CLOEXEC`, and `accept`/`accept4` calls without `SOCK_CLOEXEC`, this unconditionally marks the injected descriptor close-on-exec. `FD_CLOEXEC` belongs to the descriptor and is not inherited from the supervisor's source file description, so an allowed later exec closes a descriptor that the original syscall contract required to remain open.

**Problem:**
- Unconditionally marks injected FD as close-on-exec
- FD_CLOEXEC not inherited from source
- Later exec closes descriptor that should remain open

**Impact:**
- Descriptors closed when they should remain open
- Functionality: syscall contract violated

**Needed:**
1. Set newfd_flags from intercepted syscall flags
2. Preserve O_CLOEXEC/SOCK_CLOEXEC from original call
3. Respect original syscall contract

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861583
