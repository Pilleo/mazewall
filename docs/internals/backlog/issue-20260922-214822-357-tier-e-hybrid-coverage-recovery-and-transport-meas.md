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

**Rootful experimental result (2026-09-23):**
- Marker v2 register mapping, full-width 32-byte task initializer, stack/generation
  overlap, in-flight completion key, real retained output, and split-topology
  configuration have been corrected. The map uses a 24-byte session/stack/syscall/flags
  key and a 96-byte first-observation value with attachment generation.
- Dedicated scanner queues bounded immutable pages independently of ring polling;
  collector-owned ring/map coverage membership and acknowledgement preserve
  full-stream event history and deduplicate coverage. Final scan requires both
  native closure and kernel quiescence; map pressure remains explicit.
- Retained evidence: `docs/internals/evidence/tier-e-hybrid-20260923/README.md`.
  Six configurations each recovered 72/72 paths in five development and fifteen
  decision forks (120/120 fault-free runs). Hybrid-only long 75,000-operation
  coverage passed 72/72 paths in 40/40 additional development/decision forks.
  Thirty controlled ring-drop forks
  recovered every singleton from the map while retaining incomplete ring
  transport and event history. The maximum per-fork dependency-relative p99
  discovery upper bound was 2.3 ms versus a 500 ms target. A one-entry map
  pressure run reported 223 insertion failures and still recovered 72/72 via
  the ring; its recovery verdict remained incomplete.
- Final calibrated paired upper 95% workload ratios were 1.080 (full) and
  1.021 (unique); combined CPU ratios were 1.285 (full, **FAIL**) and 1.239
  (unique); receipt-p99 fractions of the allowed increase were 2.47 (full,
  **FAIL**) and 1.34 (unique, **FAIL**). Default promotion is rejected; mode
  remains opt-in experimental. Live native publication stayed below 0.5% of
  measured collector CPU and did not exhaust native retention; incremental
  dictionary transport remains deferred.

**Remaining scope before closing this backlog item:**
- Long matched v1/v2 ring-only controls were not rerun alongside the 40 long
  hybrid forks; sustained hybrid correctness does not establish long-workload
  comparative overhead.
- Real host PID reuse and class-churn/stack-diverse publication runs with
  the new hybrid consumer remain unqualified. The split collector still reads
  terminal definitions; native 100 ms live publication measurement does not
  constitute live dictionary admission or an incremental receiver protocol.
- A mismatched agent emitting v1 into a collector configured for v2 is rejected
  at terminal reconciliation (rootful negative control). Prove the selected
  agent emission version before opening workload admission; a versioned ELF
  symbol or note is insufficient as an agent-version attestation.
- Source-state fingerprinting of all pre-fork inputs and a separate profiler-only
  CPU denominator remain follow-up measurement hardening. Preserve the
  operator's rejection decision and `policy_eligibility=false` until addressed.
- `./gradlew build` is blocked by the existing Detekt warning-inventory ratchet:
  it reports 320 newly normalized findings, including hundreds in untouched
  profiler sources and two platform findings. `./gradlew build -x
  detektWarningRatchet` passes. Do not silently expand the reviewed warning
  inventory; a scoped quality decision is required before merging.

---
<!-- id: issue-20260922-214822-357-tier-e-hybrid-coverage-recovery-and-transport-meas  file: issue-20260922-214822-357-tier-e-hybrid-coverage-recovery-and-transport-meas.md -->
