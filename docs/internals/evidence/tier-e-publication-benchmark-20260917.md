# Rootful Tier E terminal-versus-live publication benchmark, 2026-09-17

## Environment and verdict

- Rootful Docker image: `mazewall-tier-e-rootful:perf-20260916`.
- Native build: the Release build retained by the paired-cache suite; JDK: OpenJDK 25.0.4.
- Workload: five rotated JVM forks, three warm-up batches, ten measured batches of 100 operations.
- Result: `workload_correctness=PASS`, `policy_eligibility=false`, 450 samples, and a successful
  terminal record.

## Median workload duration

| Variant | Terminal snapshot | Live snapshot |
| --- | ---: | ---: |
| Dictionary/full-stream | 13.960 ms | 12.809 ms |
| Dictionary/unique-edge | 12.787 ms | 13.795 ms |
| Inline/full-stream | 13.582 ms | 14.510 ms |
| Inline/unique-edge | 12.685 ms | 13.048 ms |

The runner rotates variants but this first publication suite does not yet pair terminal and live
fork medians statistically. These numbers validate the two publication paths and their retained
phase metrics; they are not sufficient to select a publication mode by latency alone.

## Retained artifacts

- `tier-e-publication-benchmark-20260917.jsonl`: metadata, raw child output, fork records,
  samples, summary and terminal record.
- `tier-e-publication-benchmark-20260917-runner.log`: rootful measurement output.
