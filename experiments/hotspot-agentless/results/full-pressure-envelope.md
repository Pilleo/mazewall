# Strict full pressure and controlled-rate observations

Measured after `23c2a8bd` using the final strict-full collector, without changing
limits or filtering missing/skipped outcomes. This extends Task 5's operating
envelope evidence; it does not establish production capacity or complete loss.

## Reproduction

```bash
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock bash scripts/run-benchmark.sh \
  --operations 3000 --warmup 600 --post-attach-warmup 600 --repeats 3 \
  --modes baseline,full --diversity 8 --pause-ns 100000 --drain-full
```

Use `--diversity 1` for the repeated-route comparison. The rejected burst command
uses `--operations 10000 --modes full --diversity 8 --pause-ns 0`, with the other
flags identical. Although it requests three repeats, the strict missing-token
gate stops at the first failed repeat; no distribution is claimed for that case.
`--drain-full` admits baseline/full modes only; the events-only drain has its own
separate controls in [event-only finalization](event-only-finalization.md).

Environment and inputs match [strict full finalization](full-definition-finalization.md):
x86-64 kernel `7.2.8-x64v3-xanmod1`, ordinary-user pinned Temurin interpreter/Serial
target, privileged external collector, eight or one controlled application route,
600 pre-attach warmups and 600 observed post-attach preparation operations.

- Bundle: `d4928c88f551b0da76b95a9e68bf4729fe9f5d39a5bcf3a79e6e93ef107cfab1`.
- Collector: `fb4c047a400294f8411235e2ac3c30fd1d49a845746eb8f0ebec54fb60d36707`.
- Gadget: `sha256:e3fc03114dbfdbc6ae31df45e9afe289420b0e45bcd17feae7343cb47fcb0f23`.

## Controlled-rate, eight routes

Artifacts: `build/benchmark.6372f625`. Every full repeat delivered and correctly
attributed all 3000 measured operations: zero wrong, missing, unavailable or
skipped measured outcomes. Preparation and unmarked target activity remain in
session totals; they are not silently discarded from health accounting.

| Repeat | Baseline ops/s | Full ops/s | Baseline p99 ns | Full p99 ns | Maximum pending | Pending payload bytes |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 2551.20 | 2288.80 | 142392 | 257132 | 240 | 157220 |
| 1 | 5179.31 | 2727.10 | 70642 | 240370 | 576 | 377704 |
| 2 | 4392.48 | 3743.87 | 92413 | 268050 | 827 | 543379 |

The pacing argument is a requested fixture pause, not an achieved event rate.
Measured rates vary substantially across repeats, including baseline; do not
infer a precise overhead ratio or a guaranteed 3744-operations/s threshold.

Full collector process observations, attach-to-target-done windows:

| Repeat | CPU seconds | Peak sampled RSS KiB | Window ns | Post-target stop ns |
|---|---:|---:|---:|---:|
| 0 | 5.80 | 128996 | 7301284540 | 1620981087 |
| 1 | 5.41 | 131472 | 6839255886 | 1368605201 |
| 2 | 4.23 | 132156 | 5074660378 | 1254339905 |

These CPU/RSS windows include readiness and preparation. RSS is process sampled
memory, not all kernel/OTel memory or a long-running memory-growth proof.

## Same requested pacing, one repeated route

Artifacts: `build/benchmark.3421369f`. All three repeats delivered 3000/3000
measured events, with zero wrong/missing/unavailable measured outcomes. The second
repeat reached the existing 1024 pending cap and recorded **31 explicit skips**.
The runner accepts honest enrichment skips; this run does not pass an assertion
that every delivered event has useful Java attribution.

| Repeat | Baseline ops/s | Full ops/s | Baseline p99 ns | Full p99 ns | Correct segments | Skips | Maximum pending |
|---|---:|---:|---:|---:|---:|---:|---:|
| 0 | 3921.17 | 3696.16 | 90877 | 181972 | 3000 | 0 | 1004 |
| 1 | 4586.92 | 4161.58 | 152328 | 157632 | 2969 | 31 | 1024 |
| 2 | 4878.31 | 3151.51 | 75447 | 211474 | 3000 | 0 | 247 |

Full pending payload maxima were 658604, 671946 and 161755 bytes. Collector CPU
seconds were 3.73/3.40/4.99; sampled peak RSS 134868/134524/133960 KiB; windows
4495779137/4079262209/5849645792 ns. Post-target stop observations were
1259741521/1212417698/1186721088 ns. Reduced stack diversity alone does not imply
that admission pressure disappears; achieved rates and scheduling differ too.

## Rejected 10000-operation burst

Artifacts: `build/benchmark.b9133df5/full-0`. The target completed all 10000 opens
at measured 20498.01 operations/s. The missing-token gate correctly rejected it:

- 5477 measured events delivered, 4523 missing.
- 2984 correct application segments, 2493 explicit enrichment skips.
- Zero wrong or unavailable measured segments.
- Quiescent native event-ring reserve failures observed: 4523, matching the
  measured missing count in this isolated run; whole-session loss remains partial.
- Maximum pending 1024; pending payload maximum 672152 bytes.
- Collector 5.89 CPU seconds, sampled peak RSS 132064 KiB, resource window
  6375103644 ns; post-target stop observation 2116663554 ns.

Both event and definition transport drains completed, with delivered terminal
accounting complete. Draining cannot restore records that failed native ring
reservation or convert admission skips to Java evidence. No later repeats ran
after the rejection. The measured post-target process stop interval includes
teardown outside the native shared drain phase; it is not an assertion that the
entire CLI exits within the two-second drain budget.

## Longer finite workload and time-binned RSS

Artifacts: `build/benchmark.ec92f9cc`, measured after `76a49cbd` with the same
binary/bundle. The command above uses `--operations 12000 --pause-ns 1000000`,
retaining eight routes, 600/600 preparation and three rotated baseline/full
repeats. Every full repeat correctly attributed **12000/12000 measured operations**,
with zero missing/wrong/unavailable/skipped measured outcomes. Both component
drains and delivered terminal accounting completed; native reserve and consumed
definition loss/read-error observations were zero partial snapshots.

| Repeat | Baseline ops/s | Full ops/s | Baseline p99 ns | Full p99 ns | Maximum pending | Pending bytes |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 861.40 | 809.07 | 94734 | 290301 | 287 | 187851 |
| 1 | 786.56 | 813.59 | 100415 | 218552 | 298 | 195054 |
| 2 | 847.25 | 807.76 | 105411 | 247395 | 345 | 226049 |

Measured target windows were 14831765794/14749521926/14855917676 ns; collector
attach-to-target-done windows were 18511515791/19477340448/18663555911 ns. CPU
observations were 5.80/6.75/6.27 seconds, peak sampled RSS 133256/133528/132548 KiB,
and post-target process stop 2525273833/1718934035/1313390380 ns. As above, the
process stop interval includes work outside the shared native drain phase.

For a reproducible trend observation, split each collector resource window into
four equal monotonic-time bins and take the median sampled process RSS in each
bin, using samples with `bin_start <= timestamp < bin_end`:

| Repeat | First quarter KiB | Second quarter KiB | Third quarter KiB | Fourth quarter KiB | Last sampled RSS KiB |
|---|---:|---:|---:|---:|---:|
| 0 | 91096 | 127092 | 122840 | 122888 | 122904 |
| 1 | 86944 | 124860 | 119440 | 119488 | 119512 |
| 2 | 92840 | 124128 | 122512 | 122528 | 122552 |

This distinguishes startup allocation from later process-memory observations:
after the second-quarter peak, later medians were lower, with small positive
third-to-fourth-quarter changes. It is finite approximately 19-second evidence,
not a leak-free proof, a long-running growth bound, or accounting of kernel memory.
The strict 60-second session cap remains unchanged. Session totals retain all
12603 records, including preparation/unmarked activity; session unavailable counts
were 158/127/116, separate from zero unavailable measured operations.

## Claim boundary and validation

All ten full reports preserve `collection_loss_complete=false`, definition
`complete=false`, and explicit successful component-drain observations. Session
received counts equal resolved + unavailable + skipped, measured outcomes conserve
delivered/missing denominators, wrong attribution is zero, and reported pending
count/payload maxima remain within the unchanged 1024-event/16-MiB admission bounds.
Definition consumed loss/read-error counters are zero partial snapshots in these
runs. Session unavailable counts include cold preparation/unmarked activity.

The safe evidence statement is: this exact eight-route, 3000-operation paced
fixture passed all three measured useful-attribution repeats; the one-route case
occasionally skipped enrichment, and the 10000-operation burst failed event
coverage despite successful finalization. Longer-duration growth, more distinct
stacks, independent review, broader runtime lifetime and complete native loss
remain separate gates before a general Kubescape integration claim.
