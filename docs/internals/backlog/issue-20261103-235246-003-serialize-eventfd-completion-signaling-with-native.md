---
title: "Serialize eventfd completion signaling with native retirement"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: [":platform", ":profiler"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/EventFdCompletionWakeup.kt"
target_symbols:
  - "EventFdCompletionWakeup"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Serialize eventfd completion signaling with native retirement

**Context:**
Repository-wide review, 2026-11-04: [PLAT-07](../reviews/2026-11-04-repository-review-findings.md#plat-07). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · concurrency/native ownership · confirmed unsynchronized source interleaving; native effects unexecuted · high confidence in race, moderate confidence in unqualified wrong-resource outcome · profiler async completion/shared platform wakeup.**

- **Evidence:** `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/EventFdCompletionWakeup.kt:47–59` checks `closed` then writes; `78–88` can concurrently close the fd and arena. `RealNativeMemory.write` checks the segment and descriptor, then passes the integer to native dispatch (`platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt:774–781`). No shared operation/close lock spans those steps. Profiler completion calls `wakeup.signal()` from `result.whenComplete` (`profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerSessionHandler.kt:147–155`). Exceptional worker completion occurs outside the job monitor (`profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerPreparationPool.kt:140–146`); `Job.close` (`103–112`) need not join that already-running callback. Ordinary successful completion under the job monitor is not the asserted race.
- **Interleaving:** a preparation failure completes the future and enters signal; pause after `closed=false` or after the native liveness check. The reactor observes terminal work, closes the job and session, and closes the wakeup. Resuming before validation can throw on the closed arena; resuming after validation but before the raw write can encounter EBADF. If the fd number is reused and the write buffer's arena is still open, the raw write can target the replacement resource. The atomic flag prevents later admission, not an already-admitted operation.
- **Effect:** late callback failure is captured by the ignored dependent future; native resource identity is not protected through signaling. The wrong-resource schedule is source-supported but has not been exercised against a real kernel descriptor and must not be reported as an observed corruption.
- **Smallest correction:** serialize the short nonblocking signal/drain operations with close under the wakeup owner's lock, or use an explicit admitted-operation lease whose retirement waits for those operations. Keep the reactor free of blocking helper waits; do not globally join preparation workers as the fix.
- **Regression:** barrier-controlled signal versus close before/after liveness validation; no engine write after retirement, no freed-buffer access, close once, and a real nonblocking eventfd positive control. Existing `EventFdCompletionWakeupTest.kt:11–29` is sequential only.
- **Tracking:** **NEW exact race** after wakeup/class searches. Related resolved `issue-20260910-225751-953-bound-user-notif-reactor-work-and-terminal-reply-o.md` covers terminal reply authority, not eventfd physical retirement; resolved `issue-20261015-172121-857-preserve-truthful-profiler-completion-across-termi.md` is adjacent profiler lifecycle work, not proof of signal-close serialization.
- **Dependency:** an owner-local fix can resolve this without redesigning the general FD API. PLAT-03's broader send/close protection should use the same ownership principle.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235246-003-serialize-eventfd-completion-signaling-with-native  file: issue-20261103-235246-003-serialize-eventfd-completion-signaling-with-native.md -->
