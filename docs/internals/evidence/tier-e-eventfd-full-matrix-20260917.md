# Rootful Tier E eventfd full matrix - 2026-09-17

## Status

`PASS` for the controlled workload. The retained JSONL terminal record is
`success=true`; `policy_eligibility=false` for every variant.

## Method

- Rootful isolated Docker, `--privileged`, CPU-pinned workload CPU `0` and
  collector CPU `2`.
- Shared exact `openat` oracle workload.
- Three warm-up batches, ten measured 100-operation batches, five rotated JVM
  forks per variant.
- Baseline, dictionary/full-stream, dictionary/unique-edge,
  inline/full-stream, inline/unique-edge, USER_NOTIF without stacks, and
  USER_NOTIF with stacks.

The raw result stream is
`tier-e-eventfd-full-matrix-20260917.jsonl`; the matching environment and
command metadata are in `tier-e-eventfd-full-matrix-20260917.manifest.json`.

## Correctness and workload latency

| Variant | Median ns/op | Workload verdict | Whole-session evidence |
| --- | ---: | --- | --- |
| Baseline | 3,635 | `UNVERIFIED` | `NOT_APPLICABLE` |
| Dictionary full stream | 18,813 | `PASS` | `COMPLETE` in 5/5 forks |
| Dictionary unique edge | 18,889 | `PASS` | `COMPLETE` in 5/5 forks |
| Inline full stream | 18,466 | `PASS` | `COMPLETE` in 5/5 forks |
| Inline unique edge | 18,908 | `PASS` | `COMPLETE` in 5/5 forks |
| USER_NOTIF, no stack | 605,302 | `PASS` | `COMPLETE` in 2/5 forks; otherwise `INCOMPLETE` |
| USER_NOTIF, stack | 465,657 | `PASS` | `INCOMPLETE` in 5/5 forks |

Every profiled fork matched all 1,300 declared operations. Tier E recorded
zero wrong mappings, ring loss, or dropped observations. USER_NOTIF recorded
bounded-observation drops: `0..5,474` without stack capture and
`3,543..6,081` with stack capture. Those losses are retained as incomplete
whole-session evidence; they do not erase the independently matched workload
verdict.

## Cost attribution

The Tier E warmed path is dominated by exact stack walking. Across 7,815
exactly admitted invocations per fork, median per-capture stack-walk time was
`2.28..2.42 us`, raw-key validation was `0.29..0.31 us`, and logical metadata
resolution was about `0.02 us`. Dictionary serialization was about `0.02 us`
per capture; inline serialization was about `0.99 us` per capture. All four
variants had 7,802 cache hits and 13 misses.

Median whole-process CPU was 930-950 ms for dictionary Tier E and 1,130-1,180
ms for inline Tier E, versus 390 ms for baseline. Median peak RSS was about
126-130 MiB for dictionary and 284-290 MiB for inline. These are session-level
measurements, not per-operation allocations.

USER_NOTIF's locally timed notification phases are sub-microsecond per
controlled operation after the eventfd transition. They do not explain its
wall-time overhead: the median complete profile session was 9.65 s without
stack capture and 8.77 s with stack capture, including 8.72 s and 8.15 s of
profile lifecycle overhead respectively. Median process CPU was 2.30 s and
2.24 s. This identifies the remaining cost as broad notification/lifecycle
work outside the narrow receive-to-ack phase, rather than JVM stack capture.

These are host- and workload-specific measurements. They establish neither a
general application SLA nor policy eligibility.
