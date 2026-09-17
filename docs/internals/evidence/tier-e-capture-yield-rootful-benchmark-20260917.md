# Tier E capture-yield rootful benchmark - 2026-09-17

## Provenance

- Artifact directory: `build/tier-e-trustworthy-benchmark-20260917-capture-yield/`
- Harness: `scripts/run_tier_e_trustworthy_benchmark.sh`
- Source revision: `47f4c6131dfeef7f0d26504c2548e5b1476237ee`
- Image: `mazewall-tier-e-rootful:perf-20260916`
- Pinned physical CPUs: workload `0`, collector `2`
- Workload: shared-openat oracle; three warm-up batches, ten measured batches, five rotated JVM forks.

## Correctness gate

All Tier E variants reported `workload_correctness=PASS` and five of five
whole-session-complete forks. The run is benchmark evidence only and remains
`policy_eligibility=false`.

USER_NOTIF workloads reported workload correctness `PASS`, but only one of five
forks was whole-session complete. Their timings are retained as incomplete
whole-session evidence and are not used for a Tier E performance claim.

## Median workload time, 100 operations

| Variant | Median | Whole-session complete |
| --- | ---: | ---: |
| Baseline | 0.383 ms | n/a |
| Dictionary full-stream | 2.131 ms | 5/5 |
| Dictionary unique-edge | 2.099 ms | 5/5 |
| Inline full-stream | 2.048 ms | 5/5 |
| Inline unique-edge | 2.145 ms | 5/5 |
| USER_NOTIF, no stack | 59.432 ms | 1/5 |
| USER_NOTIF, stack | 79.963 ms | 1/5 |

## Phase conclusion

For complete dictionary Tier E forks, warmed cache counts were 7,802 hits and
13 misses. Typical per-fork phase totals were:

- `GetStackTrace`: 17--35 ms, with one 89 ms outlier.
- Raw-key validation: 2.3--3.9 ms, with one 232 ms outlier.
- Logical metadata resolution and dictionary serialization: normally below 0.3 ms,
  with isolated class-churn/outlier spikes.

Exact stack walking is therefore the next dominant steady-state Tier E cost.
The narrowed profile remains an opt-in, JDK-qualified benchmark experiment;
no production capture behavior, MZSD/MZSE format, BPF state, or public API was
changed by this result.
