---
title: "Retain eventfd completion wakeup ownership after close failure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "platform"
target_modules: ["platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/EventFdCompletionWakeup.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
  - "platform/src/test/kotlin/io/mazewall/platform/seccomp/daemon/EventFdCompletionWakeupTest.kt"
  - "platform/src/test/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandlerTest.kt"
target_symbols:
  - "EventFdCompletionWakeup"
  - "SeccompSessionHandler"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain eventfd completion wakeup ownership after close failure

**Context:**
Investigate and correct eventfd completion-wakeup close failure ownership and outcome retention. EventFdCompletionWakeup.close atomically marks closed before native close, then its finally path calls FileDescriptor.closedView and arena.close even when native close reports an error; later close calls return without retry. SeccompSessionHandler.closeWithResult routes completionWakeup.close through the cleanup interpreter, while SeccompDaemonEngine.recordCleanup retains/logs the failure outcome but not the exact eventfd owner. A selected-handler full-build diagnostic recorded one separate eventfd completion-wakeup EBADF line, but no native-result or physical-retirement receipt establishes its cause or disposition. Treat cause and retirement as UNKNOWN. Keep this eventfd issue distinct from the socket cleanup follow-up [issue 869](issue-20261108-113441-869-investigate-supervisor-daemon-ebadf-during-session.md), the profiler endpoint-retirement follow-up tracked under existing [issue 752](issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int.md) per accepted triage 056, and socket-helper prewarm [issue 051](issue-20261109-054828-051-investigate-socket-helper-prewarm-cleanup-timeout-.md). Do not resolve or infer causality for any of those findings. Preserve fail-closed behavior and do not replay a close after uncertain dispatch. The relevant selected-handler diagnostic packet records one eventfd match separately from 20 socket NativeCloseFailure EBADF and three profiler shutdown matches; all retirement dispositions remain UNKNOWN.

**Needed:**
1. Trace eventfd descriptor ownership from creation through close dispatch, reactor signal/drain quiescence, cleanup outcome recording and owner lifetime; distinguish pre-dispatch failure from uncertain close dispatch without replay.
2. Design retained-owner cleanup and explicit actual signal/drain-quiescence receipts; add focused tests for real descriptor ownership, error reporting, and no close replay after uncertain dispatch.

---
<!-- id: issue-20261109-063230-677-retain-eventfd-completion-wakeup-ownership-after-c  file: issue-20261109-063230-677-retain-eventfd-completion-wakeup-ownership-after-c.md -->


## Joined eventfd and control-socket owner acceptance

The controller accepted the reviewed 677 owner-retirement/admission work and bounded 869 control-socket correction as **ACCEPT_WITH_EXPLICIT_LIMITS**, per the [joined controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-eventfd-ownership-full-build-fixed2/controller-decision.md). The raw full gate passed without a source exception: Gradle, container and host all exited 0 in 33m37s; 19 outer producers, 690 suites, 4,579 cases, 45 skips, 4,534 non-skipped cases and zero failures/errors. This acceptance does not archive either issue.

For eventfd ownership, Pending retains the exact owner until counted signal/drain users physically exit; dispatched Unknown retains its original failure and never replays native close. Eventfd Unknown cuts off admission and causes fail-closed global shutdown. Direct duplicate refusal leaves caller ownership untouched. Direct admission-capacity/cutoff refusal and unadmitted Active RunSession refusal are explicit compatibility costs.

For control sockets, the daemon retains the actual session and bounded original admission reservation. Connection-close Unknown remains counted against the same maximum without forcing first-error shutdown, preserving the accepted B4 cleanup channel; it is never Complete or replayed. Daemon-created verdict exchanges borrow the control socket; the original connection is its sole closer. The previously failing peer-address integration and unchanged B4 terminal-close helper-retirement controls passed.

Three profiler shutdown ENOSYS occurrences and one eventfd EBADF from a mocked consecutive-connection fixture remain unexplained; the eventfd cause and physical retirement are UNKNOWN. Zero socket `NativeCloseFailure` EBADF matches in this run do not close historical issue 869. No individual listener/helper retirement follows from whole-container retirement. Preserve all earlier failed packets, wrong-Gradle-home incidents, and the prior documentation source-guard 99 as immutable history. Construction/pre-return eventfd ownership, arena-close injection, VM failures, cached static supplements, absent native Gradle task, skipped suites and broader native/Live/original qualification limits remain. Issue 677 stays open; no automatic archival follows.
