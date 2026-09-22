# Calibrated Tier E split-topology comparison, 2026-09-22

This supersedes the short-batch timing interpretation in `docs/internals/evidence/tier-e-split-matched-20260922/` without replacing its raw artifacts. It is local rootful Docker evidence, not a CI result or policy input.

```bash
TIER_E_SPLIT_MATCHED_FORKS=5 \
  TIER_E_SPLIT_WORKLOAD_PROFILE=calibration_cpu_heavy \
  TIER_E_SPLIT_MATCHED_OUT=build/tier-e-split-matched-calibrated-20260922 \
  bash scripts/run_tier_e_split_matched_benchmark.sh
node scripts/summarize_tier_e_split_matched.mjs \
  build/tier-e-split-matched-calibrated-20260922/fork-results.jsonl \
  build/tier-e-split-matched-calibrated-20260922/paired-summary.json
```

All seven modes used the same 200 operations per batch, three warm-ups, ten measured batches, five rotated JVM forks, pinned target CPU 0 and collector CPU 1. The CPU work occurs **after** each observed syscall: it lengthens the application batch without changing the exact capture requirement, syscall identity, native snapshot size, or operation denominator. The baseline median batch was 109.7 ms; all measured batches stayed below the 15-second cap. `calibration_target=met`.

| Mode | Complete forks | Batch median | Paired fork-median ratio to baseline, bootstrap 95% |
| --- | ---: | ---: | ---: |
| Baseline | 5/5 | 109.66 ms | 1.00 |
| Dictionary/full-stream | 5/5 | 120.54 ms | 1.09 [1.07, 1.16] |
| Dictionary/unique-edge | 5/5 | 120.89 ms | 1.10 [1.09, 1.15] |
| Inline/full-stream | 5/5 | 119.88 ms | 1.09 [1.06, 1.24] |
| Inline/unique-edge | 5/5 | 125.52 ms | 1.14 [1.08, 1.84] |
| USER_NOTIF/no-stack | 5/5 | 178.31 ms | 1.53 [1.52, 3.46] |
| USER_NOTIF/stack | 5/5 | 171.38 ms | 1.55 [1.48, 2.39] |

These are application-batch times, not per-syscall stall times or universal application slowdown factors. The wide five-fork intervals do not establish that inline beats dictionary, or that USER_NOTIF without stacks is slower than with stacks. Keep dictionary/full-stream as the default. A 25% regression gate against a prior *matching release baseline* was not evaluated; `runtime_overhead=UNVERIFIED` for that gate despite the calibrated measurements.

Correctness: dictionary/full-stream and inline/full-stream recovered exactly 2,600 of 2,600 target-declared directory-FD operation IDs in **each** fork, with zero duplicates, background IDs, unresolved observations, or ring loss in the operation verdict. Their terminal evidence was `COMPLETE`. Both USER_NOTIF modes independently matched 2,600/2,600 operations per fork with zero duplicates or wrong/unclassified operations. Unique-edge is representative-only by design; its terminal evidence was `COMPLETE` but it is not a complete per-operation history. The separate long 72-path dictionary coverage artifact passed 72/72 in five forks; inline long-session coverage remains `INCOMPLETE` at the 64 MiB native retention bound.

The raw five-fork archive, checksum, image ID, kernel, JAR/native hashes, run manifest, per-fork JSONL, summary, and paired intervals are adjacent to this README. The runner records target CPU time, target peak RSS, per-batch workload duration, total target elapsed time, and terminal integrity. Collector CPU/RSS, receipt p99, and publication phase time were **not** captured by this runner; do not infer or backfill them from target metrics.

Machine-readable assessment for this finite workload: `capture_domain_validity=PASS` for selected observed `sys_enter`, `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, `lifecycle_validity=PASS`, `runtime_overhead=UNVERIFIED` for the release regression gate, `policy_eligibility=false`. Complete observed operation reconciliation does not imply complete syscall-attempt coverage or future-workload coverage.
