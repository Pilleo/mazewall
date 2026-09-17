# Tier E matched workload benchmark: 2026-09-16

## Provenance

- Runner: `scripts/run_tier_e_trustworthy_benchmark.sh`
- Raw samples: `tier-e-matched-benchmark-20260916.jsonl`
- Build log: `tier-e-matched-benchmark-20260916-runner.log`
- Environment: rootful Docker, `--privileged --pid=host`, Release native agent.
- CPU placement: workload CPU `0`, collector CPU `2`. Host topology reports those as distinct physical cores; sibling hyperthreads are not used.
- Workload: 100 iterations of the same write, read, checksum, and delete operation for every mode.
- Protocol: five JVM forks, three unmeasured warm-up batches and ten measured batches per fork, rotating mode order.

The timing is the shared workload body only. JVM startup, Tier E attach/detach, terminal publication, and process shutdown are intentionally outside the measured interval. `USER_NOTIF` uses one warmed JVM per fork and a bounded profiling session per batch because its worker has a 60-second session deadline.

## Results

All 200 measured batches passed the declared correctness checks: expected operation count and checksum in every mode; zero Tier E ring loss and zero wrong attribution; and complete `USER_NOTIF` evidence containing the shared workload frame.

| Mode | Median | P95 | P99 | Median vs baseline |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 8.078 ms | 11.349 ms | 15.565 ms | 1.00x |
| Tier E dictionary/full-stream | 16.856 ms | 36.193 ms | 41.517 ms | 2.09x |
| Tier E dictionary/unique-edge | 17.447 ms | 34.041 ms | 40.801 ms | 2.16x |
| USER_NOTIF oracle | 4487.390 ms | 4623.485 ms | 4851.183 ms | 555.5x |

The two Tier E modes are statistically close for this workload; unique-edge is not assumed to be faster because kernel collection, exact capture, and collector admission still occur for each observed syscall. `USER_NOTIF` remains a correctness oracle, not a practical performance baseline.

## Scope and limits

- This result is a host-specific reference, not a universal performance guarantee.
- It validates shared workload semantics, Tier E loss/accounting, and `USER_NOTIF` stack retention. It does not establish cross-backend logical-stack equality frame by frame.
- The runner rejects a missing result, checksum mismatch, Tier E loss/wrong attribution, incomplete USER_NOTIF coverage, or a missing shared workload frame.
- Re-run through the saved runner whenever the workload, JDK, kernel, CPU topology, or profiler implementation changes.
