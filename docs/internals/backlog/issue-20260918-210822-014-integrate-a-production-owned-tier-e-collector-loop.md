---
title: "Integrate a production-owned Tier E collector loop with bounded adaptive polling"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
target_symbols:
  - "TierEProfilerSession"
open_questions: false
---

# 🟡 [Severity: HIGH]: Integrate a production-owned Tier E collector loop with bounded adaptive polling

**Context:**
`TierECollectorLoop` now provides a reusable profiler-library collection owner around
`TierEProfilerSession`. It preserves explicit producer closure, bases pacing on drained ring records
rather than delayed dictionary resolution, and exposes bounded-pacing metrics. The kernel smoke
harness consumes this component, including its benchmark-only fixed-delay arm.

Rootful evidence in `docs/internals/evidence/tier-e-adaptive-poll-20260918.json` covers five paired
forks for continuous, fixed, and bounded-adaptive pacing in both dictionary emission modes. All
profiled forks retained complete exact evidence with zero loss. Adaptive pacing reduced idle polls
substantially but did not show a stable cross-mode workload improvement, so it remains opt-in.

`docs/internals/evidence/tier-e-receipt-latency-20260918.json` additionally records five rootful
forks per policy and dictionary mode using the benchmark-only `CLOCK_MONOTONIC` receipt clock.
Every profiled fork was `PASS`/`COMPLETE` with zero loss, wrong, and missing operations. This
closes the calibrated-clock prerequisite, but the burst workload neither declares a sustained event
rate nor establishes long-session behavior, so it does not promote adaptive polling.

The calibrated follow-up in
`docs/internals/evidence/tier-e-calibrated-readiness-and-capture-20260919.json` adds a pollable-FD
readiness policy and a fifteen-fork rootful decision. All 75 fork outputs were correct and complete,
AUTO selected USDT, and loss remained zero. Readiness reduced median collector-process CPU by
11.34% in dictionary/full-stream and 12.53% in dictionary/unique-edge, but both confidence bounds
missed the required 15% gate; unique-edge also missed the receipt-p99 gate. The policy is therefore
retained only as an explicit experiment and continuous polling remains the production default.

**Needed:**
1. Validate bounded adaptive policy selection against end-to-end latency, sustained overload, and
   long-session behavior; the default remains continuous until those measurements establish a
   production latency contract.
   The 2026-09-19 readiness decision is complete and rejected promotion; do not rerun it without a
   new measured hypothesis or changed workload contract.
2. Done: the benchmark-only internal receipt clock uses Linux `CLOCK_MONOTONIC` through the
   validated raw-syscall boundary, matching the `bpf_ktime_get_ns` event domain. Do not use
   `System.nanoTime()` as a substitute: its epoch is not a JVM API contract and a raw subtraction
   would be an unverified latency claim.
3. Partial: the rootful sustained-load runner now declares its target rate and uses a monotonic
   open-loop producer. The retained 2026-09-19 evidence covers a complete fifteen-fork,
   ten-batch near-capacity matrix at 1,000 boundaries/s in both dictionary modes, plus an
   intentional-overload lane that is explicitly `INCOMPLETE` and performance-ineligible even when
   its short smoke has no observed loss. The receipt-clock-enabled 2026-09-20 decision additionally
   retained 58,500 receipt samples per policy/mode, bounded p50/p95/p99 receipt ages, median poll
   counts, and peak retained bytes. It confirmed complete evidence in all arms but did not retain
   collector CPU or paired confidence intervals, so it cannot promote adaptive polling or change
   the continuous default. See
   `docs/internals/evidence/tier-e-sustained-collector-load-contract-rootful-smoke-20260919.json`
   and `docs/internals/evidence/tier-e-sustained-receipt-rootful-decision-20260920.json`.
   A sustained overload run still needs ring-capacity, maximum-ready-per-drain, and retained-state
   high-water measurements before any default-policy promotion.
4. Integrate the reusable loop into the non-harness production Tier E entry point when that entry
   point is introduced, retaining explicit producer closure and all five evidence dimensions.

---
<!-- id: issue-20260918-210822-014-integrate-a-production-owned-tier-e-collector-loop  file: issue-20260918-210822-014-integrate-a-production-owned-tier-e-collector-loop.md -->
