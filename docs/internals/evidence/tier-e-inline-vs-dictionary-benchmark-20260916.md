# Tier E dictionary versus inline matched benchmark: 2026-09-16

## Method

- Runner: `scripts/run_tier_e_trustworthy_benchmark.sh`
- Raw samples: `tier-e-inline-vs-dictionary-benchmark-20260916.jsonl`
- Build and runner log: `tier-e-inline-vs-dictionary-benchmark-20260916-runner.log`
- Rootful Docker with the Release native agent, `--privileged --pid=host`.
- Workload CPU `0` and collector CPU `2`, selected as separate physical cores.
- One identical write/read/checksum/delete workload: 100 operations per batch.
- Five JVM forks, rotating mode order; three warm-up batches and ten measured batches per mode and fork.

The benchmark measures the workload body, not JVM startup, attach/detach, or terminal publication. Every Tier E run uses the native-backed `sys_enter` capture path. `USER_NOTIF` remains an oracle and uses bounded per-batch profiling sessions within each warmed JVM.

## Results

All 300 measured batches passed: identical operation counts and checksums; Tier E had zero ring loss and zero wrong attribution in every dictionary and inline mode; `USER_NOTIF` retained the shared workload stack with complete coverage.

| Mode | Median | P95 | P99 | Relative to baseline |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 8.269 ms | 12.925 ms | 16.578 ms | 1.00x |
| Dictionary/full-stream | 18.912 ms | 36.493 ms | 43.411 ms | 2.29x |
| Inline/full-stream | 18.653 ms | 36.243 ms | 41.480 ms | 2.26x |
| Dictionary/unique-edge | 18.617 ms | 33.404 ms | 35.012 ms | 2.25x |
| Inline/unique-edge | 19.955 ms | 31.598 ms | 33.825 ms | 2.41x |
| USER_NOTIF oracle | 4462.532 ms | 4552.079 ms | 4590.207 ms | 539.7x |

## Interpretation

For this repeated-stack file workload, inline and dictionary full-stream are indistinguishable: inline is 1.4% faster at the median, far below a meaningful decision threshold. Inline unique-edge is 7.2% slower at the median than dictionary unique-edge, although its observed tail percentiles are lower. Neither difference establishes a durable winner.

The output shape still differs substantially. Full-stream resolves 6,538 syscall observations per batch, while unique-edge resolves 17 acknowledged edge representatives. Dictionary remains the default because repeated stacks can be transmitted once and referenced thereafter. Inline remains appropriate for self-contained diagnostic evidence, but this timing result does not justify changing the default.

This run does not measure transport bytes, snapshot size, allocation, or peak retained memory. Those are required before making a memory or bandwidth claim about either transport.
