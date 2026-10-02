# Owned event-only ring finalization

Implemented locally on 2026-10-02, based on `b11d0d4a`. The opt-in
`--drain-events` supports only structured events-only capture of the standalone
single `__x64_sys_openat` kprobe and `events` ring. Both stack helpers are disabled;
no OTel reader, definition consumer or enrichment worker is started. Full/strict/
debug modes, additional writers/attachments/tracers and unsupported counter/transport
layouts are rejected. Kernel for this evidence: x86-64 `7.2.8-x64v3-xanmod1`.

## Owned lifecycle

The optional Gadget stop owner performs these effects once:

1. Close the owned openat producer link and propagate its error.
2. Flush the existing Cilium ring reader.
3. Consume its explicit flush boundary and join receiver/datasource callbacks.
4. Read the retained per-CPU native reserve counters after quiescence, without
   lookup/reset polling or depending on another event to forward the observation.
5. Close the reader and owned collection programs/maps with checked errors.

Pure ordered transitions and immutable snapshots distinguish each achieved phase.
Duplicate stop calls share the owner. A duplicate caller canceling its wait cannot
cancel the first owner. All effects use one two-second context limited by the
remaining owned 60-second session deadline. A native effect or callback that does
not return can cause an explicit pending/partial error, never successful drain.

The native reader rejects unexpected closed/read/malformed/output failures in this
profile rather than retrying them as successful finalization. Tail output uses a
consumption context surviving the stop request. On unestablished receiver join,
the collector returns its error without attempting to lock a held callback or emit
a false final summary. Failed active owners retain reader/map ownership; ordinary
Gadget Close cannot bypass that decision. This is not an in-process cleanup
guarantee for permanently non-returning user callbacks; dedicated CLI failure
termination and successful resource retirement remain different outcomes.

The summary's `event_finalization_observation` reports the exact supported scope,
phase, stop cause, writer quiescence, buffer/callback join, final native counter
read and resource retirement. `loss_complete=false`. The native loss observation
has semantics `quiescent_native_reserve_failures_partial_snapshot`; final
`event_loss`/`definition_loss` remain absent. Reserved-record drain cannot recover
producer failures, deliberate discards or probe misses and does not claim Java
fidelity. No native layout, buffer/cache limit, image/module pin or dependency changed.

## Supported producer retirement source

Cilium `v0.20.0` `link/perf_event.go` closes the owned BPF perf link and its owned
perf event. No pinned-link reopening, external perf-event FD lease or extra writer
is part of this selected profile. Scope validation runs before native start.

Upstream Linux `v7.2.8` source follows the owned event's final release through
`perf_event_release_kernel`, `_free_event` and synchronous `__free_event` destruction:

- [kernel/events/core.c](https://github.com/gregkh/linux/blob/v7.2.8/kernel/events/core.c)
  assigns `perf_kprobe_destroy` and calls the destroy method before RCU object free.
- [kernel/trace/trace_event_perf.c](https://github.com/gregkh/linux/blob/v7.2.8/kernel/trace/trace_event_perf.c)
  `perf_kprobe_destroy` calls `perf_trace_event_unreg`, which disables the event
  and calls `tracepoint_synchronize_unregister` before freeing trace buffers.
- [kernel/trace/trace_kprobe.c](https://github.com/gregkh/linux/blob/v7.2.8/kernel/trace/trace_kprobe.c)
  documents this synchronization and destroys the local trace kprobe.

The real-link controls below exercise the hosted kernel and prevent publications
after successful close. They do not establish these semantics for arbitrary
attachment types or external FD references. Independent source/lifecycle review
remains open before broader finality claims.

## Red/green and native acceptance

Host tests first failed missing callback/ownership behavior, unsupported scope
admission, overflow/layout checks, canceled-tail delivery and fabricated report
claims. A duplicate-wait cancellation regression also reproduced shared-owner
cancellation before its correction. Final selected host tests pass under `-race`.
EPERM/EACCES are checked at writer retirement, flush, join, counter read and retire;
no failed phase invents a successful counter read or whole-session loss proof.

```bash
DOCKER_HOST=unix:///var/run/docker.sock make event-finalization-kernel-test \
  DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

The race-enabled kernel test uses a real filtered openat kprobe, original Gadget
reader/datasource/Stop path, a held first callback and finite native publication:

| Control | Native attempts | Delivered | Native output failures | Result |
|---|---:|---:|---:|---|
| Queued tail | 8 | 8 | 0 | Flush boundary/callback join/retirement complete |
| Isolated ring pressure | 1000 | 255 | 745 | Final counters forwarded without later event |
| Held callback deadline | 8 | Completion deliberately held | Not sampled | Error; maps/readers retained; repeated Close cannot bypass owner |

The successful controls verify unique ordered tokens, delivered plus failed output
equals the native denominator, and additional marker opens cannot publish after
producer retirement. The pressure ring is an isolated 4 KiB test object; the
normal gadget ring remains 256 KiB. The original stop path first failed native
acceptance by leaving the component in `stop_requested`; no characterization-only
pass is substituted for the new drain acceptance.

## Actual pinned workload

All runs use the same pinned ordinary-user interpreter/Serial JVM and the normal
digest-labelled gadget, except the explicit token-discard control. CPU/RSS windows
still end at target completion. `post_target_stop_ns` separately measures Docker
signal request through collector exit, not workload latency or isolated drain CPU.

- `benchmark.9185d732`: three rotated baseline/events repeats, 1200 measured
  operations, 600 pre-attachment warmups, eight routes, zero pacing. Every events
  repeat captured all 1200 operations as explicit `unwind_disabled` skips and
  passed owned component finalization. Native reserve observations were zero,
  partial. Stop windows: 338844414 / 394620425 / 344518266 ns.
- `benchmark.d55e6721`: final guarded-build repeat passed all 1200 marked tokens,
  zero missing/native reserve failures, and all owned component gates. Stop window
  444040718 ns. No Java attribution claim is made in events-only mode.
- `benchmark.d23b3716`: 10000-operation zero-pacing pressure diagnostic captured
  7106 measured operations and missed **2894**. Final native reserve observation
  was also 2894; all delivered operations retained explicit skips. Buffer/callback
  drain and resource retirement succeeded, but the missing-token gate rejected
  the run and retained its report. Equality here is not general loss completeness.
- `benchmark.bdaf96d2`: isolated discard image missed exactly `[42]` out of 200
  measured operations; 199 observed explicit skips, native reserve observation
  zero, and owned drain passed. Deliberate discard stays separate from native
  reserve failure. This is why the observation cannot certify final zero loss.

Reproduce bounded acceptance:

```bash
DOCKER_HOST=unix:///var/run/docker.sock make build benchmark-fixture benchmark-report
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock bash scripts/run-benchmark.sh \
  --operations 1200 --warmup 600 --repeats 3 --modes baseline,events \
  --diversity 8 --pause-ns 0 --drain-events
```

## Provenance and remaining gates

Initial live implementation bundle:
`a3761e0689a15c474d979b48b9ab12f9cdef8205c1c3fc2116b004f18c7ad797`.
Final bundle with the native counter-layout guard:
`d65c90a44683b7fa1df9fe6641100925af117894edf59c735d6c3591f0020a2f`.
The final collector binary SHA-256 recorded by the runner is
`87b2cb1fdb55475d3bad2060c7462e10e63cf9c0fb0c05b09dc588703041e743`.

`make verify` and the final native ownership acceptance passed. The selected graph
retains the original profiler BPF and native layouts; this lifecycle proposal is
implemented as local Go patches/additions. Full definition/perf drain, broader
producer types, probe/definition loss coverage, independent review and a general
high-rate capacity envelope remain open gates.

Repository `./gradlew build`, backlog validation and modified Python syntax checks
passed after focused acceptance. Native profiler layout and both embedded BPF
objects were compared byte-for-byte with the preceding proof checkpoint and are
unchanged. The milestone keeps the implementation/review issues open for broader
finalization and independent review rather than certifying universal loss finality.
