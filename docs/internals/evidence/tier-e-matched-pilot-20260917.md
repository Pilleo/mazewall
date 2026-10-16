# Tier E matched workload pilot: 2026-09-17

## Status

This is a retained rootful correctness and timing-shape pilot, not a performance regression
baseline. It has five rotated JVM forks, one warm-up batch, and three measured batches per fork
(15 samples per variant). The short workload and three-batch sample size are insufficient for the
saved runner's gate-quality `5 x 10 x 100` matrix. The source revision and dirty-state fields are
`unknown` because this pilot was started directly while repairing the runner.

Raw JSONL: [tier-e-matched-pilot-20260917.jsonl](tier-e-matched-pilot-20260917.jsonl)

## Provenance

- Environment: local rootful Docker, `--privileged --pid=host`, Release native agent.
- Repository mount: read-only during the privileged measurement stage.
- Scratch and output: writable `/tmp` and the retained artifact directory.
- Workload: shared OPENAT oracle; 25 iterations per batch, one warm-up, three measured batches,
  five rotated forks.
- CPU placement: workload CPU `0` (core `0`), collector CPU `2` (core `1`), distinct physical
  cores on socket `0`.
- USER_NOTIF diagnostic limits: 480-second worker deadline and 540-second child deadline.
- Terminal record: `success=true`; `policy_eligibility=false`.

## Correctness

All six profiled variants reported `workload_correctness=PASS`. Tier E dictionary and inline modes
reported `whole_session_evidence=COMPLETE`; both USER_NOTIF modes are `NOT_APPLICABLE` for the
Tier E whole-session dimension. Every profiled mode reported zero ring loss and zero dropped
events.

| Mode | Expected | Matched | Suppressed | Verdict |
| --- | ---: | ---: | ---: | --- |
| Dictionary/full-stream | 100 | 100 | 0 | PASS, COMPLETE |
| Dictionary/unique-edge | 100 | 2 | 98 | PASS, COMPLETE |
| Inline/full-stream | 100 | 100 | 0 | PASS, COMPLETE |
| Inline/unique-edge | 100 | 2 | 98 | PASS, COMPLETE |
| USER_NOTIF/no-stack | 100 | 100 | 0 | PASS |
| USER_NOTIF/stack | 100 | 100 | 0 | PASS |

The two unique-edge representatives are valid: this workload has two distinct canonical
stack/syscall edges. The remaining 98 declared operations are explicitly accounted as collector
suppression, not lost observations.

## Workload timing

These medians are the 15 measured workload batches per variant only. They exclude JVM startup,
attach, detach, and terminal publication. Ratios are descriptive pilot values, not
confidence-backed claims; P95/P99 values include large host-scheduling outliers at this small
sample size and are retained in the raw JSONL.

| Mode | Median | Ratio to baseline |
| --- | ---: | ---: |
| Baseline | 0.204 ms | 1.00x |
| Dictionary/full-stream | 0.596 ms | 2.91x |
| Dictionary/unique-edge | 0.636 ms | 3.11x |
| Inline/full-stream | 0.592 ms | 2.90x |
| Inline/unique-edge | 0.724 ms | 3.54x |
| USER_NOTIF/no-stack | 583.801 ms | 2,857x |
| USER_NOTIF/stack | 588.956 ms | 2,882x |

Dictionary and inline full-stream overlap at this pilot scale; the main Tier E cost remains exact
native-boundary capture and collection rather than dictionary transport. Inline still serializes
more data: median per-fork serialization was 0.599 ms for full-stream and 0.629 ms for
unique-edge, versus 0.020 ms and 0.022 ms for dictionary. Median stack-capture totals were close
across transports (1.39-1.55 ms per fork). USER_NOTIF remains a correctness oracle, not a
practical low-overhead profiler.

The next performance gate must run the saved `5 x 10 x 100` matrix with provenance populated,
environment metadata retained, and paired confidence intervals; this pilot establishes the
correctness and topology prerequisites for that run.
