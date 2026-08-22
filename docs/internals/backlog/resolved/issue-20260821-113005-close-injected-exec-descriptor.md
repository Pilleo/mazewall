---
title: "Close the injected exec descriptor when rewrite fails"
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
autonomy: "supervised"
related_pr: 512
related_thread: 3825912186
---

# 🟡 [Severity: MEDIUM]: Close the injected exec descriptor when rewrite fails

**Review (2026-08-21):** Still present. Exec **rewrite** itself is unimplemented (`issue-20260817-033800`); the handler **denies** instead of CONTINUE. This issue is the **tracee fd leak** after ADDFD succeeds and rewrite NACKs.

**Current tree:** Exec path: supervisor `open(O_PATH|O_CLOEXEC)` → `SECCOMP_IOCTL_NOTIF_ADDFD` copies that fd into the **tracee** → `requestParentRegisterRewrite` → parent always `sendExecRewriteAck(false)` → handler `sendSeccompError` (does not CONTINUE). `SafeLocalFd.use` closes the **supervisor-local** fd only. The injected tracee fd remains. `O_CLOEXEC` would close it on a successful exec image change; the denied syscall never execs, so CLOEXEC does not run. Repeated authorized exec attempts leak one fd per attempt in the tracee.

**Do not:**
- CONTINUE the original `execve` to “let CLOEXEC fire” (that reopens the pathname TOCTOU `033800` is about).
- Close a random tracee fd number without using the ADDFD return value.
- Treat supervisor `close(localFd)` as cleaning the tracee.

**Do:**
1. Keep the nonnegative fd returned by `SECCOMP_IOCTL_NOTIF_ADDFD`.
2. If rewrite fails, close **that** fd in the tracee (pidfd_getfd + close, or a documented supervisor-side mechanism that actually affects the tracee), then send the error response.
3. Still deny; do not CONTINUE.

**Tests:** Mock ADDFD returning 99, rewrite false. Assert a close of tracee fd 99 (or pidfd_getfd(99)+close) happens before the error ioctl. Assert no CONTINUE.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912186
