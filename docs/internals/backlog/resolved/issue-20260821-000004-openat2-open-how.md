---
title: "Decode openat2's open_how before injecting the descriptor"
severity: "HIGH"
status: "resolved"
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
related_thread: PRRT_kwDOScnnEM6a5aRK
---

# 🔴 [Severity: P1]: Decode openat2's open_how before injecting the descriptor

**Review (2026-08-21):** DUPLICATE of issue-20260821-113000-decode-openat2-open-how (keep that one open).

**Context:** When the supervised syscall is `openat2`, argument 2 is a pointer to `struct open_how`, not an integer flags value. Converting that pointer to `Int` and calling ordinary `open`/`openat` uses address bits as flags and discards `mode` and `resolve` constraints such as `RESOLVE_BENEATH` or `RESOLVE_NO_SYMLINKS`, so an allowed call can fail or open a target under materially different semantics.

**Problem:**
- `SupervisorSessionHandler.kt:720` - openat2 arg2 is a pointer, not integer flags
- Pointer value used as flags bits
- mode and resolve constraints (RESOLVE_BENEATH, RESOLVE_NO_SYMLINKS) are discarded
- Can open with incorrect semantics or fail unexpectedly

**Impact:**
- Security: Path resolution constraints can be bypassed
- Correctness: Syscall may fail or behave differently than expected
- Semantic mismatch between validation and execution

**Needed:**
1. Read and validate `open_how`, emulate it with `openat2`, or deny the notification.

**Notes:** openat2 was introduced in Linux 5.6 with the open_how structure that includes flags, mode, and resolve.
