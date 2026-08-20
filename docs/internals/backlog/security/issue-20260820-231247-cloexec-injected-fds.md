---
title: "Preserve requested close-on-exec state on injected FDs"
severity: "MEDIUM"
status: "open"
priority: medium
component: "enforcer"
dependencies: []
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
---

# Preserve Requested Close-on-Exec State on Injected FDs

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

For supervised `open`/`openat` calls without `O_CLOEXEC`, and `accept`/`accept4` calls without `SOCK_CLOEXEC`, this unconditionally marks the injected descriptor close-on-exec. `FD_CLOEXEC` belongs to the descriptor and is not inherited from the supervisor's source file description, so an allowed later exec closes a descriptor that the original syscall contract required to remain open.

## Impact

- Descriptors closed unexpectedly on exec
- Violation of syscall contract
- Application breakage when exec is expected to inherit open FDs

## Solution

Set `newfd_flags` from the intercepted syscall's requested flags rather than forcing CLOEXEC for every injected FD.

## Related Files

- `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt` - Line 682
