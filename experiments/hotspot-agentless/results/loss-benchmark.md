# Definition shutdown, loss controls and bounded measurements

Follow-up: [`event-read-loss.md`](event-read-loss.md) investigates the retained
burst discrepancy and corrects an extra valid-sample discard in the pinned reader.
It also records new native pressure controls and the admission-skip label fix.
Historical runs and failures below remain unchanged.

Evidence collected 2026-10-01/02 on Linux x86-64
`7.2.8-x64v3-xanmod1`. Runtime, Go and builder images and module revisions remain
the pinned inputs in `dependencies.lock.json`. Java runs as the ordinary operator
UID with `-Xint -XX:+UseSerialGC -Xmx128m`; full mode selects strict epoch identity.
Artifacts below are ignored local runs, not portable published fixtures.

## Owned definition shutdown

Cancellation-aware trace/barrier delivery and owned reader/consumer joins passed
channel-gated race tests, including a held final callback and a stop timeout.
Final fatal/health sampling follows pending enrichment and the bounded stop.
Successful live summaries report reader/consumer stopped and buffers **not drained**.
An incomplete join remains a session error and an explicitly unjoined snapshot.

Strict unloading `capture.9DvjUY` and genuine first-definition-drop recovery
`capture.56k6nD` passed after the shutdown change. These remain independent of the
event-drop control. Native transport/producer-buffer drain and complete loss
accounting are not established by joining workers.

## Measurement contract

The fixture issues finite, unique measured file tokens across one or eight named
routes. Baseline/events/full runs rotate serially across repeats, using identical
counts, JVM flags, pre-attachment warmup, optional preparation and pacing.
Throughput includes requested pacing; latency percentiles cover the individual
open/read/close call chain. CPU/RSS observations cover the collector process from
startup/attachment through target completion, including discovery/preparation.
They are not workload-only CPU overhead. Kernel memory is unmeasured.

Events mode does not start OTel or either stack helper and emits explicit
`unwind_disabled` skips. The analyzer checks actual delivered terminal counters,
operation tokens, duplicate/task identity, latency ordering and denominator;
it rejects definition-profiler evidence in events mode and fabricated final loss.
Missing tokens, enrichment skips and unavailable frames remain distinct.

Optional post-attachment preparation performs 600 additional unique operations at
500,000 ns pacing, outside measured timing. Full mode releases the measured gate
only after its pre-gate snapshot has ordered leaf/route segments with checked even
epochs for every configured route. Preparation completes within 15 seconds and
the observation wait is bounded at five seconds. The pre-gate snapshot is retained
as `readiness-before-measurement.json`; the final report independently recomputes
preparation coverage. Preparation in baseline/events uses the same workload, with
no Java readiness claim. This is empirical preparation, not future loss proof.

## Paced observations

Initial one-route control `benchmark.602afbe3`: 1200 measured operations, 600
pre-attachment warmups, no post-attachment preparation, 500,000 ns pacing, three
repeats per mode. All marked tokens were observed in both collector modes.

| Mode | Median ops/s | Range ops/s | Median p99 ns |
|---|---:|---:|---:|
| Baseline | 1646.331 | 1633.086–1653.166 | 67509 |
| Events | 1611.384 | 1607.748–1624.591 | 115115 |
| Full | 1466.086 | 1452.385–1469.997 | 252230 |

Full useful segments were 1057/1063/1097, with 143/137/103 unavailable and zero
wrong segments. Collector CPU seconds: events 0.99/1.22/1.16, full 2.65/2.72/2.69;
peak sampled RSS KiB: events 76572/80232/78352, full 131636/131844/130944.

Eight-route **ready, paced** control `benchmark.66276104`: same measured count,
pre-attachment warmup and pacing, plus 600 post-attachment preparation operations;
three repeats per mode. All eight routes were epoch-checked before measurement.
Every full run observed 1200/1200 correct measured segments, with zero missing,
unavailable, skipped or wrong measured outcomes.

| Mode | Median ops/s | Range ops/s | Median p99 ns |
|---|---:|---:|---:|
| Baseline | 1464.442 | 1424.234–1552.177 | 151471 |
| Events | 1452.426 | 1412.353–1495.953 | 170048 |
| Full | 1242.866 | 1180.773–1290.591 | 312087 |

Events CPU seconds: 2.68/2.27/2.23; peak RSS KiB: 78024/78472/77088.
Full CPU seconds: 6.10/5.25/5.73; peak RSS KiB: 132680/133988/130380.
Sample windows span 3.24–3.73 seconds for events and 6.13–7.20 seconds for full.
These observations are separate independent runs, not token/time joins to baseline,
and are not a production envelope or an isolated measurement of preparation cost.

## Failed burst controls retained

**Cold** `benchmark.4ecbea9c`: 1200 measured operations, eight routes, zero pacing,
600 warmups before attachment, no post-attachment preparation. Full observed all
1200 tokens but **zero useful segments**, all unavailable. Its useful-coverage gate
failed. Pre-attachment warmup is not profiler readiness.

**Ready** `benchmark.9c88160d`: same burst workload with 600 post-attachment
preparation operations and verified eight-route pre-gate coverage. Full repeats
0/1 observed all 1200 tokens, with 775/1013 correct and 425/187 unavailable.
Repeat 2 observed **1171/1200**, all delivered measured segments correct, but
**29 measured tokens missing**. Forwarded native event loss observed 28 with
`complete=false`; definition-reader lost/empty/error observations were zero.
Reader/consumer joins succeeded and `buffers_drained=false`. The missing-token
gate failed; there is no passing aggregate. Preparation does not solve transport
capacity/finality, and the 28 observed reports do not fully explain 29 missing
tokens. The exact missing list and raw evidence remain in `full-2/run-report.json`.

## Exact controlled event drop

The isolated gadget image
`sha256:6dcb92f0f035e7df0ffa18e3c14f5462eb3536ee55be539b8693fb02362a1653`
drops measured token 42 after allocating its occurrence sequence. Normal gadget
source/image are unchanged. `benchmark.c334bfe5` originally established missing
`[42]`, occurrence-gap lower bound 1 and forwarded observed loss zero.

Final-build `benchmark.f2276af1`: 200 operations, 1,000,000 ns pacing, 200
pre-attachment warmups. Exactly `[42]` missing, 199 observed, 93 correct/106
unavailable measured outcomes, zero skips/wrong segments, complete delivered-event
terminal accounting. Reader/consumer joined; native observations remain partial
and event loss observed zero. A wrong missing token now fails the runner's gate.

A preceding final-build attempt, `benchmark.b1cb9d20`, missed `[42,82]` while the
repository merge build also ran. Its gate rejected the extra loss and retained
the report. The isolated repeat passed; load causality is not proved and this
failure remains part of the evidence, not an accepted single-drop run.

## Verification and remaining gates

`make verify` passed local race tests, original Gadget resolver/datasource tests,
expanded profiler tracer/process-manager epoch and shutdown regressions, libpf/
HotSpot tests, build and module checksum/graph checks. The expanded tracer gate
first exposed a test-packet construction bug: `traceFromRaw` copies under `-race`,
so writes to its returned pointer never initialized the source bytes. The fixture
now encodes wire offsets directly; both race and non-race tests pass. The oversized
test requires the actual `invalid raw frame counts: 0/3073` rejection.

Measured runs used shutdown bundle
`08d9d5ccf3beb115aaa63cd4824c4923ca936b298b30f1f12f771ad9a165b38c`;
the corrected test-only wire fixture yields final bundle
`3ad274fae7b536e9f534e59faafa70a34c97bc446995e5db297d082a88123370`.
The latter final collector passed the exact event-loss control. Runtime/BPF
behavior was not changed by the wire-test fix.

Checkpoint bundle after removing a trailing blank context line from the shutdown
patch envelope:
`bd85b25a8f3d7b843c5a6c6aa5da73f699893ff933bbdf786bebc37a918bf21f`.
All 428 selected profiler and 740 Gadget generated Go/C/header/BPF files are
byte-identical to the tested `3ad274...` bundle. Fresh checkpoint `make verify`
passed again; the patch still applies without fuzz.

Backlog validation, modified shell/Python syntax checks and repository
`./gradlew build` passed. Its first attempt rejected missing index membership for
the newly scaffolded wire-fixture issue; adding the entry repaired that gate.

Full native drain/finality, burst collection, independent review, broader HotSpot
lifetime/runtime coverage, semantic BCI fidelity and Kubescape remain open gates.
