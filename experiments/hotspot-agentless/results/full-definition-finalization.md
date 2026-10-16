# Owned strict full definition finalization

Based on `add37cab`, implemented locally 2026-10-02. `--drain-full` requires
structured full capture with `--hotspot-identity=safepoint-epoch`, the exact pinned
interpreter/Serial runtime and one finalization owner. Events/debug/non-strict
profiles or simultaneous `--drain-events` are rejected. Pins, native layouts,
event/perf/cache bounds, worker limits and BOOTTIME event budgets are preserved.

## Protocol

The existing single-openat event stop retires its producer and flushes/joins its
ring/datasource readout. Before Gadget maps/programs are retired, its optional
strict-profile handoff runs under the same two-second remaining budget:

1. Retire owned profiler-native hooks with checked errors; attached sampling
   entries are outside this profile and refused. Process metadata remains alive.
2. Flush the original definition perf reader. Drain requests bypass poll latency;
   ordinary batch limits/watermark logic and interval metrics remain in use.
3. Consume the explicit perf flush boundary, send the final unbuffered barrier,
   close the sole producer channel, and join the final consumer callback.
4. Finish bounded enrichment while identity/exec guards, caches and metadata are
   still owned. Each event retains its original deadline and terminal outcome.
5. Permit event collection retirement, sample component health, then close the
   joined symbolizer using its existing metadata cleanup path.

Graceful request and owned definition/identity/enrichment contexts are distinct.
Fatal/output/target-exec/permission failures cancel both; target exit is an abort,
not proof that remote metadata can still be safely read. The owned deadline is
bounded by the original 60-second cap. Failed/pending owners cannot be bypassed
by StopDefinitions/Close; incomplete full work retains its exec-barrier lease and
cannot emit a false final summary under an active callback.

The optional definition-drain capability is additive. Default legacy capture
still uses cancellation/join partial snapshots; no rendered interface was removed.
`definition_stop_observation.buffers_drained=true` requires the explicit reader
boundary plus acknowledged consumer join. It does **not** set complete native
loss, complete Java walking/BCI fidelity, or universal metadata-retirement proof.

## Behavioral verification

Host regressions first failed missing perf flush/producer guards, hidden EPERM/
EACCES, final callback join, full profile/lifetime and full report evidence.
Native cause aggregation additionally failed when a later EACCES overwrote EPERM;
the corrected control retains both actual causes. Canceled readers cannot become
flushed; unjoined consumers cannot become drained. Duplicate stop requests share
the owner, with existing failed-owner retirement guards retained.

```bash
DOCKER_HOST=unix:///var/run/docker.sock make definition-finalization-kernel-test \
  DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

The race-enabled native control publishes valid raw definition packets through a
real perf event map, holds the first consumer callback, requests native readout and
then releases it. It verifies unique tokens, explicit perf completion and channel
acknowledgment:

- 8 native definitions: all 8 delivered.
- 5000 native definitions: all 5000 delivered, crossing the ordinary 4096-record
  batch cap with no consumed loss/read-error observation.

This transport fixture uses an isolated larger perf allocation (`samplesPerSecond`
128) so its known tail fits; actual pinned collector summaries retain 524288
data bytes per CPU. The packets exercise native transport/decoder/handoff, not a
synthetic claim of verified Java frames. Java attribution is established by the
independent real fixture gates below.

## Actual pinned strict workload

Hosted kernel: x86-64 `7.2.8-x64v3-xanmod1`. Java runs as the ordinary operator UID
from the pinned Temurin image, with no injected profiler. Stop duration is separate
from target workload timing and CPU/RSS attach-to-target-done observations.

**Paced eight-route repeats** `benchmark.9c455a25`: 1200 measured operations,
600 pre-attachment warmups and 600 observed post-attachment preparation operations,
500000 ns pacing, three rotated baseline/full runs. Every full repeat delivered
and correctly attributed all 1200 measured segments, with no missing/unavailable/
skipped/wrong measured outcomes. Both native component drains completed; health
and native loss observations remain partial. Stop windows were 999182036,
837696025 and 896768339 ns.

| Mode | Median ops/s | Range ops/s | Median p99 ns |
|---|---:|---:|---:|
| Baseline | 1605.447 | 1598.800–1650.077 | 70589 |
| Strict full | 1494.714 | 1486.872–1503.540 | 158255 |

**Zero-pacing burst** `benchmark.12ef41c5`: same counts/routes/preparation, three
full-only repeats. Every run captured all 1200 measured operations: 1024 correct
application segments and **176 explicit pending-limit skips**, zero unavailable/
wrong/missing outcomes. The 1024 pending bound is preserved; successful drain does
not rewrite skips as resolved Java frames. Stop windows: 946478227, 1046061511,
994736903 ns. These are bounded observations, not a production capacity promise.

**Genuine first-definition drop** `capture.BrFmcz`: 400 event terminals, 352
resolved/48 unavailable, one real native reference invalidation and zero recovery
errors. The chronology/correctness gate passed with both component drains. Original
unavailable outcomes remain immutable; only subsequent operations recover.

**Strict unloading** `capture.iN8ZWA`: useful per-generation gate passed, including
1580 checked/correct segments across the 1600 marked operations and all 80 disposable
generations. Session counts include 1761 total records (including unmarked GC/
fixture activity), 1580 resolved/181 unavailable and no skips. Both component drains
completed; the scoped epoch and broader lifetime limitations are unchanged.

**Exact event discard** `benchmark.250abd0c`: 200 measured operations, exactly
missing `[42]`, 199 observed/correct segments, no measured unavailable outcomes.
Both drains completed; deliberate discard remains separate from native reserve
failure, and whole-session loss is not upgraded to final zero.

The recovery/unloading harness uses an explicit finish gate: the target announces
completed finite work but remains alive while the collector receives SIGINT and
finishes owned draining. Only then is the target released. Default fixture exit
behavior is retained when full drain is not requested. This preserves, rather
than suppresses, the distinct target-exit/identity abort boundary.

## Reproduction and provenance

```bash
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock bash scripts/run-benchmark.sh \
  --operations 1200 --warmup 600 --post-attach-warmup 600 --repeats 3 \
  --modes baseline,full --diversity 8 --pause-ns 500000 --drain-full
```

Live accepted workload bundle:
`ee3f662e402e6243896abaa7d0a49f4b0978becfef405d6f6cfbb9d8fe48b8e8`.
Final bundle after multi-batch/cause-retention tests:
`d4928c88f551b0da76b95a9e68bf4729fe9f5d39a5bcf3a79e6e93ef107cfab1`.
Final collector binary SHA-256:
`fb4c047a400294f8411235e2ac3c30fd1d49a845746eb8f0ebec54fb60d36707`.
Recovery uses its distinct fault manifest/modfile; raw artifacts retain provenance.

Final experiment verification and the native multi-batch race gate passed, with
the event native tail/deadline regression and repository `./gradlew build` also
passing. Final-binary live control `benchmark.f721bddb` observes all 200 measured
tokens using the established 600/600 preparation envelope. An initial 100/100
preparation attempt failed the bounded epoch-route readiness gate; it is not
counted as accepted evidence.

The final experiment verification includes
the original symbolizer/cache/datasource/epoch/quality suites and module checksums.
Native submit/probe/decode loss coverage, ancillary metadata cleanup/error coverage,
general concurrent/JIT lifetime, semantic BCI, independent review and a general
high-rate envelope remain open. The current claims are explicit event-ring and
definition-perf drain plus delivered enrichment accounting for the pinned profile.
