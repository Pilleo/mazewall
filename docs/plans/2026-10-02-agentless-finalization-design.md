---
document_type: design
base_revision: d6c02260
status: proposed
---

# Bounded agentless capture finalization

## Goal and evidence boundary

Define an owned, bounded graceful-stop protocol that preserves already-published
event and definition records before resource retirement. The first checkpoint
contains native characterization tests and this design; lifecycle implementation
and whole-session loss completeness are subsequent acceptance gates.

The approved immediate milestone is a deterministic shutdown-tail proof plus
source-backed design. Existing pins, four enrichment workers, pending count/byte
limits, absolute BOOTTIME event deadlines, cache bounds and strict runtime profile
remain the constraints for implementing this design. Production JVM/Tier E and
USER_NOTIF modules are outside its scope. No new dependency or breaking interface.

## Current ownership and failure

Source bases: Gadget `7097db144ccd8dab15373f27ec98d2c3d22e4588`, profiler replacement
`ef6cf29492c8a980f889052903fa2dd0c0765cc0`, Cilium eBPF `v0.20.0`, plus the local
shutdown/epoch/event-read patches. Paths below are relative to the named module.

| Owner/source | Current behavior | Required distinction |
|---|---|---|
| Gadget `pkg/gadget-context/run.go:Run` | Waits for timeout/context cancellation, then reverse-order PreStop/Stop/PostStop; deferred Close runs before returning | A stop request must not also abort tail consumers |
| Gadget `pkg/operators/ebpf/ebpf.go:Stop` | Closes tracers, then detaches raw programs/links and disables/closes perf entry FDs, then waits | Retire writers before flushing readers; keep required maps alive |
| Gadget `pkg/operators/ebpf/tracer.go:close` | Closes loss-poller signal and native ring/perf reader | Reader close discards queued tail; sampler exit is not joined |
| Gadget `receiveEvents` | Treats non-closed reader errors as warnings and continues | Deliberate `ErrFlushed` is a drain boundary, not an ordinary retry |
| Gadget ring loss poller | Lookup/reset once a second; stores a pending report which is forwarded on a later read | Join poller and sample counters without relying on another event |
| OTel resolver `startOtelEbpfProfiler` | Reader, map/PID monitors and consumer share a child of collector context | Reader/consumer lifetime must survive graceful stop requests |
| Profiler `tracer/events.go:startTraceEventMonitor` | Cancellation exits before consuming a queued perf tail; cancellation-aware channel delivery may discard an undelivered trace | Drain mode needs a live consumer and explicit reader completion |
| Profiler `Tracer.Close` | Disables entry perf events, closes hooks and closes process manager together | Native producer retirement must precede metadata/process-manager destruction |
| Experiment `internal/capture/collector.go:Run` | Shared cancellation precedes enrichment Finish and StopDefinitions | Stop admission after event readout, keep definition/identity owners alive through enrichment |

Error paths also matter: Gadget `gadgets.CloseLink` ignores link close errors;
raw detach/perf disable failures are logged in current Stop; deferred context Close
errors are warned rather than joined into its result. Profiler Close similarly
logs hook/perf errors. These helpers cannot certify successful producer retirement
for the new contract. The new owned path must propagate detach/disable/close/read
failures, especially EPERM/EACCES, rather than treating them as successful drain.

## Deterministic native proof

See [queued-tail evidence](../../experiments/hotspot-agentless/results/queued-tail.md).
Eight unique records are successfully published before readout, on a single CPU
for deterministic perf ordering. After delivering token 1:

- Ring Close: seven queued tokens become unreadable.
- Perf Close: seven queued tokens become unreadable.
- Ring Flush: tokens 2–8 are delivered, then `ringbuf.ErrFlushed` is observed.
- Perf Flush: tokens 2–8 are delivered, then `perf.ErrFlushed` is observed.
- Actual Gadget receiver, with its first datasource callback held: Close followed
  by callback release completes the callback and joins the receiver, but only
  token 1 is delivered. All seven native tail records remain unconsumed.

The tests pass with the race detector. They characterize a known defect; they are
not a passing acceptance test for a future draining collector. The producer is
quiescent because its finite test invocations have returned. Production detach
and in-flight writer retirement still need their own proof.

## Approaches and decision

1. **Reorder detach and Close alone:** insufficient. Even with a quiescent
   producer, reader Close loses all queued tail records in the control.
2. **Delay stop or poll for apparent emptiness:** insufficient. Producer races,
   per-CPU perf wakeup thresholds, pending reservations and unsent loss reports
   prevent an empty observation or fixed sleep from proving completion.
3. **Owned quiesce → flush → join → finish → sample → retire:** selected. Reuse
   existing Cilium readers, datasource delivery, symbolization, cache and workers.
   Add explicit stop phases and optional capabilities without replacing buffers.

Implement first for the standalone experiment's supported event producer path,
not every Gadget network/uprobe/iterator attachment mode. Unsupported ownership
paths cannot assert successful quiescence or complete buffer finality.

## State and lifetime contract

A single finalization owner evaluates monotonic states and issues effects:

`Running → StopRequested → ProducersQuiesced → ReadersDraining → InputsClosed →
EnrichmentFinished → OwnersJoined → ObservationsSampled → ResourcesRetired`.

Any phase may enter `FailedPartial`; fatal target identity, permission, output or
native failures take priority over a pending graceful request. Duplicate requests
join the same result; they cannot repeat link/FD retirement or close a channel twice.
Model transitions separately from I/O rather than assigning state inside callbacks.

Three lifetimes are distinct:

- **Stop request:** operator SIGINT or requested duration initiates finalization.
- **Owned consumption:** event/definition readers, metadata owners, callbacks,
  enrichment and target incarnation/exec validation survive the graceful request.
- **Abort/deadline:** fatal errors or exhausted shutdown budget cancel owned work
  and return an explicit partial/error result. Cancellation is not a flush marker.

One total shutdown context is bounded by the existing two-second budget and the
remaining strict 60-second session deadline, whichever expires first. Each phase
uses the same remaining budget; do not grant two seconds independently per phase.
Keep each event's original one-second BOOTTIME deadline, never restart it on stop.
An external stop-request context may already be canceled, so it cannot be the
reader/consumer drain context. Preserving that lifetime must not detach the target
identity/exec guard, erase fatal errors or extend the strict session cap.

## Required phase behavior

1. **Quiesce owned native writers.** Detach the Gadget producer links and disable/
   detach applicable profiler native entry points before readers/maps are closed.
   Audit every writer to the selected event and definition maps. Successful
   detachment must include retirement of in-flight writers/reservations; prove
   that for supported link types. Error means partial failure, not quiescence.

2. **Drain both transports with live owners.** Initiate Cilium Flush only after
   writer quiescence. Keep event delivery, definition decoding, metadata and target
   validation alive. Reader loops consume the explicit flush boundary and finish,
   distinguishing it from closed/error/timeout. The current OTel polling/max-batch
   loop must continue its final drain across batch limits and account for empty/
   lost/error records. A deadline interrupt is not successful completion.

3. **Complete handoffs.** Reader completion alone does not prove its last callback
   finished. Definition readout requires an acknowledged terminal handoff and
   consumer completion, preserving the existing ProcessedUntil ordering. Event
   completion requires finished datasource callbacks and closed enrichment input.
   Do not cancel shared definition consumption before that acknowledgment.

4. **Finish bounded enrichment.** No new event admissions after the event reader
   boundary. Existing jobs finish using original deadlines and may end unavailable
   or skipped; drain does not upgrade attribution/epoch/BCI quality. Metadata/maps
   used by lookups remain owned until the corresponding jobs/callbacks finish.

5. **Join observers and snapshot.** Join the native loss poller as an owned worker.
   For the supported ring path, replace racing lookup/reset reporting with
   cumulative observations and regression/overflow checks; take a final map read
   after quiescence without requiring a later event to forward it. Preserve
   existing consumed-record metrics and their interval resets separately.

6. **Retire resources last.** Metadata/process manager, maps, native readers and
   ancillary watches are released only after their users are joined. Preserve
   successful/failed retirement independently and aggregate real errors. A timed
   out callback/native operation cannot be made bounded by closing resources it
   may still access. Mark outstanding ownership explicitly; no successful final
   result or session reuse until it is actually retired. Dedicated CLI failure
   termination is not an in-process join guarantee.

Cilium Flush does not stop producers. `ReadInto` can spin on a busy ring reservation;
its deadline/poller wakeup is not a proof that an in-flight reservation has retired.
The deadline/error gate must handle a non-returning read/callback without falsely
reporting drain or freeing a resource in use. This is an explicit implementation
acceptance requirement, not an assumed guarantee of calling Flush or Close.

## Observations and claim discipline

Add an optional component-scoped finalization observation to session summaries:
transport, stop cause, producer-quiescence outcome, each reader's boundary outcome,
callback/consumer joins, enrichment completion, loss-sampler join/counter-read
outcome, retirement outcome and the first failed phase. Not-started/not-applicable
components must remain distinct from completed components; events-only mode has
no definition reader. Existing summaries/interfaces remain usable.

`buffer drained` requires quiescent writers, an explicit reader boundary and
finished downstream handoffs. It is independent of delivered-event terminal
accounting and independent of native loss completeness. A producer output failure
cannot be recovered by drain. Final event/definition loss fields remain absent
until their exact covered failure paths are proved. Perf consumed lost records
alone do not certify trailing output failures; definition submit/decode loss is
also outside a mere reader-join proof. Successful full claims must name the
supported transport/profile, never imply universal kernel or Java fidelity.

Benchmark timing must retain the existing target-workload denominator. Record
stop/drain intervals separately; preserve attach-to-target-done resource windows
and label any additional finalize-inclusive CPU/RSS window explicitly.

## Staged implementation gates

1. Pure state/ownership tests: repeated stop, partial startup, detach failure,
   deadline, EPERM/EACCES, held callbacks and no premature resource retirement.
2. Event-only supported path: deterministic queued-tail acceptance through the
   actual Gadget stop owner, including last event/callback and flush-boundary
   handling. Component drain evidence may pass while overall loss stays partial.
3. Full mode: queued genuine definition plus event, final consumer acknowledgment,
   no metadata teardown race, terminal accounting, original quality/epoch guards.
4. Quiescent final counter sampling: positive native loss with no later event,
   concurrent sampling isolation, counter regression/overflow and permission errors.
5. Integration: exact token-42 loss, genuine definition-drop recovery, strict
   unloading, startup/target-exit/exec/fatal/deadline paths and bounded pressure.
6. Independent review of detach semantics and covered loss domains before enabling
   any complete-loss claim. Keep native capacity/fidelity milestones separate.

This design chooses the ownership protocol, not an already verified implementation.
Its execution plan must preserve the current experiment's pins and review scope.

## Event-only implementation checkpoint (2026-10-02)

The supported event-only ring phase is implemented behind `--drain-events`.
Source scope validation requires the single openat kprobe/tracer, disabled stack
helpers and the native per-CPU uint64 reserve counter. The opt-in path has no
background reset poller; it samples retained counters after writer retirement and
reader/callback join, including when no later event exists. Resource failures and
deadline/pending ownership remain explicit errors. Full-mode definition/perf drain
is still a proposed implementation gate; legacy full capture retains partiality.

Native acceptance now verifies actual single-openat-link retirement, all queued
records delivered, native failure accounting without another event, and no new
publications after detach. A held callback exceeding budget prevents map/reader
retirement and repeated Close cannot bypass the owner. Independent review of the
supported detach path and broader covered loss domains remains outstanding.
See [implementation evidence](../../experiments/hotspot-agentless/results/event-only-finalization.md)
and the scoped execution plan under `docs/superpowers/plans/`.

## Strict full implementation checkpoint (2026-10-02)

Subsequent auxiliary retirement correction (2026-10-03): strict profiler auxiliary
workers now have independent cancellation, sealed enrollment, checked wake/close
actions and joins. Lazy Gadget pruning is sealed/joined too. Full handoff finishes
enrichment, then joins auxiliary work before event collection retirement under
the existing remaining deadline. `auxiliary_stopped` is an independent required
full-report observation, not a complete native loss or auxiliary-buffer drain.
See [auxiliary retirement evidence](../../experiments/hotspot-agentless/results/auxiliary-worker-retirement.md).

Subsequent metadata watermark correction (2026-10-03): interpreter detach errors
now use an additive checked boundary, preserve failed process/instance ownership
and native errno causes, notify the strict fatal owner after unlocking, and
propagate through original definition readout and reader joins. See
[metadata-retirement evidence](../../experiments/hotspot-agentless/results/metadata-retirement.md).
This addresses the specific watermark error gap without upgrading complete loss
or auxiliary-monitor lifecycle claims.

Optional `--drain-full` now requires full structured strict epoch capture and one
owner. The event stop owner's existing deadline covers an after-readout hook that
retires profiler-native hooks, flushes/joins definition readout and final consumer
callback, then finishes enrichment before Gadget resource retirement. Request
cancellation is distinct from owned identity/definition/enrichment lifetime;
fatal, target-exit/exec, permission or deadline errors remain partial aborts.

The native perf control verifies all eight queued raw definitions and a 5000-record
tail crossing the ordinary batch cap, with explicit flush/channel completion.
Actual strict workload, genuine definition-drop recovery and unloading controls
pass both component observations. Buffer drain remains independent of submit/
probe/decode loss and metadata cleanup/error coverage; full whole-session loss
fields and broader lifecycle/fidelity claims remain gated. See
[strict full evidence](../../experiments/hotspot-agentless/results/full-definition-finalization.md).
