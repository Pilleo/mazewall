# Tier E Measured Hot-Path Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce Tier E's per-boundary overhead while preserving exact attribution, lifecycle correctness, bounded retention, and the benchmark's seven-mode evidence contract.

**Architecture:** Keep dictionary/full-stream as the production default and optimize only costs demonstrated by paired measurements. Improve the benchmark contrasts first, then evaluate ring-drain batching, marker-path cost, and cache-guided exact stack capture as independent experiments. Retain an experiment only when normalized evidence remains identical and its paired confidence interval demonstrates an improvement.

**Tech Stack:** Kotlin/JVM 25, C++ JVMTI agent, generated eBPF, BPF ring buffer, Gradle, CMake/CTest, privileged Docker.

**Spec:** `docs/internals/designs/profiler/tier-e-design.md`

## Global Constraints

- Preserve exact attribution, fresh `InvocationId` allocation, fail-closed lifecycle behavior, and collector-authoritative acknowledgement.
- Preserve raw `MZSD v2`, inline `MZSD v3`, `MZSE` compatibility, and existing public entry points.
- Keep dictionary/full-stream as the default; inline remains a diagnostic/reference transport.
- Do not suppress ring observations in the kernel and do not reinterpret unique-edge mode as a capture optimization.
- Keep the native and collector retained-state budgets bounded at 64 MiB by default.
- Tier E evidence remains ineligible for policy input; every retained benchmark record contains `policy_eligibility=false`.
- Use Release native builds, pinned workload/collector CPUs, three warm-ups, ten measured batches, rotated mode order, and paired forks.
- Require at least 15 paired forks for an optimization decision. Five-fork runs are smoke or directional evidence only.
- Accept an optimization only when workload correctness passes, whole-session evidence is complete, normalized decoded evidence is equivalent, and the paired 95% confidence interval excludes zero in the beneficial direction.
- Remove rejected or inconclusive experimental production code before committing; retain only the benchmark evidence and rejection rationale.
- Keep USDT, calling-context instrumentation, and incremental dictionary publication outside this plan.

---

### [90%] Task 1: Add direct paired comparator statistics

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmark.kt`
- Modify: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmarkJsonTest.kt`
- Modify: `scripts/run_tier_e_trustworthy_benchmark.sh`

**Interfaces:**
- Produces: `paired_direct_effects` in the summary JSON.
- Produces contrasts: `inline_minus_dictionary_full_stream`, `inline_minus_dictionary_unique_edge`, `unique_minus_full_dictionary`, `unique_minus_full_inline`, and `user_notif_stack_minus_no_stack`.
- Each contrast contains `paired_median_delta_ns`, `paired_bootstrap_95_ci_low_ns`, `paired_bootstrap_95_ci_high_ns`, `pairs`, and `excluded_pairs`.

- [x] Add a failing test using constant per-fork deltas and assert the direction of every named contrast.
- [x] Run `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.stress.TierEMatchedBenchmarkJsonTest`; expect the direct-effects assertion to fail.
- [x] Extract a reusable `pairedEffect(leftByFork, rightByFork, eligibility)` helper. Pair only identical fork numbers; exclude failed workload or incomplete evidence arms.
- [x] Serialize direct contrasts alongside the existing profiler-versus-baseline effects. Define every delta as `right - left` and encode that direction in the field name.
- [x] Add `MAZEWALL_TIER_E_DECISION_FORKS`, defaulting to `15`, for acceptance runs while retaining the current five-fork smoke default.
- [x] Run the focused JSON test and one `1x1x1` rootful smoke matrix; verify every direct contrast is present and all records remain policy-ineligible.
- [ ] Commit as `test(benchmark): add direct paired profiler contrasts`.

### [100%] Task 2: Measure and optimize ring-drain ownership transfer (rejected)

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/ringbuf/BpfRingBufferReader.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/collector/TierERingBufferCollector.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/ringbuf/BpfRingBufferReaderTest.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/collector/TierERingBufferCollectorTest.kt`

**Interfaces:**
- Add internal `RingDrainMetrics(recordsCopied, bytesCopied, busyRecords, discardedRecords, scanPasses, batches, maxBatchRecords)`.
- Add internal `drainOwned(consumer: (SyscallInvocationEvent) -> Unit): RingDrainMetrics` while preserving `drainEvents()` as a compatibility wrapper.

- [x] Prototyped copied-record ownership transfer with lazy decode, then ran a matched 15-fork rootful comparison against the decoded-event-list path.
- [x] Confirmed all 120 profiled fork/mode records passed workload correctness and complete session evidence; all records remained policy-ineligible.
- [x] Measured faster ring drain for dictionary full-stream and unique-edge, but no workload-time confidence interval excluded zero in the beneficial direction.
- [x] Restored the decoded-event-list production path and removed the benchmark-only `ownedDrain` switch, lazy event iterator, and ownership counters.
- [x] Ran focused ring-buffer, lifecycle, and benchmark JSON tests after restoration.

**Rejection evidence (2026-09-18):** Both rootful matrices used 15 rotated forks, three warm-up batches, ten measured batches, the same source revision (`4aa176289f880e40a0002feb6204d66c04a6b9b0`), and the controlled openat oracle. The `owned - compatibility` median workload deltas and deterministic bootstrap 95% intervals were dictionary/full `-4,868,623 ns [-16,457,011, 43,866,909]`, dictionary/unique `-8,848,052 ns [-22,407,388, 10,968,909]`, inline/full `4,165,226 ns [-10,502,971, 16,790,524]`, and inline/unique `10,129,513 ns [-4,400,934, 34,361,616]`. Although ring-drain intervals were favorable for both dictionary modes, join time moved in the opposite direction and end-to-end evidence is inconclusive. The implementation is intentionally rejected under the global acceptance rule. The retained summary is [tier-e-owned-drain-rejection-20260918.json](../../evidence/tier-e-owned-drain-rejection-20260918.json).

### [55%] Task 3: Decompose and evaluate marker-transition cost

**Files:**
- Modify: `profiler-native/src/agent.cpp`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt`
- Test: `profiler-native/tests/marker_export_test.cpp`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilderTest.kt`

**Interfaces:**
- Extend benchmark-only native metrics with `marker_admission_clear_ns`, `marker_exact_set_ns`, `marker_exit_clear_ns`, and transition counts.
- Production mode performs no additional clock reads.

- [ ] Write native tests asserting one admission clear, at most one exact set, and one exit clear for a successful non-reentrant boundary.
- [ ] Add cases for capture failure, callback suppression, nesting, detach, and validated re-entry; assert no stale exact marker survives.
- [x] Split existing aggregate marker timing by transition reason while preserving `mazewall_stack_marker` ABI and BPF layouts.
- [x] Run a 15-fork measurement without changing transition behavior and identify the dominant transition: `exact_set` was largest at approximately `2.44 us` per transition, versus `2.39 us` admission clear and `1.84 us` exit clear across the four Tier E modes.
- [ ] Evaluate only a proven redundant transition. A candidate may be removed only if fresh invocation identity, callback suppression, detach, and re-entry tests remain exact.
- [ ] Run paired correctness/performance evidence. Accept only when the 95% CI demonstrates improvement and all lifecycle fixtures pass.
- [ ] If no transition is safely redundant, retain the phase metrics but no behavioral optimization; commit metrics as `perf(tier-e): attribute marker transition cost`.

**Measurement evidence (2026-09-18):** The 15-fork rootful matrix contained 117,225 transitions of each class per transport/mode. None is proven redundant: admission clear prevents stale marker inheritance before JVMTI work, exact set establishes current invocation ownership, and exit clear revokes ownership after the native boundary. The phase metric is retained; no transition behavior changes in this task. See [tier-e-marker-transition-measurement-20260918.json](../../evidence/tier-e-marker-transition-measurement-20260918.json).

### [100%] Task 4: Implement cache-guided exact stack capture (rejected)

**Files:**
- Modify: `profiler-native/src/agent.cpp`
- Modify: `profiler-native/include/symbolization_generation.hpp`
- Modify: `profiler-native/tests/symbolization_generation_test.cpp`
- Add: `profiler-native/tests/cache_guided_stack_capture_test.cpp`
- Modify: `profiler-native/CMakeLists.txt`

**Interfaces:**
- Add internal capture result `ExactCaptureProbe { frames, matchedCachedDepth, requiresFullFallback }`.
- On a validated cache candidate of depth `N`, request `N + 1` frames. A hit is exact only when exactly `N` frames return and every raw frame, generation, and weak guard matches.
- Any additional, missing, changed, unloaded, redefined, or invalid frame triggers a full configured-depth capture in the same boundary.

- [x] Prototyped an opt-in first-frame admission plus cached-depth-and-one probe, complete raw-frame equality, generation validation, weak-guard validation, and same-boundary full-capture fallback.
- [x] Exercised unchanged, deeper, shorter, changed-middle, epoch, and flag mismatches in a native helper fixture; exercised real agent guided hits in a native benchmark fixture.
- [x] Ran rootful seven-mode smoke and matched 15-fork default/enabled matrices. All Tier E modes retained exact workload correctness and complete evidence.
- [x] Rejected the experiment: all workload-time bootstrap 95% intervals crossed zero, while 93,960 fallback probes per Tier E mode added a second JVMTI call. Removed the agent path, option, counters, launcher wiring, and fixture.

**Rejection evidence (2026-09-18):** The default and enabled rootful matrices shared source revision `4aa176289f880e40a0002feb6204d66c04a6b9b0`, dirty count `11`, CPU set `0,2`, controlled oracle workload, three warm-up batches, ten measured batches, and 15 forks. Every profiled Tier E mode matched 19,500 expected operations with zero missing, wrong, duplicate, unclassified, lost, dropped, or unexplained-unknown events. However, guided-minus-default workload intervals were dictionary/full `776,057 ns [-20,318,778, 8,245,583]`, dictionary/unique `4,885,758 ns [-9,526,840, 20,185,778]`, inline/full `4,105,523 ns [-15,383,799, 21,387,674]`, and inline/unique `7,011,508 ns [-27,735,086, 13,294,100]`. None meets the global acceptance rule. See [tier-e-guided-capture-rejection-20260918.json](../../evidence/tier-e-guided-capture-rejection-20260918.json).

### [90%] Task 5: Measure realistic workload-rate sensitivity

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierESharedOpenatOracleWorkload.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmark.kt`
- Modify: `scripts/run_tier_e_trustworthy_benchmark.sh`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierECommonFileWorkloadTest.kt`

**Interfaces:**
- Add workload profiles `syscall_dense`, `mixed_cpu_io`, and `bursty_io` with deterministic operation manifests.
- Report `profiled_operations_per_second`, `added_ns_per_profiled_operation`, and `estimated_single_core_fraction` without claiming whole-application slowdown from a microbenchmark alone.

- [x] Add deterministic `syscall_dense`, `mixed_cpu_io`, and `bursty_io` schedules around the existing exact `openat` operation manifest. The CPU schedule runs after each observed operation, so it changes the workload mix without changing operation IDs, syscall arguments, order, or oracle semantics.
- [x] Thread profile provenance through baseline, Tier E, and USER_NOTIF child JVMs; retain it in metadata, fork, and sample records. A rootful seven-mode `mixed_cpu_io` smoke completed with all 60 expected operations matched per profiled mode, zero loss, zero wrong/unclassified operations, and complete evidence. See [tier-e-workload-profile-smoke-20260918.json](../../evidence/tier-e-workload-profile-smoke-20260918.json).
- [x] Run the seven-mode 15-fork matrix for each profile with fixed CPU pinning and environment provenance. All 270 profiled forks retained exact oracle matches, complete evidence, zero observed loss, and `policy_eligibility=false`; see [tier-e-workload-rate-matrix-20260918.json](../../evidence/tier-e-workload-rate-matrix-20260918.json).
- [x] Retain the observed 1k- and 10k-class rate points. The profiled modes did not sustain 100k boundaries per second on the authoritative host, so the 100k point is explicitly `INCOMPLETE`, not extrapolated.
- [x] Mark unsupported rate conclusions `INCOMPLETE`; no global dictionary-versus-inline winner is accepted because direct intervals overlap zero in mixed and bursty profiles.
- [ ] Commit as `test(benchmark): add Tier E workload-rate profiles`.

### [100%] Task 6: Measure the JDK-qualified narrow capture domain

**Purpose:** Determine whether the existing `jdk25-unix-openat0` admission profile reduces
end-to-end overhead while preserving exactness inside its explicitly narrower observation domain.

- [x] Run a rootful paired matrix with baseline, dictionary/full-stream `full`, and
  dictionary/full-stream `jdk25-unix-openat0`: 15 rotated JVM forks, three warm-up batches,
  ten measured batches, CPU set `0,2`, and the exact openat oracle.
- [x] Require a complete workload oracle and complete Tier E evidence for every profiled fork.
- [x] Retain a bounded conclusion: the profile is an opt-in domain reduction, not a new default
  and not evidence that a full-domain boundary is cheaper.

**Accepted host evidence (2026-09-18):** All 30 Tier E forks completed with exact workload
correctness and complete evidence. The full profile median boundary time was `1,980,379 ns`; the
JDK-qualified `openat0` profile was `1,640,671 ns`. The paired full-minus-narrowed workload
effect was `312,152 ns` per measured batch, with a deterministic bootstrap 95% interval of
`[278,892, 404,393] ns` across 15 pairs. The narrowed profile is therefore retained as an
opt-in, declared-domain configuration. Full remains the default exact-capture profile. See
[tier-e-capture-profile-matrix-20260918.json](../../evidence/tier-e-capture-profile-matrix-20260918.json).

### [100%] Task 7: Attribute full-domain boundary cost before another optimization

**Purpose:** Reduce full-profile cost without silently narrowing its capture domain.

- [x] Retain matched phase measurements for exact stack capture, raw-key validation/generation
  guards, proxy/marker operations, BPF/ring handling, and collector join/retention. Across all
  three workload shapes, ring draining is the largest normalized measured phase; stack capture
  and required marker clearing follow it. See
  [tier-e-full-profile-phase-matrix-20260918.json](../../evidence/tier-e-full-profile-phase-matrix-20260918.json).
- [x] Evaluate fused direct decoding as a reversible ring-drain candidate. The direct path copies
  a decoded immutable event before consumer release and the legacy `ByteArray` path remains a
  paired benchmark control. The 15-fork rootful matrix retained `156,000/156,000` expected Tier E
  operations with zero loss and complete evidence, but dictionary/full-stream had an interval that
  crossed zero. Direct decode is rejected as a default optimization and remains benchmark-only;
  legacy decoding remains the session default. See
  [tier-e-ring-decode-ab-rejection-20260918.json](../../evidence/tier-e-ring-decode-ab-rejection-20260918.json).
- [x] Keep the decoder decision scope focused on baseline and paired Tier E arms. USER_NOTIF does
  not execute the ring decoder and is excluded from this scope, avoiding thirty unrelated child
  JVMs in a fifteen-fork decision run while preserving every direct-versus-legacy pair.
- [x] Use the existing seven-mode, rate-profiled benchmark contract and record paired
  confidence intervals for each phase alongside whole-workload time.
- [x] Choose one dominant full-domain phase for a single reversible optimization; reject it if
  exact oracle, lifecycle, or evidence dimensions regress, or if the paired interval does not
  support the claimed improvement.

**Decision (2026-09-19):** The production-aligned fifteen-fork direct-decoder experiment retained
complete evidence but failed the end-to-end and total-CPU gates. Direct decoding reduces local
drain allocation and time, yet its workload confidence intervals cross zero and its CPU medians do
not improve. The candidate is rejected as the default; the retained decision is
[tier-e-ring-decode-production-decision-20260919.json](../../evidence/tier-e-ring-decode-production-decision-20260919.json).

### [10%] Task 6: Retain decisions and update the Tier E design

**Files:**
- Modify: `docs/internals/designs/profiler/tier-e-design.md`
- Add: `docs/internals/evidence/tier-e-hot-path-optimization-20260918.json`
- Modify: `docs/superpowers/plans/2026-09-18-tier-e-measured-hot-path-optimization.md`

**Interfaces:**
- Evidence records `decision: accepted | rejected | inconclusive`, paired effect and confidence interval, correctness verdict, artifact checksum, source revision, environment, and `policy_eligibility: false`.

- [ ] Record the current accounting matrix as the pre-optimization baseline, including its checksum and rootful environment metadata.
- [ ] For each experiment, retain exact commands, raw artifact path, paired effect, confidence interval, normalized-evidence verdict, lifecycle verdict, memory/tail-latency gates, and final decision.
- [ ] Remove rejected experimental code before its decision commit; explicitly state why it was rejected or inconclusive.
- [ ] Update the design with accepted behavior only. Keep rejected alternatives in historical evidence, not normative architecture.
- [ ] Run `./gradlew :profiler:test`, native Release CTest, `./scripts/adkw check-backlog`, and `./scripts/adkw hook`.
- [ ] Run the final rootful 15-fork seven-mode matrix and verify all retained records declare `policy_eligibility=false`.
- [ ] Commit as `docs(tier-e): retain measured hot-path optimization decisions`.

## Completion Criteria

- Direct dictionary/inline, full/unique, and USER_NOTIF stack/no-stack comparisons use paired forks and 95% confidence intervals.
- Every retained optimization demonstrates a positive paired effect with a confidence interval excluding zero.
- Normalized decoded stacks, statuses, execution relationships, operation counts, and lifecycle verdicts remain equivalent.
- No accepted change introduces stale attribution, kernel suppression, unbounded retention, wire-format drift, or policy eligibility.
- Rejected or inconclusive experiment code is absent from production sources.
- Final evidence distinguishes per-operation workload overhead from attach, publication, and shutdown lifecycle cost.
