---
title: SupervisorSessionHandler TOCTOU vulnerability during string extraction for paths
type: issue
status: open
priority: high
labels: ["security", "enforcer", "toctou", "sandbox-bypass"]
component: enforcer
target_modules: [":enforcer"]
target_files: ["io.mazewall.enforcer.supervisor.SupervisorSessionHandler.kt"]
---

# Issue: `SupervisorSessionHandler` Path Extraction TOCTOU

## Context
In `SupervisorSessionHandler.kt`, `extractNotificationArgs` reads path strings from the tracee's memory:
```kotlin
when (nr) {
    arch.open, arch.execve -> {
        pathStr = readStringFromProcess(tid, args[0])
    }
```

## The Bug
The supervisor extracts the string `pathStr` from the tracee's memory via `process_vm_readv` (inside `readStringFromProcess`). It then sends this path to the JVM for validation. The JVM validates `pathStr` and returns an ALLOW or DENY. If ALLOW, the supervisor sends `SECCOMP_USER_NOTIF_FLAG_CONTINUE` to the kernel.

Because the arguments live in the tracee's user-space memory, a malicious sibling thread in the tracee can modify the string at `args[0]` **after** `readStringFromProcess` has read it, but **before** the kernel executes the system call.

This is a classic Time-of-Check to Time-of-Use (TOCTOU) race condition inherent to `SECCOMP_RET_USER_NOTIF`.

## Security / Stability Impact
- **Sandbox Bypass**: An attacker can initiate `open("/allowed/path")`. The supervisor reads `/allowed/path`, approves it, and signals CONTINUE. In the microscopic window before the kernel executes `open`, the attacker thread rewrites the memory to `/etc/shadow`. The kernel then opens `/etc/shadow`.

## Recommendation
This is a known limitation of ptrace/process_vm_readv based profiling (Tier S/P), documented in `Profiler.kt` and `StraceProfiler.kt`. However, `SupervisorSessionHandler` is in the `:enforcer` module, suggesting it's part of the actual enforcement mechanism.
If `SupervisorSessionHandler` is actively enforcing policy (not just profiling), it MUST NOT use `SECCOMP_USER_NOTIF_FLAG_CONTINUE` for pointer-based arguments (like paths).
Instead, it should inject the file descriptor (e.g. by opening the file itself, and returning the FD using `addfd` or `SECCOMP_USER_NOTIF_FLAG_CONTINUE` if only landlock is doing the filesystem checks).
The memory states that Landlock provides race-free containment, so if `SupervisorSessionHandler` relies on Landlock for filesystem, it might be safe, but it's extracting the path for "JVM validation listener", which could be making decisions based on it.

Ensure that the design docs explicitly warn about this, or enforce that JVM validation listener cannot override Landlock Denies, and only acts as an additional restrictor.
