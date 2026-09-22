---
title: "Tier E hybrid coverage recovery and transport measurement"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["profiler", "profiler-native", "scripts"]
target_files: ["profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEOptions.kt", "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt", "scripts/run_tier_e_split_matched_benchmark.sh"]
target_symbols: ["TierEOptions", "CoverageRecoveryMode", "CoverageCandidate"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Tier E hybrid coverage recovery and transport measurement

**Context:**
Deliver an opt-in hybrid coverage mode that preserves an exact stack/syscall edge
in a bounded kernel map when its ring event cannot be delivered. Keep the existing
ring stream and collector-owned acknowledgement. Dictionary/full-stream remains
the default. `policy_eligibility=false` throughout.

**Needed:**
1. Stage 1: extend split-topology benchmark to export receipt p50/p95/p99, native
   serialization/publication counters, CPU/RSS, retained-byte peaks; freeze workload;
   bounded deadlines, cleanup, terminal result for missing batches/children/metrics.
2. Stage 2: marker v2 `mazewall_stack_marker_v2(invocationId, stackTraceId, flags)`
   with versioned USDT, per-session version selection, 24→32 byte task storage,
   exact publish after admission, clear lifecycle, nested scope restore.
3. Stage 3: bounded retained-edge map `DISABLED|SHADOW|RECOVER` default DISABLED,
   4096 edges, sessionEpoch+stack+syscall+flags key, immutable representative,
   insert-if-absent before ring reservation, no eviction, continued ring attempts.
4. Stage 4: 100ms sweep start, ≤256 entries/batch with bounded yielding, serialized
   collector owner, historical validation without replaying task state, shared
   acknowledgement (coverage-only for full-stream), ordered shutdown, early
   singleton ring-drop fixture with incomplete event history.
5. Stage 5: compare terminal vs 100ms live snapshots; reopen incremental only on
   ≥10% profiler CPU, retention exhaustion, or dictionary blocking discovery.
6. Stage 6: v1-ring vs v2-ring vs v2-hybrid matrix, 72-path oracle, 5+15 forks,
   72/72 gates, p99 dependency-relative recovery ≤500ms, paired 95% ≤1.25 workload
   and CPU, receipt p99 ≤ max(100us,10%).

**Investigation:**
- Split matched benchmark exports workload_ns/CPU/RSS only; receipt/publication
  phases exist in TierEMatchedBenchmark but not in split topology.
- Split target agent runs without `benchmark-phases`; native counters unavailable.
- Terminal report lacks publication/receipt fields; collector.log is terminal-only.
- TierEOptions has DIAGNOSTIC_UPDATE coverage map but no SHADOW/RECOVER mode.

**Side effects:**
- Disabled mode must retain existing generated program and map schema.
- No ring suppression, no promise of lower producer CPU.
- No default promotion without separate decision.

---
<!-- id: issue-20260922-214822-357-tier-e-hybrid-coverage-recovery-and-transport-meas  file: issue-20260922-214822-357-tier-e-hybrid-coverage-recovery-and-transport-meas.md -->
