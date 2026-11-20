---
title: "Repoll canceled seccomp receives without retiring a live listener"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: [":platform", ":enforcer", ":profiler"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/UserNotifReceive.kt"
target_symbols:
  - "UserNotifReceive"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Repoll canceled seccomp receives without retiring a live listener

**Context:**
Repository-wide review, 2026-11-04: [PN05-01](../reviews/2026-11-04-repository-review-findings.md#pn05-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/confidence/boundary:** confirmed source correctness defect; high confidence; shared core USER_NOTIF transport, directly affecting optional profiler. Native schedule not reproduced in this lane.

**Trigger and evidence:** a signal cancels the notified syscall before/during RECV while another thread still uses the same filter. Linux legitimately returns RECV `ENOENT`. `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/UserNotifReceive.kt:50–56` recognizes EINTR/EAGAIN/EBADF but classifies ENOENT as `Failed`. `SeccompSessionHandler.kt:143–153` then sets `isTerminated` and breaks before accepting any notification. Connection cleanup closes the listener (`SeccompDaemonEngine.kt:382–385`; `platform/src/main/kotlin/io/mazewall/ffi/networking/SeccompConnection.kt:65–74`). No terminal producer record can honestly certify surviving filter users.

**Effect:** the cancelled notification needs no reply, but all later trapped syscalls on surviving permanently filtered threads now fail with ENOSYS. This can break unrelated background JVM work. It is not a reason to issue a fabricated reply, and is distinct from SEND-side ENOENT and from proven daemon death.

**Authority:** Linux v6.18 `kernel/seccomp.c:1568–1587` returns ENOENT when no current notification remains; the [RECV error contract](https://man7.org/linux/man-pages/man2/seccomp_unotify.2.html) explicitly includes a target syscall interrupted by a signal handler. Last-listener loss causing ENOSYS is also described there. The inspected official [kernel source](https://github.com/torvalds/linux/blob/v6.18/kernel/seccomp.c#L1552-L1588) corroborates the condition.

**Smallest correction/check:** classify RECV ENOENT as a benign repoll outcome, retaining one-reader/readiness discipline and real fatal errno handling. Inject ENOENT followed by a valid notification, assert no listener retirement or reply for the missing notification and one normal reply for the subsequent one. Then qualify with an owned two-tracee signal/restart case on a supported kernel. Do not alter the already-defined SEND-side terminal policy in this narrow fix.

**Tracking/dependencies:** **NEW residual classification gap** after resolved `docs/internals/backlog/resolved/issue-20260821-113001-terminate-sessions-after-notif-errors.md` (which required termination for non-benign EBADF/EINVAL/ENOMEM). No dedicated RECV-ENOENT issue found. Fix the shared adapter once; it unblocks reliable profiler/supervisor cancellation qualification. Do not claim it diagnoses the historical `20261003-131111-752` failure.

**Independent native review:** Astra confirms the classification/schedule and ranks it P2, fail-closed availability/correctness, not a containment bypass (`05-astra-notif.md:51–68`). It rejects a separate blocking allegation based only on RECV ignoring `O_NONBLOCK`: fresh POLLIN plus the sole reader/one attempt suffices in inspected upstream Linux 5.0/5.5/5.15/6.18; EINTR/ENOENT must return to poll.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235246-361-repoll-canceled-seccomp-receives-without-retiring-  file: issue-20261103-235246-361-repoll-canceled-seccomp-receives-without-retiring-.md -->
