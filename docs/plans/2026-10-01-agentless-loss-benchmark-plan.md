# Agentless Loss/Finality and Measurement Implementation Plan

> **For agentic workers:** Use `executing-plans` inline in the existing workspace.
> No worktrees or delegation. Operator authorized continued execution without
> progress stops on 2026-10-01. The independent units below have separate gates.

**Goal:** Stabilize final definition-pipeline observations and produce reproducible
loss-integrity controls and three-mode event-triggered overhead measurements.

**Architecture:** Reuse the current Gadget/OTel pipeline, make definition channel
delivery cancellation-aware, and join the owned reader/consumer before final
error sampling. Add an events-only capture mode and a pinned finite benchmark
fixture/report. A distinct test gadget drops one marked event; normal source
contains no fault switch. All loss observations remain partial.

**Tech Stack:** Existing Go 1.26 module, pinned Gadget/OTel, pinned Temurin and
builder images, Linux x86-64, ordinary-user Java/privileged collector.

**Spec:** This document specializes Tasks 4–5 of the overall agentless/Kubescape
plan and the post-epoch review. It does not authorize upstream submission or
production MazeWall/Tier E changes.

## Global constraints

- Work on `fast-master` in the existing workspace; preserve unrelated operator work.
- Keep production JVM modules, USER_NOTIF, C++ agent and InvocationId unchanged.
- Retain all dependency/image pins and current enrichment/cache/buffer limits.
- Patch only checksum-verified generated sources with no-fuzz patches/additions.
- Joining a reader/consumer does not prove native producer buffers were drained.
  `event_loss`/`definition_loss` stay absent and health `complete=false`.
- Optional stop is bounded by a two-second context. A timeout is an explicit
  session error and cannot become successful finalization.
- Benchmark modes: `baseline`, `events`, `full`; only full starts OTel. Events mode
  emits operation records with explicit `unwind_disabled` terminal skips.
- Each measurement records exact workload/mode/runtime/build provenance. Modes
  share JVM flags, operation counts, warmup, pacing and path diversity.
- No collector baseline is a separate execution; never time-join its operations.
- Report distributions from repeated serial runs, not a production performance
  promise. CPU/RSS sampling covers its documented process/window only.
- Missing manifest tokens, queue skips, unavailable frames and native observed
  loss are separate dimensions. Observed zero never certifies zero total loss.

## Task 1: Definition shutdown ownership

**Files:** `patches/profiler-shutdown.patch`, additions under
`patches/profiler-additions/tracer/`, `patches/gadget-shutdown.patch`, additions
under `patches/gadget-additions/pkg/symbolizer/{otel/,}`, preparer,
`internal/capture/{collector,health}.go`, `internal/model/record.go`.

**Contract:**

```go
// Optional resolver capability, existing Close signature remains unchanged.
type DefinitionStopper interface {
    StopDefinitions(context.Context) (DefinitionStopObservation, error)
}
type DefinitionStopObservation struct {
    ReaderStopped, ConsumerStopped bool
    BuffersDrained bool // always false in this milestone
}
// Profiler-owned reader observation after cancellation:
func (t *Tracer) WaitTraceReader(context.Context) error
```

- [ ] Reproduce blocked definition sends and unjoined in-flight callbacks with
  channel-gated tests (no timing sleeps). A stopped consumer must not strand the
  reader, and stop must wait for a held handler's final callback.
- [ ] Replace both trace and batch-barrier channel sends with a helper selecting
  delivery versus cancellation. Return an undelivered trace to the existing pool.
- [ ] Add reader-done ownership and close its native reader when it stops. Add
  resolver-owned cancellation and consumer completion. Stop cancels then joins
  reader/consumer with the supplied deadline; it does not flush native buffers.
- [ ] Collector calls stop after Gadget returns and pending enrichment finishes,
  before health/fatal sampling. Timeout/permission/unexpected failures remain
  session errors. Report joined components and `buffers_drained=false` separately.
- [ ] Verify original waiter/pruning/health tests, new tests with `-race`, and a
  live strict fixture. Keep the issue open for review; record source-backed scope.

## Task 2: Honest three-mode capture and benchmark report

**Files:** `internal/capture/{ig,collector}.go`, CLI, `internal/benchmark/`,
`cmd/mw-benchmark-report/`, `fixtures/java/BenchmarkFixture.java`,
`scripts/run-benchmark.sh`, Makefile, report/unit/integration tests.

**Interfaces:**

```go
// Config.EventsOnly selects no OTel startup and disables both stack helpers.
type RunManifest struct {
    Mode string
    Operations, Warmup, Diversity int
    PauseNS uint64
    JVMImage, PatchSHA256, GadgetImage, Kernel string
}
// Report consumes measured target results and candidate JSONL, not a mock count.
func Analyze(RunManifest, io.Reader, io.Reader) (RunReport, error)
```

- [ ] Test invalid modes/counts/denominators, missing operations, terminal duplicate
  or identity mismatch, invalid latency ordering, and unsupported coverage claims.
- [ ] Add events-only mode without constructing a symbolizer/enrichment worker.
  Preserve every event plus an explicit skipped terminal; reject strict-epoch or
  debug-rendered combinations that would claim unsupported semantics.
- [ ] Fixture precreates tokenized files, warms both branch routes, waits behind
  a gate, measures finite marked opens with per-operation latency and writes a
  target summary. Diversity uses fixed named call chains; pacing is independent.
  Target output includes PID, UID, flags/counts, elapsed time and latency percentiles.
- [ ] Runner starts the same pinned ordinary-user JVM for each mode, measures
  collector CPU/RSS via read-only proc samples and records sample window/count,
  readiness time, source/class/image hashes, exact arguments and actual kernel.
  Unavailable resource dimensions remain absent, not invented zeros.
- [ ] Run three repeats per mode serially, then a bounded burst/diversity control.
  Report per-run values and medians/ranges with operation-count denominators.
  Do not compare CPU samples from unequal windows as workload-only overhead.

## Task 3: Controlled event-loss integrity

**Files:** `patches/faults/drop-marked-event.patch`, a test-only gadget builder
script, `internal/benchmark/`, live integrity test and Make target.

- [ ] Test a manifest with a dropped token: classify it as unobserved, never as an
  enrichment skip or unavailable stack. Sequence gaps are lower-bound evidence
  and cannot classify trailing loss or definition loss by themselves.
- [ ] Build a separate digest-labelled test gadget from a generated source copy.
  Drop one recognized marked event after its occurrence sequence is allocated;
  do not modify the normal gadget or add a normal runtime fault option.
- [ ] Run the live fixture through the fault image. Require the exact missing
  token, complete accounting for delivered events, zero wrong labels, and no
  upgrade of partial native health to final loss proof.
- [ ] Keep genuine first-definition-drop recovery as a separate control; run both
  event and definition loss gates after shutdown ownership changes.

## Task 4: Evidence and checkpoint

- [ ] Review scope and residual shutdown limitations. Save exact run artifacts,
  counts, resource windows and distributions; update experimental guidance.
- [ ] Run focused host tests first, appropriate live controls, `make verify`,
  backlog/whitespace/shell checks and repository `./gradlew build` once.
- [ ] Commit only scoped files and index rows. Broader native drain/finality,
  concurrent collectors/JIT lifetime, GraalVM and Kubescape remain separate gates.

The implementation tests are behavioral: real channel ownership, actual decoded
JSONL/token pairing, actual pinned workload measurements and a real isolated
event-drop control. No assertion merely checks that this prose appears in a file.

## Execution checkpoint (2026-10-02)

Tasks 1–3 are implemented locally with owned shutdown joins, events-only capture,
report/fixture/runner, isolated exact token-42 discard and repeated measurements.
The definition recovery and strict unloading controls passed after shutdown.
The expanded race gate found and fixed a raw-wire test-fixture bug independently
of the decoder. `make verify`, backlog validation and the repository merge gate
passed; the first merge attempt correctly rejected missing membership for the new
wire-test issue, then passed after its index entry was added.

The burst/diversity control failed useful cold coverage. An explicit optional
post-attachment preparation comparison was then implemented: identical fixed
preparation in all modes, separately tokenized and excluded from measured timing;
full mode requires every configured route's epoch-checked leaf/route segment in
a retained pre-gate observation, with bounded preparation and observation waits.
Three paced eight-route repeats passed 1200/1200 correct measured segments each.
The third ready burst lost 29 tokens and was rejected; no passing burst aggregate
is claimed. An extra-loss single-drop attempt was also retained and rejected.

Task 4 evidence is in
[`loss-benchmark.md`](../../experiments/hotspot-agentless/results/loss-benchmark.md).
Native transport finality/burst coverage and independent review remain open under
the scoped issues; this checkpoint is not approval to enable production usage.
