---
title: "Eliminate potential TOCTOU on pointer-based argument resolution inside ProfilerSessionHandler"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
open_questions: false
---

# 🔴 [Severity: HIGH]: Eliminate potential TOCTOU on pointer-based argument resolution inside ProfilerSessionHandler

**Context:**
The `ProfilerSessionHandler` intercepts system calls with pointer arguments (such as `openat` and `connect`) to resolve and profile them. It reads the remote tracee process's memory space using `/proc/<pid>/mem` or `process_vm_readv` via the `ProfilerMemoryReader` up to a maximum limit (default 4096 bytes).

**The Threat Model & Boundary:**
Although seccomp user notifications block the calling thread of the tracee inside the kernel, other sibling threads in the same address space are NOT blocked.
Under highly concurrent execution, a sibling thread could mutate the memory pointed to by the system call arguments (such as the path string buffer or the socket address struct) AFTER the `ProfilerSessionHandler` has read/resolved the path, but BEFORE the system call actually runs in the kernel (if returning `SECCOMP_USER_NOTIF_FLAG_CONTINUE`).

**Architectural Decision:**
1. **Scope Classification:** Profiling is an *offline, developer-time diagnostic phase* run on trusted code to observe behavior and generate a `BillOfBehavior`. Production containment is handled by `:enforcer`.
2. **Implementation:**
   - In `:profiler`, retain `SECCOMP_USER_NOTIF_FLAG_CONTINUE` with memory reading via `/proc/<pid>/mem` to preserve JVM file descriptor allocation and avoid interfering with application runtime state during profiling runs.
   - In `:enforcer`, production enforcement uses `SupervisedOpen` with `SECCOMP_IOCTL_NOTIF_ADDFD` (secure FD injection), which eliminates pointer TOCTOU vulnerabilities completely by opening the verified file descriptor inside the daemon and injecting it into the tracee table.
   - Document this threat model distinction and TOCTOU boundary clearly in `docs/internals/designs/profiler/profiler-design.md`.

**Resolution (2026-08-23):** Decision items implemented/verified:
1. `:profiler` retains FLAG_CONTINUE + process_vm_readv by design (offline diagnostic).
2. Verified `:enforcer` production enforcement uses `SupervisedOpen` +
   `SECCOMP_IOCTL_NOTIF_ADDFD` (handleInjectFd -> SECCOMP_IOCTL_NOTIF_ADDFD), eliminating
   pointer TOCTOU for approved opens.
3. Threat-model boundary documented in
   `docs/internals/designs/profiler/profiler-design.md` (Zero-Crash Execution section): profiler
   observations describe the *requested* syscall; concurrent mutation between read and CONTINUE
   is accepted for diagnostics and explicitly out of scope for containment.
