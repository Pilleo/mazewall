---
title: "Publish native invocation watermarks independently of completion order"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler-native/src/agent.cpp"
target_symbols:
  - "write_definitions"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Publish native invocation watermarks independently of completion order

**Context:**
Repository-wide review, 2026-11-04: [TE-04](../../reviews/2026-11-04-repository-review-findings.md#te-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · confirmed source defect · high confidence · release boundary: opt-in native live snapshot admission/v2 hybrid recovery. NEW producer-ordering defect.**

- **Trigger/evidence:** thread A allocates invocation `N` at `profiler-native/src/agent.cpp:913–920` then stalls in capture. Thread B allocates `N+1` and records its definition first. A records later; append order under the definitions mutex is now `[N+1, N]` (`743–748`). `write_definitions` sets the watermark to `snapshot.invocations.back().id` (`1669`), therefore `N`, despite including `N+1`.
- **Consequence:** `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierENativeSnapshotAdmission.kt:40–43` rejects the valid producer data as beyond the watermark, or as a regression if an earlier publication exposed `N+1`. `TierENativeLiveSnapshotConsumer.kt:29–44` permanently latches failure. Scheduling alone can disable live admission and invalidate dictionary/recovery evidence. Terminal-only decoding is not shown to reject this watermark; do not broaden the observed consumer impact beyond the opt-in live path.
- **Smallest complete correction:** compute the cumulative snapshot high-water mark from the maximum actually included invocation ID under the existing snapshot discipline, rather than append order; retain the reader's rejection checks. Reconcile the protocol documentation at the same time: the normative design currently says watermark means `StackTraceId`, explicitly not `InvocationId` (`docs/internals/designs/profiler/tier-e-design.md:2386–2388`), while native producer and native-live reader implement an invocation bound. If a contiguous-prefix guarantee is intended, specify and enforce that stronger contract rather than silently equating a maximum with a contiguous prefix.
- **Regression:** use barriers to allocate two IDs and complete them in reverse order; publish before and after the lower-ID completion. Feed the actual encoded native snapshots into the Kotlin admission/consumer, assert monotonic covering watermarks, both invocations resolved, and no failure latch. Current `TierENativeSnapshotAdmissionTest.kt:29–52` checks malformed input but not concurrent native producer ordering.
- **Mapping/dependencies:** no matching issue found. Related resolved `docs/internals/backlog/resolved/issue-20260912-232409-587-integrate-bounded-dictionary-streaming-watermarkin.md` owns the separate Kotlin stack-batch encoder, not this native MZSE producer. Qualify live-admission measurements only after this producer fix; no causal link to the historical 71/72 failure is established.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Resolution**

The native writer computes its watermark from the maximum invocation ID it actually emitted, independent of append order. The deterministic native regression failed on the second snapshot before the correction and now passes. The JDK 25 session-bound writer/reader fixtures passed, and the Kotlin reader and admission accepted both actual native snapshots in order. The separate dictionary batch watermark retains its StackTraceId contract.

---
<!-- id: issue-20261103-235249-167-publish-native-invocation-watermarks-independently  file: issue-20261103-235249-167-publish-native-invocation-watermarks-independently.md -->
