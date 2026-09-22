---
title: "Diagnose intermittent Tier E pre-admission observation gaps under sustained load"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEKernelSmoke.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt"
target_symbols:
  - "TierEKernelSmoke"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose intermittent Tier E pre-admission observation gaps under sustained load

**Context:**
Rootful 1,000 operations/s sustained samples intermittently missed contiguous declared operations
before exact admission with zero ring loss and no marker-loss branch. A same-run, counter-only raw
`sys_enter` denominator reproduced the missing descriptor groups and found no `openat2`
substitution. The benchmark container exposed CPUs `0,2`, while every Tier E perf-event program was
attached only to CPU `0`; pinning the target leader did not pin all JVM carrier threads. The engine
now attaches marker, syscall, exec, and benchmark-audit programs on every allowed online CPU.

**Needed:**
1. Retain a clean, multi-fork rootful sustained confirmation of the all-allowed-CPU attachment fix,
   including the benchmark-only raw denominator as diagnostic provenance. Do not promote a capacity
   ceiling from source-dirty smoke evidence.

**Evidence:**
- Split-privilege coverage qualification on 2026-09-22 retained five 75,000-operation forks
  per flavour in [the split coverage report](../evidence/tier-e-split-coverage-20260922/report.md).
  Both dictionary modes recovered all 72 declared paths in every fork with complete terminal
  evidence; inline exceeded its bounded native snapshot size and remained incomplete. This
  run had attachment audits disabled, so it qualifies declared-path recovery but **does not**
  close the pre-admission diagnostic question in this issue.
- Coverage-discovery follow-up: `TierECoverageOracleTest` verifies declared frequencies,
  singleton placement, retry/acknowledgement semantics, wrong attribution, and missing-path
  verdicts. `scripts/run_tier_e_coverage_benchmark.sh` saves a separate coverage comparison;
  its coverage verdict never upgrades incomplete operation accounting. The completed five-seed
  matrix is retained in [the coverage report](../evidence/tier-e-coverage-discovery-20260921/report.md):
  both dictionary modes recovered 72/72 paths in all forks with zero missing expected operations
  and zero ring loss. Both inline modes failed terminal publication at the 64 MiB encoded-size
  bound and remained INCOMPLETE. This issue remains open: the new corpus, disabled audits and
  corrected affinity do not establish the cause of every previous discrepancy.
- The coverage harness now launches the workload JVM pinned from startup and records effective
  affinities. The older leader-only `taskset -pc` operation was insufficient to establish the
  Java workload thread's CPU. Prior performance measurements are not automatically comparable.
- Clean rootful development evidence at `33ee8ffe59e36ac90dd2fe67186969f96151f639` is retained at
  `/tmp/tier-e-admission-audit-development-20260921`. Five 60-second 500/s forks per dictionary
  mode completed with `30600/30600` operation-oracle, raw-entry, and post-marker-admission counts
  in every arm and zero ring loss. This satisfies the requested clean multi-fork 500/s confirmation
  and did not reproduce the prior gap. The issue remains open until the higher-rate capacity and
  host-noise evidence required by the related CPU-global sampling issue is retained.

---
<!-- id: issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa  file: issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md -->

### 2026-09-22 split-privilege matched and lifecycle evidence

Retained artifact: `docs/internals/evidence/tier-e-split-matched-20260922/README.md`, with raw fork/negative-fixture archives and checksums. Exact commands: `bash scripts/run_tier_e_split_matched_benchmark.sh`, `node scripts/test_verify_tier_e_split_operations.mjs`, `./gradlew :profiler:test`, and `ctest --test-dir /tmp/jseccomp-profiler-native-build --output-on-failure`. Five rotated forks per mode completed for baseline, four Tier E flavours, and USER_NOTIF with/without stacks. Full-stream dictionary and inline each recovered 2,600/2,600 declared operations in all five short forks; USER_NOTIF matched 2,600/2,600 in all ten forks. The benchmark-only collector manifest is session-bound and independent of the target's denominator. Stale attach identity, simulated active-session incarnation change, and delayed closed artifact have retained rootful fixtures. The earlier long 72-path dictionary coverage passed; inline long-session coverage remains `INCOMPLETE` at the native 64 MiB budget.

Verdict for this *short* matched workload: `capture_domain_validity=PASS` for selected `sys_enter`, `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, `lifecycle_validity=PASS`, `runtime_overhead=UNVERIFIED`, `policy_eligibility=false`. `calibration_target=unmet`: baseline batches were below 1 ms and the common count cannot be raised enough without exhausting bounded inline retention. The historical 25% performance gate is not passed by these timings. Real host PID reuse and a decision-quality all-seven-mode duration remain unqualified. This entry does not close the intermittent pre-admission issue.

### 2026-09-22 calibrated follow-up

A second seven-mode, five-rotated-fork rootful matrix used the saved `calibration_cpu_heavy` profile with the **same** 200-operation batches and post-syscall application CPU work. Artifact: `docs/internals/evidence/tier-e-split-calibrated-20260922/README.md`. `calibration_target=met`: baseline median 109.7 ms; all modes stayed below 15 seconds per batch. Both full-stream Tier E transports recovered 2,600/2,600 declared operations with complete terminal evidence in every fork; both USER_NOTIF modes independently matched 2,600/2,600 per fork. The measured paired median workload ratios were dictionary/full 1.09, dictionary/unique 1.10, inline/full 1.09, inline/unique 1.14, USER_NOTIF/no-stack 1.53, and USER_NOTIF/stack 1.55. Intervals and raw logs are retained. `runtime_overhead=UNVERIFIED` for the historical 25% release regression gate because no prior release has this exact calibrated workload and collector CPU/RSS, receipt p99, and publication phase time were not captured. Long inline 72-path coverage and real host PID-reuse qualification remain incomplete. This does not resolve the intermittent pre-admission issue.

### 2026-09-22 collector-resource follow-up

A second calibrated five-fork seven-mode matrix retained privileged-collector process CPU and peak RSS after terminal publication: `docs/internals/evidence/tier-e-split-calibrated-metrics-20260922/README.md`. All 35 forks completed, all 20 Tier E terminal reports were complete, and both full-stream transports again recovered exactly 2,600/2,600 declared operations per fork. Dictionary/full-stream collector peak RSS median was 362,952 KiB versus inline/full-stream 546,872 KiB; CPU medians (including setup and terminal work) were 5.41 s and 7.37 s. Wide timing intervals across the two calibrated runs preclude a speed ranking. Receipt p99, publication phase time, a matching prior-release regression baseline, real host PID reuse, and sustained inline 72-path completion remain unqualified. This issue remains open and `policy_eligibility=false`.
