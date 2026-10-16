# USER_NOTIF latency diagnostic - 2026-09-17

This rootful Docker diagnostic explains the unexpectedly high USER_NOTIF latency in the earlier
Tier E flavour matrix. It used the shared `OPENAT` oracle with 20 warm-up operations and 20 measured
operations, a pinned workload CPU, `--privileged`, and the same JDK/container setup as the retained
Tier E benchmark artifacts. All runs completed with 40 expected and 40 matched operations, zero
dropped observations, and valid workload evidence.

The pre-fix no-stack run recorded a median controlled-operation duration of 21,509,771 ns. Of that,
20,845,925 ns elapsed before the parent listener received the event. The listener-to-ACK segment was
166,247 ns and ACK-to-return was 353,295 ns. This located the latency ahead of stack capture.

`SeccompSessionHandler` polled a pending off-reactor preparation job every 10 ms, but completion of
that job does not make either the seccomp listener or control socket readable. Reducing the pending
work poll bound to 1 ms produced these matched one-fork diagnostic medians:

| Mode | Operation | Before listener | Listener to ACK | Stack capture | ACK to return |
|---|---:|---:|---:|---:|---:|
| No stack | 3,556,759 ns | 3,130,389 ns | 112,736 ns | 551 ns | 257,768 ns |
| Stack enabled | 4,072,197 ns | 3,589,919 ns | 162,053 ns | 40,529 ns | 271,776 ns |

The temporary 1 ms polling change removed the coarse 10 ms floor, reducing the no-stack diagnostic
by about 6.0x. It was then replaced by the production session-owned `eventfd` wakeup: preparation
completion increments the descriptor, the reactor drains it, and only the reactor claims the
notification reply. The remaining USER_NOTIF time includes preparation and cross-process delivery
before listener receipt. The listener-side stack capture is about 40 microseconds in this fixture,
so it does not explain the original 21 ms result.

The `eventfd` implementation is covered by focused platform and profiler daemon tests. A rootful
five-fork post-change matrix is retained separately when it completes; until then these figures
remain pre-`eventfd` diagnostic evidence.

These are one-fork diagnosis measurements and do not replace the five-fork full flavour matrix.

The compact rootful seven-mode integration run also completed successfully after the phase fields
were added. Its USER_NOTIF records each contain `timed_operations=40`, `expected_operations=40`,
and `matched_operations=40`; the raw machine-readable result is retained as
[phase-schema matrix JSONL](tier-e-user-notif-phase-schema-20260917.jsonl) with its
[runner log](tier-e-user-notif-phase-schema-20260917.log). That run validates artifact wiring,
not a new performance baseline.
They are retained to justify the reactor bound and to make the next full run interpretable.

## Provenance

- Source base revision: `334547e7c4f8db9638247ef949608720bf737dbc`, with the latency instrumentation
  and reactor fix present in the working tree.
- Container: `mazewall-tier-e-rootful:perf-20260916`, rootful Docker, `--privileged`,
  `--security-opt seccomp=unconfined`, CPU set `0,2`; workload process pinned to CPU `0`.
- Raw logs:
  [before fix](tier-e-user-notif-latency-before-poll-fix-20260917.log),
  [no stack](tier-e-user-notif-latency-no-stack-20260917.log), and
  [stack enabled](tier-e-user-notif-latency-stack-20260917.log).
- SHA-256: `faf39956810e4c954b266a8161d7dc74885f0b4df1360a7161caeb1ebee7854d`,
  `e19cad9a066affcfdbc04d4dfc406a5e21d569471bfcb4496ba00827b8452f4b`, and
  `9dbaa4c140578dd7befc6f40a53de6b932fd93856786993b997e0f9d8e46d90b` respectively.
