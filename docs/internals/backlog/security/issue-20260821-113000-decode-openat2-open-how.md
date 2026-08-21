---
title: "Decode openat2's open_how before injecting the descriptor"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789298
---

# 🔴 [Severity: HIGH]: Decode openat2's open_how before injecting the descriptor

**Context:** When the supervised syscall is `openat2`, argument 2 is a pointer to `struct open_how`, not an integer flags value. Converting that pointer to `Int` and calling ordinary `open`/`openat` uses address bits as flags and discards `mode` and `resolve` constraints such as `RESOLVE_BENEATH` or `RESOLVE_NO_SYMLINKS`, so an allowed call can fail or open a target outside the intended constraints.

**Problem:**
- openat2 arg2 is struct open_how pointer, not int flags
- Pointer converted to Int loses struct data
- mode and resolve constraints discarded
- Call may fail or violate constraints

**Impact:**
- Security: constraints not enforced
- Functionality: calls may fail

**Needed:**
1. Parse struct open_how from tracee memory
2. Extract flags, mode, resolve from struct
3. Use correct parameters for open/openat

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789298
