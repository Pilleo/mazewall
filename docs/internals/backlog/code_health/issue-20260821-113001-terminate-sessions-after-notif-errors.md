---
title: "Terminate sessions after notification receive errors"
severity: "MEDIUM"
status: "resolved"
priority: medium
resolved_in_commit: 74ad6616
resolved_by: "already fixed in commit"
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3796525642
---

# 🟡 [Severity: MEDIUM]: Terminate sessions after notification receive errors

**Context:** When `SECCOMP_IOCTL_NOTIF_RECV` returns a non-`EINTR` error such as `EBADF`, `EINVAL`, or `ENOMEM`, `onSuccess` is skipped and the method returns `Continue` without changing `isTerminated`. The daemon can consequently keep polling a permanently failed listener and retain its connection resources; the previous supervisor handler broke the session on this path.

**Problem:**
- Non-EINTR errors not handled
- Session continues polling failed listener
- Connection resources retained

**Impact:**
- Daemon keeps polling permanently failed listener
- Connection resources not freed

**Needed:**
1. Handle non-benign receive errors by terminating session
2. Set isTerminated on error
3. Break session loop on error

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3796525642
