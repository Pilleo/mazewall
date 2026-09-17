# Tier E post-USER_NOTIF-poll-fix matrix (2026-09-17)

## Status

`PASS` for workload correctness. The benchmark wrote a terminal record with
`success=true`; Tier E remains `policy_eligibility=false`.

## Method

- Rootful isolated Docker container with CPU pinning: workload CPU `0`, collector CPU `2`.
- Shared exact `openat` oracle workload.
- Three warm-up batches, ten measured batches, five JVM forks, rotated variants.
- 100 operations per batch. The baseline and USER_NOTIF variants do not produce
  Tier E whole-session evidence; their `whole_session_complete_forks` is therefore `0`.

The authoritative raw output and terminal record are
`tier-e-post-user-notif-fix-matrix-20260917.jsonl`; the corresponding runner
log and compact manifest are retained alongside it.

## Workload medians

Each value is the median measured 100-operation batch divided by 100. It is a
workload measurement, not a universal per-syscall latency guarantee.

| Variant | Median ns per operation | Workload correctness |
| --- | ---: | --- |
| Baseline | 3,810 | `UNVERIFIED` (no profiler oracle required) |
| Tier E dictionary full stream | 19,953 | `PASS` |
| Tier E dictionary unique edge | 20,789 | `PASS` |
| Tier E inline full stream | 19,466 | `PASS` |
| Tier E inline unique edge | 21,452 | `PASS` |
| USER_NOTIF, no stack | 3,136,544 | `PASS` |
| USER_NOTIF, stack | 3,414,938 | `PASS` |

## USER_NOTIF phase medians

Phase values are per controlled operation, aggregated from five forks. They
explain the end-to-end USER_NOTIF delay without attributing scheduler wake-up
latency to stack capture.

| Phase | No stack (ns) | Stack (ns) |
| --- | ---: | ---: |
| End-to-end controlled operation | 2,549,219 | 3,384,255 |
| Blocked before listener receives request | 2,346,111 | 2,613,937 |
| Listener receive to ACK | 44,274 | 85,232 |
| Stack capture | 275 | 39,802 |
| Queue time | 12,855 | 12,663 |
| ACK to application return | 97,307 | 83,450 |

The earlier fixed 10 ms pending-work polling delay is gone. The remaining
dominant cost is synchronous seccomp notification/wake-up scheduling before
the listener receives a request, which Tier E does not impose because it
observes asynchronously after selected `sys_enter` processing.

## Interpretation

- Dictionary and inline full-stream are within normal run variance in this
  workload; their transport cost is not the dominant boundary cost here.
- Unique-edge is not a reliable throughput optimization: it still captures
  every exact stack and additionally coordinates representative ownership.
- This matrix validates correctness and comparative workload cost for the
  controlled oracle. It does not establish a general application SLA.
