---
title: "Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 229
---

# 🔴 [Severity: MEDIUM]: Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets

**Context:**
**Hypothesis:** The `ProfilerSocket` creates a `socket(AF_UNIX, SOCK_STREAM, 0)`. It does not apply the `O_CLOEXEC` (Close-on-Exec) flag. If the profiled JVM spawns a child process (e.g. via `ProcessBuilder`) while the profiler connection is open, the child process inherits the open socket file descriptor to the Profiler Daemon.

`ProfilerSocket.kt` makes the raw Linux `socket` downcall. Because `SOCK_CLOEXEC` is not bitwise OR'd into the socket type, the descriptor remains open across `execve`. Although the Tier 2 policy might block `execve`, if a user allows `execve` (or uses Tier S process-wide profiling without blocking `execve`), child processes will unknowingly hold a reference to the daemon socket.


**Needed:**
1. Always bitwise OR `NativeConstants.SOCK_CLOEXEC` into the `type` argument when calling `LinuxNative.socket`.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.profiler.internal.ProfilerSocket``
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
