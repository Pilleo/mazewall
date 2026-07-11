---
title: "TOCTOU in `USER_NOTIF` Argument Dereferencing"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: TOCTOU in `USER_NOTIF` Argument Dereferencing

**Context:**
**Hypothesis:** When the Profiler Daemon receives a `USER_NOTIF` for a syscall like `openat`, it uses `process_vm_readv` to read the path string from the tracee's memory. Because the tracee thread is stopped but other sibling threads in the same process are still running, a malicious or poorly synchronized sibling thread can rewrite the path string in memory *after* the BPF filter has triggered the notification but *before* the Profiler reads it.

The Linux `SECCOMP_RET_USER_NOTIF` mechanism stops the thread making the system call. The daemon reads the arguments from the tracee's memory. Since memory is shared across threads, a TOCTOU (Time of Check to Time of Use) is possible. The kernel will eventually execute the syscall with the *current* memory contents, which might differ from what the profiler logged.


**Needed:**
1. Document that the `USER_NOTIF` Tier S Profiler is vulnerable to concurrent memory mutation (TOCTOU) and is strictly intended for profiling trusted/benign workloads, not for intercepting malicious evasion attempts.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.profiler.engine.ProfilerDaemon``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
