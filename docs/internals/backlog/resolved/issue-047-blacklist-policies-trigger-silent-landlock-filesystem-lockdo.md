---
title: "Blacklist policies trigger silent Landlock filesystem lockdown due to `io_uring` check"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Blacklist policies trigger silent Landlock filesystem lockdown due to `io_uring` check

**Status:** RESOLVED (July 2026)
**Target:** `io.mazewall.enforcer.ContainedExecutors.kt` (specifically `needsLandlock` calculation)
**Context:** In `ContainedExecutors.kt`, `needsLandlock` was implicitly triggered if `io_uring_setup` was allowed, even if no filesystem paths were specified. This caused Landlock to be applied with an empty ruleset, permanently locking down the filesystem for the thread.
- *Threat Model Nuance:* Seccomp BPF filters are unable to inspect shared memory Submission Queue Entries (SQEs) inside `io_uring` queues at syscall entry time. Thus, an attacker can bypass path-based seccomp blocks (e.g. `openat`) by submitting operations asynchronously via `io_uring`. Since kernel workers (`io-wq`) inherit the thread's Landlock credentials, applying Landlock prevents this bypass.
- *Root Cause:* If a blacklist policy allows `io_uring_setup` but blocks direct `open`/`openat`, `needsLandlock` evaluated to `true`. If the user did not define any allowed filesystem paths, Landlock was applied with empty read/write lists, effectively locking the thread out of all file operations.
**Fix:**
1. Landlock is now only enforced if actual allowed filesystem paths are defined.
2. If Landlock is not enforced (no allowed paths), and the policy restricts `open` or `openat` but allows `io_uring_setup`, `io_uring_setup` is automatically blocked (mapped to `ACT_ERRNO`) to prevent the bypass vector.



## Profiler, SBoB Parser & Exception Mapping Diagnostics
