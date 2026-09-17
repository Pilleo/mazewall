# Rootful Tier E, baseline and USER_NOTIF benchmark, 2026-09-17

## Environment and verdict

- Rootful Docker image: `mazewall-tier-e-rootful:perf-20260916`.
- Native build: Release; JDK: OpenJDK 25.0.4.
- Workload: five rotated JVM forks, three warm-up batches, ten measured batches of 100 operations.
- Result: `workload_correctness=PASS`, `policy_eligibility=false`, 350 samples, and a successful
  terminal record.
- The controlled workload was valid in every mode. Whole-session Tier E evidence remains incomplete
  because the artifact retains background `NO_ACTIVE_INVOCATION` activity separately.

## Median workload duration

| Configuration | Median |
| --- | ---: |
| Baseline | 8.125 ms |
| Dictionary/full-stream | 12.537 ms |
| Dictionary/unique-edge | 13.032 ms |
| Inline/full-stream | 12.953 ms |
| Inline/unique-edge | 13.114 ms |
| USER_NOTIF, no stack | 4,431.140 ms |
| USER_NOTIF, stack | 4,424.918 ms |

This workload is deliberately native-call-heavy. It measures the benchmark contract and relative
mechanism cost, not average application slowdown. The two USER_NOTIF modes are intentionally much
slower because each observed syscall crosses the blocking notification and acknowledgement path.

## Retained artifacts

- `tier-e-full-benchmark-20260917.jsonl`: metadata, raw child output, fork records, samples,
  summary and terminal record.
- `tier-e-full-benchmark-20260917-runner.log`: rootful measurement output.
