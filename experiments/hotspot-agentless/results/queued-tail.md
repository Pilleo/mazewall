# Native queued-tail shutdown characterization

Checkpoint based on `d6c02260`, collected 2026-10-02 on x86-64 Linux
`7.2.8-x64v3-xanmod1`. Gadget/profiler/Cilium versions and container image digests
remain the experiment's pinned inputs. This checkpoint adds a native test fixture
and [finalization design](../../../docs/plans/2026-10-02-agentless-finalization-design.md),
not a draining capture implementation.

## Deterministic test

```bash
DOCKER_HOST=unix:///var/run/docker.sock make queued-tail-kernel-test \
  DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

The target builds with `-race` and runs inside an owned privileged pinned Go
container. Eight finite native BPF invocations publish distinct tokens before
readout. Every helper return is checked. The producer thread is temporarily
pinned to one permitted CPU and its original affinity restored before fixture
readout, making perf token ordering independent of cross-CPU ring merging.
The isolated ring is 4 KiB; isolated perf data buffers are 4 KiB per CPU.
No fixture output fails and no loss sampler is needed for this small queue.

After token 1 has been consumed, compare native reader Close and Flush:

| Transport/action | Successfully published | Delivered | Queued tail unconsumed | Boundary |
|---|---:|---:|---:|---|
| Ring Close | 8 | 1 | 7 | `os.ErrClosed` |
| Ring Flush | 8 | 8 | 0 | `ringbuf.ErrFlushed` |
| Perf Close | 8 | 1 | 7 | `os.ErrClosed` |
| Perf Flush | 8 | 8 | 0 | `perf.ErrFlushed` |

Flush controls verify the exact sequence 1–8 with no duplicate/reorder/corruption.
Producer quiescence is known because all finite invocations have returned before
readout. These are direct reader primitives, not an assertion that Gadget or OTel
currently recognizes a flush boundary as successful finalization.

## Actual Gadget close path

The fixture also starts the real `Tracer.receiveEvents` against its actual ring
and datasource. A channel holds the first subscriber callback while the remaining
native tail is queued. Calling the real `Tracer.close`, then releasing the held
callback, yields:

- Eight successfully published native tokens.
- Callback 1 completed; receiver and wait group joined.
- Only token 1 delivered; **seven queued tokens unconsumed**.

The test has no timing sleep; channel gates determine ordering. Two-second guards
bound failed test waits. Both ordinary and race builds passed the characterization;
the final fixture additionally scopes/restores producer affinity to guarantee
single-CPU perf order.

A first attempt to select the producer CPU via the BPF program-run CPU flag was
rejected with EINVAL for these XDP fixtures. The final fixture explicitly pins its
owned OS thread instead and fails on any affinity error; this is not a runtime
capture fallback or a swallowed permission failure.

## Source-backed consequence

Pinned Gadget Stop closes its tracers before detaching writer links. Reader Close
is resource retirement, not a drain operation. Gadget also retries a reader's
non-closed error, so adding Flush alone would warn/retry its `ErrFlushed` marker.
OTel cancellation exits trace readout/consumption before queued definition drain;
its Close couples native hook retirement with metadata teardown. Thus the existing
joined-worker observations correctly retain `buffers_drained=false`.

The design requires separate stop-request and consumer/abort lifetimes, owned
producer retirement, explicit flush boundaries, callback acknowledgments,
bounded enrichment, observer joins, final sampling and resource retirement.
Unsupported/incomplete phases are explicit partial failures. Native loss counts
and Java attribution quality are separate from buffer-drain evidence.

## Provenance

Proof bundle:
`b43ec9c9c9c7d35cc952dde7e3049eba44e31e61b16a4043c62348bb9fa2d970`.
Compared with the preceding
`69c7ecda47e0f0151d7377801d224952e829fd7c84d741b2e55ddc5a381d79c4`:
all 345 selected profiler and 540 Gadget **production** Go/C/header/BPF files have
identical file sets and bytes. The new source input is a kernel-tagged test; native
layouts, embedded programs, capture behavior and buffers are unchanged.

The native characterization is evidence for the proposed finalization contract.
It does not prove producer detach/in-flight quiescence, full definition drain,
native loss completeness or a lossless high-rate envelope.

Final checks passed: race-enabled `make queued-tail-kernel-test`, `make verify`
(local/upstream race tests, build and module graph/checksum checks), backlog
validation and repository `./gradlew build`. The final kernel target includes
the single-CPU perf ordering control and scoped affinity restoration.
