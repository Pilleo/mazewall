# Preserve event payloads accompanying loss observations

## Source-backed defect (2026-10-02)

Pinned Gadget `7097db144ccd8dab15373f27ec98d2c3d22e4588`,
`pkg/operators/ebpf/tracer.go`, pairs `ringbufReader.ReadInto`'s valid `RawSample`
with an independently polled loss count. A successful read can return both a
payload and nonzero loss. The original receive loop reports loss, then executes
`continue`, discarding the accompanying valid sample. Perf loss-only records,
by contrast, contain no payload. Treating these as the same loses one additional
valid event for every positive ring-loss report.

Historical ready burst `benchmark.9c88160d/full-2` contained one forwarded report
of 28 lost samples and 29 missing measured tokens. It delivered a later
`/fixture/benchmark-target.json` open from the same native thread (4181048), at
BOOTTIME 111589144379649 after the last delivered measured open at 111589124347763.
The source defect explains an extra discarded valid sample; the counts are
consistent with it. The exact discarded payload cannot be recovered from the
original JSONL. This does not prove every session drains on stop.

## Local correction and regressions

`gadget-event-loss.patch` routes a successful read through `processEventRead`:
report loss, then process a nonempty payload. Empty/loss-only reads emit no event.
The original datasource packet/accessor/subscriber path remains in use, with no
replacement queue/cache, dependency, native buffer enlargement or BPF change.

Real datasource tests first failed for lost payload delivery, hidden subscriber
errors and a fabricated all-zero event on empty input. They now pass with `-race`.
Ordinary valid payloads and perf-style loss-only reads are covered too. The full
event-operator package is included in `make upstream-test`.

```bash
DOCKER_HOST=unix:///var/run/docker.sock make event-read-kernel-test \
  DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

This privileged test creates an **isolated 4 KiB ring**, runs 1000 genuine BPF
output attempts without a reader, and independently counts native output failures.
After producer quiescence it pairs the first actual payload with observed loss
and delivers all readable payloads through the same datasource path. Result:
**745 native failures + 255 delivered payloads = 1000 attempts**, with no extra
discard or corruption. This isolated complete denominator is not a claim about
production buffers or lookup/reset polling. The normal event ring remains
256 KiB; OTel perf buffers remain 512 KiB per CPU.

## Live controls

All controls retain the pinned interpreter/Serial JVM, ordinary target UID,
strict epoch mode, four workers and existing pending/cache bounds. Actual kernel:
`7.2.8-x64v3-xanmod1`, x86-64. Runs are serial; failed gates retain evidence.

**Identical ready burst** `benchmark.40e1f102`: 1200 measured operations, 600
pre-attachment warmups, 600 post-attachment preparation operations, eight routes,
zero measured pacing, three rotated repeats per baseline/events/full mode.
All collector repeats observed every measured token. Full useful/unavailable
segments were 1158/42, 1056/144 and 907/293, with zero skips/wrong segments.
Native observed loss was zero, so these alone do not exercise the positive-loss
fix or establish a general burst-capacity envelope.

**Controlled discard** `benchmark.3ec5930d`: the final bundle passed the existing
200-operation gate requiring exactly missing token `[42]`. The isolated fault
image was `sha256:91d8e94fdf2d3093e9d7e598063d829b232251182301675f9cce7c764cc986ad`.
This remains distinct from native ring pressure.

**Larger pressure diagnostic**, 10000 measured operations with the same preparation
and eight routes, zero pacing, full mode only:

- `benchmark.8b6ad46e` exposed a second defect: its summary counted 2166 admission
  skips, but their terminals said `unavailable`. The strict counter validator
  rejected it. Original records remain unchanged. A target-summary event was not
  delivered, so this run cannot separate unread tail from native capacity loss.
  No accepted report/aggregate was created.
- `benchmark.a7855691`, after correcting admission attribution, observed 5431
  measured tokens: 4696 correct segments, **735 explicit skips**, zero unavailable
  or wrong measured segments, and **4569 missing tokens**. Forwarded native loss
  was also 4569, with `complete=false`. The later target-summary event was delivered.
  Delivered counters matched all 6034 events/terminals, including preparation/
  unmarked records. The missing-token gate rejected the run; its exact list remains
  in `full-0/run-report.json`. Equality in this control is not general loss proof.

The admission fix changes only terminal attribution to `skipped`; count/byte
limits, reasons and immutable event records are preserved. Both admission tests
reproduced the wrong label before the fix and pass with the correction. Missing
events remain separate from explicit enrichment skips.

Reproduce the pressure diagnostic (its missing-token gate may fail):

```bash
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock bash scripts/run-benchmark.sh \
  --operations 10000 --warmup 600 --post-attach-warmup 600 \
  --repeats 1 --modes full --diversity 8 --pause-ns 0
```

## Provenance and verification

The three-repeat burst used corrected reader bundle
`a799b36a34fbba1ffc2343611ed5047fefa09610531671e6cac7073a22f6f856`.
Adding the kernel test gives final bundle
`69c7ecda47e0f0151d7377801d224952e829fd7c84d741b2e55ddc5a381d79c4`,
used by the kernel/single-drop/pressure controls. Generated profiler layout and
both embedded BPF architectures are byte-identical to the preceding checkpoint
`bd85b25a8f3d7b843c5a6c6aa5da73f699893ff933bbdf786bebc37a918bf21f`.
The root admission-label fix is outside the dependency bundle; future manifests/
reports additionally record actual collector binary SHA-256. Older runs are not
retroactively assigned a binary hash.

Final `make verify` passed local race tests, full event-operator tests, original
symbolizer/datasource tests, profiler epoch/shutdown/quality tests, build and
module graph/checksum checks. The real-ring control, repository `./gradlew build`,
backlog validation and modified Python syntax checks passed. Local patches await
independent review.

Native producer/read-buffer finality and general high-rate capacity remain open.
The corrected dispatch prevents an extra userspace discard; it cannot recover
native output failures. `event_loss`/`definition_loss` remain absent.
