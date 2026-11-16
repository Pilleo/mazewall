---
title: "Investigate supervisor daemon EBADF during session cleanup"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
target_symbols:
  - "recordCleanup"
open_questions: false
---

# 🟡 [Severity: HIGH]: Investigate supervisor daemon EBADF during session cleanup

**Context:**
Real supervised Legacy sessions in retained 078, 079 and 080 runs report NativeCloseFailure EBADF during daemon cleanup despite successful identity assertions and process exit; exact descriptor ownership and physical retirement remain unknown. The same class of failure appears in 17 lines of the earlier 074 full build and 20 lines of 080 across multiple supervised scenarios. The managed test checks policy/session identity, runtime drain, worker exit, logical lifetime retirement and daemon process exit; it does not assert the daemon cleanup outcome. EBADF alone does not establish a double close or a benign result.

**Needed:**
1. Trace actual session socket and listener ownership and close outcomes through `SeccompDaemonEngine.recordCleanup`, `SocketManager.close`, and the real `SupervisorProxyIntegrationTest` Legacy session.
2. Add a discriminating regression asserting authoritative daemon cleanup and retirement without suppressing errors or weakening ownership checks. Preserve the 078, 079 and 080 evidence and distinguish this issue from profiler/listener issue 752.

---
<!-- id: issue-20261108-113441-869-investigate-supervisor-daemon-ebadf-during-session  file: issue-20261108-113441-869-investigate-supervisor-daemon-ebadf-during-session.md -->


## Joined control-socket and eventfd owner acceptance

The controller accepted the bounded control-socket correction together with the reviewed eventfd owner-retirement/admission work as **ACCEPT_WITH_EXPLICIT_LIMITS**, per the [joined controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-eventfd-ownership-full-build-fixed2/controller-decision.md). The raw full gate passed without a documentation/source exception: Gradle/container/host all exited 0 in 33m37s; 19 outer producers, 690 suites, 4,579 cases, 45 skips, 4,534 non-skipped, zero failures/errors. Issue 869 remains open and unarchived.

The daemon now retains the actual session and bounded original admission reservation; daemon-created verdict exchanges borrow the control socket, whose original connection is the sole closer. Connection-close Unknown remains counted against the same maximum without forcing first-error shutdown, preserving the accepted B4 cleanup channel; it is never Complete or replayed. Direct admission refusal leaves the caller owning the FD, and an unadmitted Active RunSession is refused. The previously failing peer-address integration and unchanged B4 terminal-close helper-retirement controls passed.

Zero socket `NativeCloseFailure` EBADF matches in this run do not close this issue or establish individual listener/helper retirement. Three profiler shutdown ENOSYS occurrences and one eventfd EBADF in a mocked fixture remain unexplained; eventfd cause/retirement remain UNKNOWN and are tracked separately under [issue 677](issue-20261109-063230-677-retain-eventfd-completion-wakeup-ownership-after-c.md). Preserve prior failed packets, wrong-home incidents, and the historical docs source-guard 99.
