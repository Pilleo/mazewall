---
title: "Route custom supervised syscalls through JVM validation"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorNotificationMachine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7kBdQA
---

# 🔴 [Severity: HIGH]: Route custom supervised syscalls through JVM validation

**Context:** When a `StacktraceScopingPolicy.handlers` map contains any syscall outside the six hard-coded categories (for example `UNLINK`, `IOCTL`, or `CLONE3`), installation changes that syscall to `ACT_NOTIFY`, but `SupervisorNotificationMachine.evaluateFastPath()` classifies it as `SupervisedKind.Unknown` and returns `Abort(EPERM, "unsupervised syscall number")` at line 45-46 before `sendRequestToJvm` runs. The configured handler is therefore never invoked and every occurrence is denied.

**Problem:**
- `SupervisedKind.classify()` only recognizes 6 syscall categories (open, connect, accept, execve, fork/vfork/clone)
- Custom supervised syscalls get classified as `Unknown`
- Fast-path aborts with EPERM instead of routing to JVM handler
- Policy-configured handlers are never invoked

**Impact:**
- Security: Custom supervised syscall handlers cannot function
- Denial of service: Legitimate supervised syscalls are always denied
- Policy violation: Configured handlers are ignored

**Needed:**
1. Pass handler configuration context into `evaluateFastPath`
2. Check if syscall is in handler map before classifying as Unknown
3. Route policy-configured supervised syscalls to `AskJvm`
4. Only abort genuinely unexpected notification numbers

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825587200
