---
document_type: execution_plan
base_revision: b11d0d4a
status: implemented_event_only
---

# Owned Event-only Ring Finalization Implementation Plan

> **For agentic workers:** Use `executing-plans` inline in the existing workspace.
> No worktrees, delegation or push. The operator approved continued execution
> without intermediate updates on 2026-10-02.

**Goal:** Add opt-in bounded native finalization for the standalone event-only
single-openat-ring profile, preserving queued events and honest partial loss.

**Architecture:** An optional Gadget stop owner validates the exact attachment
scope, retires its producer, flushes/joins the original reader and callbacks,
reads quiescent native reserve-failure counters without resets, then closes the
reader. The collector emits an additive component observation. Failed/pending
ownership is retained rather than freed under active callbacks.

**Tech Stack:** Existing pinned Go/Gadget/profiler/Cilium graph, Linux x86-64,
existing native ring and datasource; no dependency or native layout change.

**Spec:** `docs/plans/2026-10-02-agentless-finalization-design.md`.

## Global constraints

- Preserve unrelated operator work on `fast-master`; stage only scoped files.
- Apply authored patches/additions only to verified generated modules with no fuzz.
- Keep native ring/cache/perf capacities and production JVM/Tier E unchanged.
- `--drain-events` requires structured events-only mode; full/strict/debug modes
  cannot opt in. Unsupported producer/transport ownership is refused.
- Use one two-second stop budget bounded by the remaining 60-second owned session.
- No definition reader or enrichment jobs exist in this profile; do not report
  those components as completed. Whole-session loss remains explicitly partial.
- Propagate native/permission/read/output errors. A timed-out callback cannot
  justify reader/map retirement or a successful final summary.

---

## Task 1: Owned state and supported scope

**Files:** additions under `patches/gadget-additions/pkg/operators/`,
`patches/gadget-additions/pkg/operators/ebpf/event_finalization*.go.txt`,
`patches/gadget-finalization.patch`, preparer.

**Interfaces:** optional `EventFinalizationOptions` passed through Gadget vars;
`EventFinalizationObservation` is an immutable snapshot. The private stop owner
consumes a backend exposing Quiesce, Flush, Join(ctx), Sample and Retire. Snapshot
fields distinguish writer quiescence, boundary/callback join, counter sampling,
reader retirement and the failed phase; loss completeness is always false.

- [ ] Write real owner tests for duplicate stop, held callback, deadline and
  EPERM/EACCES/flush/read/sample/retirement failures; observe failures before fix.
- [ ] Implement pure ordered transitions plus a single asynchronous effect owner.
  Snapshot locks never cover native calls or user callbacks. Duplicate calls
  share completion; errors preserve achieved phases and outstanding ownership.
- [ ] Scope validation requires one `capture_openat` kprobe on
  `__x64_sys_openat`, one `openat` tracer on the `events` ring, both stack helpers
  disabled, and no raw/perf/network/tc/uprobe/iterator attachments.
- [ ] Run selected event-operator host tests with `-race` through the generated graph.

## Task 2: Native operator/reader integration

**Files:** finalization patch/additions, original reader call site adaptation,
new native test addition, Makefile gate.

- [ ] Write a queued native event acceptance test using the actual stop owner and
  a real openat link. Hold the first datasource callback; stop must wait, then
  deliver all queued tokens and join after release. Trigger more opens after
  producer retirement and require no new native publications.
- [ ] Recognize `ErrFlushed` only in the requested owned drain. Reader completion
  follows callback completion. Legacy read/close behavior stays selectable.
- [ ] The opt-in profile has no racing background lookup/reset loss poller.
  Snapshot the retained native map after producer quiescence/readout and reject
  map errors or sum overflow. Forward the observation even without another event.
- [ ] Close the native reader only after join; keep failed/unjoined owners/maps
  retained on error. Default Gadget cleanup must not bypass the new owner.
- [ ] Verify native queued-tail and positive-pressure controls with the race detector.

## Task 3: Collector/CLI and report integration

**Files:** CLI, `internal/capture/{ig,collector,...}.go`, model summary,
benchmark report/runner, host tests.

- [ ] Test invalid full/strict/debug combinations and event delivery after graceful
  request cancellation; wire `--drain-events` only after the tests fail.
- [ ] Set optional stop options before Gadget Start. Tail output uses the owned
  consumption lifetime rather than the canceled request context.
- [ ] Emit event component observation after successful join. On pending ownership
  return the explicit error without trying to lock a held callback or emit a false
  final summary. Preserve delivered accounting, absent final loss, and real errors.
- [ ] Record the flag in benchmark manifests. Require valid component observations
  for opted-in runs, without turning them into complete collection-loss proof.
- [ ] Run the real pinned event-only workload with opted-in stop and missing-token
  accounting; retain any rejected pressure controls.

## Task 4: Evidence and checkpoint

- [ ] Source-review supported detach semantics, errors and partial-start handling.
  Keep producer in-flight retirement limits accurately scoped in evidence.
- [ ] Run focused race/kernel/live controls, `make verify`, backlog/whitespace/
  syntax checks, and repository `./gradlew build` after focused gates pass.
- [ ] Record exact artifacts and provenance; update design progress and guidance.
- [ ] Commit only scoped files and partial index membership/count. Full-mode
  definition finalization, broader attachments and complete loss are open gates.

## Execution evidence

The supported opt-in event-only path is implemented. Real native stop acceptance
passes eight queued tokens, 1000 pressure attempts with 255 delivered/745 native
failures and no later publications, and an owned deadline that retains active
callback resources. Host race tests cover scope/permission/budget/duplicate stop
and honest report claims. Three pinned burst repeats and a final guarded repeat
captured all 1200 measured tokens. A 10000-operation pressure run remains rejected
with 2894 missing tokens and matching partial native reserve observations.
Exact discard token 42 remains missing with native reserve failures zero.
Full definition drain and complete native loss remain unimplemented gates.
