# HotSpot production accounting proof implementation plan

> **For agentic workers:** After operator approval, use `subagent-driven-development` or `executing-plans` task-by-task. Use the configured `implementer` and `reviewer` roles; give each independently testable sub-deliverable its own file scope and evidence checkpoint. This document does not start agents, implementation or native execution by itself.

**Goal:** Finish issue 042's production conservation, independent reconciliation and all-source proof without relabelling the completed selected-case qualification as broader evidence.

**Architecture:** Keep the existing native owners, readout windows, accounting reducers and production inventory. Preserve independently acquired identities and immutable final snapshots through capture, then join native admission evidence with actual enrichment, wire and bounded-client receipts. Admit each lane separately; compute global completeness only at the client, after final delivery and strict retirement.

**Tech Stack:** Existing Go collector and pinned Gadget/OTel profiler patches, C/BPF producers, shared Go wire model, Kotlin client and existing qualification control plane. The accepted qualification used Go 1.27.3, Cilium 0.22.0 and CGO=0; this is not an instruction to upgrade repository dependencies.

**Spec:** [selected accounting contract](2026-10-17-hotspot-selected-accounting-contract.md), [production loss-source inventory](2026-10-17-hotspot-production-loss-evidence.md), [remaining-work specification](2026-10-17-hotspot-remaining-work-spec.md), [issue 042](../internals/backlog/issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md).

## Global constraints and execution gate

- **PLAN ONLY. Operator approval is required before implementation, preparation, compilation or any native run.** Approval must cover the actual changed graph, source-specific receipt additions and bounded native cases, not merely this document's existence. No commands below have been executed for this plan.
- Work in the current checkout. No worktrees, commits, pushes, merges, new dependencies, breaking API changes, core BPF scan redesign, issue creation or issue archival. Existing modified files are user-owned. Edit only scoped files after approval; never clean up the unrelated patch EOF warnings.
- Preserve JDK 22 minimum/JDK 25 idioms, capture ABI/wire compatibility, event-only agentless operation, the process-wide security floor, strict ownership and fail-closed native errors. Never handle `GITHUB_TOKEN`.
- Preserve the existing remaining deadline, PM lock/lifetime checks, Observed25 READY before finite burst, scalar-map `UpdateNoExist` and owned-prefix rollback. N1 stays at ordinary 20, without a bespoke RLIMIT increase; the explicit 8192-pressure control stays unchanged.
- Missing admission, malformed rows, identity mismatch, overflow, incomplete CPU coverage, unknown quiescence, native EPERM/EACCES, timeout, held delivery or uncertain retirement mean **UNKNOWN/failed proof**, never zero loss or successful fallback. Keep errno and the full failed receipt.
- Take counter snapshots after producer quiescence and the required reader/consumer barriers, **while the original map FDs and native readout capability are valid**. Only publish final proof after all required owned resources retire. Never read maps after retirement or claim held resources as success.
- Use finite outcomes and checked arithmetic. Reuse `Counts`, `Proof`, `Binding`, `Snapshot`, `LaneEvidence`, `MissingEvidence` and native definition types; do not build another generic accounting/authority framework.
- Preserve byte-equal canonical/mirrored files under `profiler/hotspot-agentless` and `experiments/hotspot-agentless` wherever an existing mirror exists. Update genuine preparation inputs, manifests, embedded assets and goldens together. Do not stamp a changed graph with old CURRENT labels.
- Any unavoidable public signature break, dependency change or broader source instrumentation than the bounded additions below is a stop-and-review scope decision, not an executor's discretionary extension. Optional internal capability forwarding must not add methods to an existing required interface.
- A source with no defensible denominator remains unproven. In particular, owned auxiliary-worker joins do not by themselves establish complete kernel netlink/report delivery. Do not narrow the existing definitions lane to `trace_events` to make it pass.

## Baseline and source-grounded gap assessment

The current checkpoint is **17/17 CURRENT selected qualifications**: one capless ABI case and sixteen non-ABI cases, from one frozen graph and four binaries. Older 11/17 and failed-state passages are historical, not the current result.

- Source fingerprint: `7e09e472bd37c2c7b03aeb14dad509a0b56485bcfef2ddcb56e0891a2c08c7f9`.
- Independent acceptance: `/tmp/opencode/master-merge-mr5fp6z4/inspection-3i8iwo0d/qualification-acceptance.md`, SHA-256 `0b2b23d9534122c6b1348b90f382d1ab8eb23811dce174e492302813248e1a90`.
- Actual invocation: `/tmp/opencode/master-merge-mr5fp6z4/inspection-9nx17p27/primary-invocation.json`, SHA-256 `bbb7cbca50a2832e97113d5e79446efbecd06f940a84d0890a455e1136bae67c`.
- That invocation explicitly records aggregate conservation, reconciliation, all-source completeness and installed-kernel-image identity as unproved. Issue 042 stays OPEN.

| Existing implementation | Correct classification of remaining work |
| --- | --- |
| `internal/accounting/conservation.go`: `Aggregate`, `Evaluate`; `binding.go`: `BindAndEvaluate` | Already implemented; production evidence gap, not a request for another reducer. |
| Gadget `selected_accounting_native.go.txt`: acquired original map, ID/layout/CPU checks, `sampleAfterJoin`; backend `Sample` before `Retire` | Already implemented; prove the actual production invocation and receipt, do not move sampling after close. |
| `capture/selected_accounting.go`: independent prepared identity, strict retired parent, overflow-checked terminals | Already implemented and tested; retain and extend negative evidence, not replace with final-binding self-comparison. |
| Profiler `TraceDefinitionBinding`, `checkBinding`, `TraceDefinitionFinal`, `reconcileTraceDefinitions` | Native readout and reconciliation already exist. Preserve source/config/perf/counter identity, generation, CPU cardinality and sticky failure. Actual aggregate production evidence remains missing. |
| Gadget `definition_drain.go.txt` retains `DefinitionStopObservation.TraceDefinition` | Accounting observation exists upstream of capture. |
| `collector.go` ignores the returned `DrainDefinitions` value in `AfterReadout`; `health.go::definitionStopModel` only copies physical-stop flags | **Code gap:** final definition accounting is dropped before production inventory. |
| `capture/inventory.go::applyCollectionInventory` populates only `SelectedEvents` | **Code gap:** four other lanes have no production admission adapters. Existing `inventory_test.go` deliberately expects those lanes missing. |
| `Enricher.Submit` increments Received before writing, and increments skipped before the terminal write; JSONL currently just calls `Encoder.Encode` | **Code gap:** attempts/classification are not an independent successful-delivery receipt. A failed write must not become a delivered terminal. |
| Kotlin assembler checks event/terminal pairing and summary counts; `HotSpotEvidence.collectionLossComplete` and evidence-document output are hardcoded false | Existing local checks are useful, but whole-stream/retention proof and the final global conjunction are absent. |

### Readout lock finding: verify, do not inherit a historical conclusion

`nativeEventStopBackend.Join` releases the owner mutex before `AfterReadout`; its action is not run under that outer lock. However, current `withDefinitionReadout` itself locks `o.mu` with deferred unlock and invokes `action()` while locked. `TraceDefinitionFinal` also owns its source mutex during checked map access. Do not describe historical D-02 as either fixed or still reproducing without a focused blocked-action regression. Task 1 must establish deadline/snapshot progress and prevent map retirement during a borrowed readout, without enabling post-deadline cleanup or holding the snapshot mutex over arbitrary work.

## Proof vocabulary and exact denominators

`Evaluate` currently defines selected conservation as:

```
A = S + F; R <= S; T = R; Lost = A - R
T = Resolved + Unavailable + Skipped   (checked additions)
```

Every possible-CPU row must independently satisfy its equation before aggregation. `Complete` means the loss denominator is known; it does **not** mean `Lost == 0`.

Native trace-definition reconciliation currently defines:

```
A = S + OutputFailed + RejectedSize
Reader.Received = HandedOff + RejectedRecord + HandoffAborted
Reader.Received <= S
Undelivered = S - Reader.Received
EmissionLoss = OutputFailed + RejectedSize + Undelivered
```

Check all sums for overflow. `LostSamples` is diagnostic, not another additive term in `EmissionLoss`. A zero-attempt `TraceDefinitionNotExercised` result is not evidence that an exercised positive-production definition lane succeeded. Trace definitions are only one definitions subsource; auxiliary admission and definition-store consumption remain separate obligations.

For the additional boundaries use these denominators, not selected-map totals recycled under new names:

- **Enrichment:** actual calls admitted to the owned input boundary; classify each as queued, immediate budget-skipped, or rejected. Each admitted event has exactly one successful event record and one successful terminal write, including budget skips; pending work is zero after Finish. Track failed writes separately from logical classification.
- **Wire:** every complete JSONL record preceding the final summary, plus its exact encoded bytes including newline. Producer and reader independently compute record count, byte count and SHA-256. The summary carries the producer prefix receipt; it does not count/hash itself. Require one final summary and EOF, in addition to existing schema/session/identity/pairing checks. Truncation, trailing records and duplicates fail.
- **Retention:** every validated event-terminal pair and recovery record admitted by the client before the final summary, including non-retained ones. Reconcile admitted pairs = retained pairs + discarded pairs; admitted recoveries = retained recoveries + discarded recoveries. Pair completeness, byte limits, pending count and discard classification must all remain checked. Known retention loss is not silently converted to zero loss or `retentionComplete`.
- **Definitions auxiliaries:** each enabled owned producer/queue/consumer has its own admission/classification/consumption receipt and final owner identity. Perf `report_events` requires producer admission, not just reader loss. Polling/prune/async owners need scheduled/admitted work versus completion/cancellation classification. A kernel source whose upstream admission cannot be measured cannot be promoted merely because its owned reader joined.

## Task 1 — Preserve production readouts and prove aggregate conservation

**Owned artifact:** immutable trace-definition rows and selected rows from one valid production readout, with independently acquired preparation identities and explicit finality. Existing native reducers remain authoritative.

**Files:**

- Modify `profiler/hotspot-agentless/internal/capture/collector.go`, `health.go`, `inventory.go`.
- Add `profiler/hotspot-agentless/internal/capture/definition_accounting.go` and `definition_accounting_test.go`.
- Modify `profiler/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/definition_drain_native.go.txt`, `definition_drain.go.txt` and `definition_drain_test.go.txt` for optional prepared-binding forwarding and observation preservation.
- Modify only if the blocked-action regression requires it: Gadget `pkg/operators/ebpf/definition_readout.go.txt`, `event_finalization_owner.go.txt`, `event_finalization_backend.go.txt` and their existing finalization/readout tests.
- Preserve profiler `tracer/definition_accounting.go.txt`, `definition_native.go.txt`, `definition_reader.go.txt`; extend `definition_accounting_test.go.txt` and `definition_accounting_kernel_test.go.txt` rather than replacing their real readout.
- Mirror changed existing files and update `profiler/hotspot-agentless/patches/README.md`.

**Interfaces:** retain `TraceDefinitionBinding() (libpf.TraceDefinitionBinding, error)` and `TraceDefinitionFinal(context.Context) (libpf.TraceDefinitionObservation, error)`. The resolver may forward the former through a new optional capability asserted by capture; do not change the required symbolizer interface. Capture owns `definitionAccounting` with `expected libpf.TraceDefinitionBinding` acquired before production and `final libpf.TraceDefinitionObservation` copied during `AfterReadout`. Absence is an error for this proof mode, not fabricated zero rows. Consume the existing `DefinitionStopObservation.TraceDefinition` rather than sampling again later.

- [ ] Add a host regression in the existing definition-owner test package: block the readout action on a channel, expire its context, request the owner snapshot concurrently, and assert snapshot/deadline reporting returns without waiting for the action. Assert retirement does not close borrowed maps until the action returns; record UNKNOWN/retained ownership, not final success. Release the test channel explicitly so the test itself leaves no goroutine behind. This test decides whether a lock change is needed.
- [ ] If RED, replace the broad mutex-held callback with a narrow, single admitted readout lease: validate/copy state under `o.mu`, mark the active lease, unlock for the action, then lock to seal outcome. Concurrent readout is rejected; retire cannot pass an active lease. Deadline expiry records failure promptly but does not close resources under the action. Do not add a retry, independent deadline or cleanup grace period.
- [ ] Acquire and retain the definition preparation binding through the actual resolver/tracer before READY. Store the returned `DrainDefinitions` observation inside `AfterReadout` while the native readout window is active. Copy the Rows slice; keep original errors and the original remaining stop context. Preserve the existing order: event quiescence/drain → definition readout/consumer barrier → enrichment Finish → auxiliary stop → selected Sample → resource retirement → final summary.
- [ ] Add this concrete regression to `definition_accounting_test.go.txt`; it tests real existing arithmetic independently of native permissions:

```go
func TestDefinitionAggregateCannotHideContradictoryRows(t *testing.T) {
    b := definitionBindingFixture()
    b.PossibleCPUs = 2
    rows := []libpf.TraceDefinitionCPUCounts{
        {Attempted: 2, Submitted: 1},
        {Attempted: 1, Submitted: 2},
    }
    _, err := reconcileTraceDefinitions(b, rows, libpf.TraceDefinitionReaderCounts{})
    if err == nil { t.Fatal("aggregate equality hid invalid per-CPU rows") }
}
```

- [ ] Run the focused host packages through the existing genuinely prepared source graph: capture/accounting tests and the patched tracer/OTel/operator host suites, with JSON test events and race checks. Use the same cached-upstream preparation mechanism as the accepted graph; do not run `go test` against unpatched downloaded upstream. Existing collector entry is `make -C profiler/hotspot-agentless unit`; inspect its effective prepared module paths before execution and retain them with the result. Expected outcome: no native cases selected, the new blocked-action and row tests execute, existing selected proof tests stay green.
- [ ] Retain the affected native test executable selection for Task 4: `TestKernelDefinitionPerfDrainAcknowledgesQueuedTail` and the existing definition-accounting production case. Do not run native tests in this task without the later fresh authority gate.

## Task 2 — Reconcile each attempt against independent identities

**Owned artifact:** a pure checked definition diagnostic and a per-attempt receipt pairing expected identity, actual final identity, raw rows and independently delivered counters. No receipt can supply its own expected identity.

**Files:** Task 1's `definition_accounting.go`/test; existing `internal/capture/selected_accounting.go`, `selected_accounting_test.go`, `inventory_test.go`; profiler `tracer/definition_accounting_test.go.txt`, `definition_accounting_kernel_test.go.txt`; existing `internal/accounting/binding_test.go` and `lanes_test.go`.

**Interface:** introduce only the capture-local pure function
`definitionDiagnostic(expected libpf.TraceDefinitionBinding, final libpf.TraceDefinitionObservation) (accounting.Counts, error)`.
It checks complete equality of separately acquired bindings, validates the native definition equations above and converts the verified trace subsource to `Counts`: Attempted=A, Submitted=S, ReserveFailed=OutputFailed+RejectedSize, Received=Reader.Received, Terminals=HandedOff+RejectedRecord+HandoffAborted. This conversion is a trace subsource diagnostic, **not** admission of the whole definitions lane. Finality comes from the owner observation and strict parent, not this arithmetic conversion alone.

- [ ] Write the binding-mutation table before adding the adapter. Use `definitionBindingFixture()` and `validateTraceDefinitionBinding` in the tracer package; mutate each identity component independently. This is executable regression code, not a final-binding self-comparison:

```go
func TestDefinitionExpectedIdentityIsIndependent(t *testing.T) {
    expected := definitionBindingFixture()
    for _, mutate := range []func(*libpf.TraceDefinitionBinding){
        func(b *libpf.TraceDefinitionBinding) { b.Session += "x" },
        func(b *libpf.TraceDefinitionBinding) { b.HostTGID++ },
        func(b *libpf.TraceDefinitionBinding) { b.Birth++ },
        func(b *libpf.TraceDefinitionBinding) { b.CounterMapID++ },
        func(b *libpf.TraceDefinitionBinding) { b.PerfMapID++ },
        func(b *libpf.TraceDefinitionBinding) { b.Generation++ },
        func(b *libpf.TraceDefinitionBinding) { b.PossibleCPUs++ },
    } {
        actual := expected
        mutate(&actual)
        if validateTraceDefinitionBinding(expected, actual) == nil {
            t.Fatal("changed source identity accepted", actual)
        }
    }
}
```

- [ ] Implement `definitionDiagnostic` with checked additions and row-by-row validation. Check unknown source/outcome, missing binding/rows, invalid cardinality, sticky native/reader overflow, contradiction, undelivered rows and mismatching native totals. Keep the existing native `checkBinding` before/after the map read; do not replace actual map metadata with cached final report fields.
- [ ] Extend the same negative table at capture's adapter boundary: preparation absent, malformed final, changed PID/birth/session/maps/generation/CPU, non-final/NotExercised, held consumer and parent not retired. Verify that missing evidence stays missing even when every supplied final field agrees with an invalid expected binding. Retain the existing selected diagnostic's map/CPU/terminal-overflow tests.
- [ ] Add this existing-API guard to `internal/accounting/lanes_test.go` to prevent a zero-count fixture from being mistaken for an exercised production proof:

```go
func TestKnownLossIsNotZeroLoss(t *testing.T) {
    p := Evaluate(Counts{Known: true, Quiesced: true, Joined: true,
        Attempted: 10, Submitted: 8, ReserveFailed: 2,
        Received: 7, Terminals: 7})
    if !p.Complete || p.Lost != 3 { t.Fatal(p) }
}
```

- [ ] Repeat the focused host/race packages from Task 1 and collector accounting/capture tests. Expected: every mutation rejects at the intended identity/arithmetic boundary; zero rows are labelled not exercised for qualification; known nonzero loss remains a known denominator, not a clean run.

## Task 3 — Admit the four remaining lanes at their actual boundaries

This task has four independently testable sub-deliverables. Execute and verify 3a, 3b, 3c and 3d separately rather than handing their combined changes to one worker. Keep source-specific receipts; do not create a combinatorial harness or map all source IDs onto `selected_counts`.

### 3a. Definitions, including auxiliaries

**Files:** capture's definition adapter and `inventory.go`; profiler additions `tracer/definition_reader.go.txt`, `definition_accounting.go.txt`, `auxiliary_stop.go.txt`, `periodiccaller/owned_scope.go.txt`; patches `profiler-auxiliary-ownership.patch`, `profiler-definition-drain.patch`, `gadget-auxiliary-ownership.patch`; their existing reader/owned-scope/auxiliary tests. Native emission hooks belong at existing prepared `support/ebpf/tracemgmt.h`/`interpreter_dispatcher.ebpf.c` patch sites, not a parallel BPF program.

- [ ] **Feasibility gate before native instrumentation or an image rebuild:** update the existing production-loss evidence document with one row for each enabled trace/report producer, PID/netlink or kobject source, polling/prune owner, async update queue and definition-store consumer. Each row must name the actual preparation owner/identity, admission denominator, terminal classification, finalization barrier and rejecting oracle. Classify it as measurable, authoritatively disabled for this exact profile, or active/unmeasured. A local receive count plus successful StopAuxiliary is insufficient to prove kernel admission. Socket overflow/truncation/reader failure stays UNKNOWN. If an active source lacks an independent upstream denominator or defensible admission boundary, stop the all-source promotion/instrumentation branch and record the precise blocker for an operator scope decision; do not first rebuild and then discover the denominator is absent. Tasks 1–2 may still finish their narrower production accounting proofs. Do not substitute a synthetic zero, disable a source merely to pass, or narrow the definitions contract to `trace_events`.
- [ ] Preserve the trace binding/rows and extend the receipt through definition-store consumption, not merely handoff to its channel. Classify each received target definition as consumed, rejected or aborted; a dropped store update cannot be hidden by a joined reader.
- [ ] At the existing owned auxiliary boundaries (`StartPIDEventProcessor`/`processPIDEvents`, `pollKobjectClient`, periodic/prune work, async update queue), count actual admitted work and terminal classification with sticky overflow. Bind these snapshots to the created owner instance, source kind and capture target/session; take them after stop/join, before owned map finalizers close maps.
- [ ] For `report_events` add native attempted/submitted/output-failed accounting at the existing emission callsites, using the current definition-counter pattern, and capture the original report map/counter map IDs and possible-CPU rows at preparation/readout. Update only the affected pinned native inputs and generated outputs. This changes the native graph; old 17/17 becomes baseline evidence for the old graph, not proof for this candidate.
- [ ] Extend existing native-reader and owned-scope host tests with queued work, duplicate completion, failed callback, cancellation, rejected store delivery and overflow. Each control must demonstrate its specific counter/oracle changed before asserting rejection. The positive definitions lane requires every enabled subsource receipt, not just `TraceDefinitionFinal`.

### 3b. Enrichment admission versus successful delivery

**Files:** `internal/capture/enrichment.go`, `enrichment_test.go`, `collector.go`, `inventory.go`; new internal `enrichment_accounting_test.go` only if keeping these focused tests separate improves readability.

**Interface:** add unexported `Enricher.accounting() (accounting.Counts, error)`, valid only after successful Finish. Keep queue/write error ownership local. It reports independently counted input admissions, queued/immediate-skip/rejected classifications, and successful event/terminal writes; never reconstruct write success from `stats.Resolved/Unavailable/Skipped` alone.

- [ ] Test a sink that fails only on terminal delivery, using existing `model.Sink`, `NewEnricher` and `EnrichmentOptions`. Assert logical resolution/skipping does not produce a complete delivery receipt. Test queue-count and byte-budget skips as successfully classified terminals only when the sink accepted their terminal record; test submit-after-close and held worker/deadline separately.

```go
type terminalFailSink struct{ triggered bool }
func (s *terminalFailSink) Write(_ context.Context, r model.Record) error {
    if r.Kind == "enrichment" {
        s.triggered = true
        return errors.New("terminal delivery rejected")
    }
    return nil
}

func TestFailedTerminalCannotAdmitEnrichment(t *testing.T) {
    sink := &terminalFailSink{}
    lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
        return oneFrame("pathA"), nil
    })
    e, err := NewEnricher(context.Background(), fixtureOptions(), lookup, sink,
        bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
    if err != nil { t.Fatal(err) }
    if err := e.Submit(fixtureEvent("event-1", 7)); err != nil { t.Fatal(err) }
    if err := e.Finish(); err == nil { t.Fatal("terminal failure swallowed") }
    if !sink.triggered { t.Fatal("fault did not reach terminal writer") }
    counts, err := e.accounting()
    if err == nil && accounting.Evaluate(counts).Complete {
        t.Fatal("failed terminal admitted enrichment")
    }
}
```

The test reads `triggered` only after Finish joins the worker; keep that happens-before guarantee. Reuse the existing fixture helpers and add the accounting import to the test file.
- [ ] Increment successful-delivery counters only after `sink.Write` returns nil. Add sticky checked-overflow handling for proof counters; preserve existing stats semantics and budgets. `Finish` freezes the receipt only after owned workers and writes finish. Wire failure remains an error even if terminal classification counters balance.
- [ ] Require this receipt in inventory and preserve selected-event reconciliation against the original selected rows. An enrichment lane is not admitted from selected Received/terminal totals alone.

### 3c. Actual wire prefix receipt

**Files:** `internal/output/jsonl.go`; add `internal/output/jsonl_test.go`; capture `collector.go`/`inventory.go`; Kotlin `HotSpotWireReader.kt`, `HotSpotCollectorWorkers.kt`, `HotSpotSessionInterpreter.kt`, `HotSpotEvidenceAssembler.kt`; tests `HotSpotEvidenceAssemblerTest.kt`, `HotSpotStreamingEvidenceTest.kt`. Keep `hotspot-bridge/wire/record.go`'s existing `Summary.Extensions` mechanism and unknown-field preservation; no required wire-field or ABI change.

**Interface:** add `(*JSONL).AccountingPrefix() (records uint64, bytes uint64, sha256 string, err error)` to the existing internal output package; capture asserts this narrow optional capability on its sink. Snapshot the prefix before writing the final summary. Encode `production_accounting` into the existing summary Extensions, with `schema: 1`, session/PID/birth, source-specific receipts and `wire_prefix: {records, bytes, sha256}`. This optional extension is a diagnostic proof envelope; absent or unknown schema means UNKNOWN. Do not use it as native authority.

- [ ] Keep one locked encoding/write boundary. Count/hash exact fully written JSON plus newline; detect short writes and latch errors. The prefix includes lifecycle/events/enrichment/recovery records, not its own summary. A summary can carry native/enrichment proof; it cannot claim its own delivery or client retention.
- [ ] Add this concrete failure regression in `internal/output/jsonl_test.go`:

```go
type shortWriter struct{}
func (shortWriter) Write(p []byte) (int, error) { return len(p)-1, nil }

func TestShortWriteIsNotSuccessfulDelivery(t *testing.T) {
    s := NewJSONL(shortWriter{})
    if err := s.Write(context.Background(), model.Record{Kind: "event"}); err == nil {
        t.Fatal("short JSONL write admitted as complete")
    }
}
```

- [ ] Make `HotSpotWireReader.read` return an internal immutable prefix/EOF receipt while retaining its existing callback. Update both callers: offline assembler and live `HotSpotCollectorWorkers`; carry EOF receipt through the existing session interpreter to accumulator finalization. Hash bytes before decoding, excluding the recognized final-summary line; validate that the summary is last and EOF is real. Preserve byte/depth/UTF-8 limits and cancellation.
- [ ] Add real stream tests: valid prefix; same records with one altered raw byte; lost newline/truncated JSON; missing/duplicate summary; appended record; failed/short producer write. For altered/truncated controls assert the delivered-byte count/hash or parser oracle actually differs. Neither a clean helper exit nor `Encoder.Encode` alone admits wire.

Add this bounded parser control to `HotSpotEvidenceAssemblerTest.kt`; the fixture is the existing genuine compact projection, not a hand-invented successful capture:

```kotlin
@Test
fun `truncated genuine stream cannot establish final wire delivery`() {
    val original = checkNotNull(javaClass.getResourceAsStream(
        "/hotspot/measured-event-projection.jsonl",
    )).use { it.readBytes() }
    val broken = original.copyOf(original.size / 2)
    assertTrue(broken.size < original.size)
    assertFailsWith<HotSpotProtocolException> {
        HotSpotEvidenceAssembler().read(ByteArrayInputStream(broken))
    }
}
```

### 3d. Client retention and the global predicate

**Files:** Kotlin `HotSpotEvidenceAssembler.kt`, `HotSpotWireMapper.kt`, `HotSpotEvidence.kt`, `HotSpotEvidenceDocument.kt`; add internal `HotSpotCollectionAccounting.kt` for strict extension decoding and the pure conjunction; tests `HotSpotEvidenceAssemblerTest.kt`, `HotSpotStreamingEvidenceTest.kt`; collector `inventory.go`/`inventory_test.go`; `internal/accounting/lanes_test.go`.

**Interface:** `HotSpotCollectionAccounting` is internal and owns finite outcomes `Unknown`, `ReconciledWithLoss`, `ReconciledWithoutLoss`, with source-specific reasons and identities. It consumes the optional native/enrichment proof envelope, reader EOF/prefix receipt and the accumulator's own counts. It returns a checked collection result; the existing public property remains the same type and name. Optional absent proof preserves today's false result. No required public constructor/interface change.

- [ ] Count admitted pairs/recoveries independently of retention, using existing seen-event/seen-terminal validation even for records dropped by retention budgets. Reconcile kept/discarded totals and bytes after EOF. A retention0 control must exercise positive admitted pairs and classified discards, not pass vacuously on an empty stream.
- [ ] Preserve `retentionComplete == no discarded occurrences/recoveries` as a separate predicate. A known bounded retention loss may have a complete denominator but must still expose discarded counts and remain unsuitable for consumers requiring no retained loss. Collection proof never grants policy eligibility.
- [ ] Decode the optional proof envelope strictly, including schema, expected target/session and source identities. Keep raw unknown extension fidelity. Final completeness requires all five lanes reconciled, authoritative source coverage, actual wire EOF, final pairing, no proof overflow/errors and strict native retirement. Evaluate loss-free health separately. Do not equate `len(UnprovenCollectionSources)==0` with proof unless every lane was admitted from its real receipt.
- [ ] In collector inventory admit only helper-owned lanes actually known there; wire/retention remain missing until the client validates delivery/retention. The existing selected Binding may correlate a capture but must not impersonate another lane's native map identity. Validate each source-specific identity before producing any `LaneEvidence` projection.
- [ ] Update the internal hardcoded `collectionLossComplete = false` only to the checked result; update evidence-document writer/reader together. Preserve format-1 legacy false reports. True reports require the optional proof and revalidated local counts; a serialized true boolean without receipts is rejected. Keep physical drain, terminal accounting, retention completeness and policy eligibility separate.
- [ ] Use this concrete existing-model test before changing inventory admission:

```go
func TestEachLaneRequiresItsOwnFinalEvidence(t *testing.T) {
    b := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 1}
    all := []Lane{SelectedEvents, Definitions, Enrichment, Wire, Retention}
    for _, absent := range all {
        lanes := map[Lane]LaneEvidence{}
        for _, lane := range all {
            if lane != absent {
                lanes[lane] = LaneEvidence{Binding: b, Final: true,
                    Counts: Counts{Known: true, Quiesced: true, Joined: true}}
            }
        }
        missing := MissingEvidence(b, lanes)
        if len(missing) != 1 || missing[0] != absent { t.Fatal(absent, missing) }
    }
}
```

- [ ] Add Kotlin regressions around the existing real row fixtures: strip each lane receipt in turn; wrong target/map/source generation; forged true report; retention0 with positive delivery; pending terminal; overflow; valid prefix but missing EOF. Assert false/UNKNOWN or protocol rejection at the proper boundary. Keep old legacy/partial captures conservative.
- [ ] Run focused checks after each sub-deliverable, retaining actual test events. Commands after approval: `make -C profiler/hotspot-agentless unit`; `./gradlew :profiler:test --tests '*HotSpotEvidenceAssemblerTest' --tests '*HotSpotStreamingEvidenceTest'`. Run the patched native-owner packages' host/race suites against the genuine prepared graph as in Task 1. No native execution yet.

## Task 4 — One coherent bounded production evidence campaign and ledger

**Owned artifact:** an independently inspectable final proof bundle: actual source graph, preparation/binary/image identities, authentic raw counters and delivery receipts, triggered fault controls, strict retirement receipts and a lane-by-lane conclusion. This is one coherent campaign, not reuse of consumed scopes or an unbounded retry loop.

**Files:** update only the two contract/evidence documents linked above, issue 042's current checkpoint, `.superpowers/sdd/2026-11-03-hotspot-definition-accounting/progress.md`, affected canonical/mirror manifests and existing qualification/golden inputs. Extend the existing qualification cases/control-plane inputs rather than introducing a new three-fragment extractor or generic broker.

- [ ] After implementation approval, inspect the effective graph and publish a candidate manifest before requesting native authority. Reuse unchanged binaries only if graph, embedded assets, contracts and provenance genuinely permit it. Build affected artifacts once through genuine pinned preparation and upstream compiler-cache reuse. Rebuild Gadget image only when affected C/YAML/BPF/image inputs changed; auxiliary native-counter additions do change native inputs and therefore require the appropriate rebuild. Distinguish declared native fixture layers from genuine preparation markers.
- [ ] If the candidate differs from 7e09, label old 17/17 as accepted baseline, not automatically CURRENT for the new candidate. Rerun all affected ABI/non-ABI qualification cases coherently against that candidate, including the definition readout ordering/concurrency cases touched by a lease change. Do not mix old and new binary receipts into a single current result.
- [ ] Prepare a fresh closed authority root **for each actual case**, using the proven full seven-module control plane, initializer and master-primary gate. Preserve every original role capability, predicate, workchild cleanup/report budget and owner. Seal actual metadata/new inodes; capless recipients get no fake capabilities. Create the private 0600 typed primary-hash-bound master before any derived approval/helper starts. Retain the exact materialized command, inputs and hashes as reviewable artifacts before operator approval.
- [ ] Never execute the old accepted `...qualification-03` target or any consumed/quarantined root. Do not probe, reuse or clean up those scopes, their helpers, capabilities or PIDs. Historical component quarantines remain unresolved and untouched. No retries, custom permission expansion, post-deadline cleanup or permission-to-warning conversion.
- [ ] Execute one bounded positive production capture with real input activity and independently observed expected target/owner identities. Require nonzero selected and definition admissions, every possible-CPU slot including zeros, native equations, consumer/store/enrichment delivery, exact wire prefix/EOF, bounded-client retention and full owned retirement. Do not call a not-exercised auxiliary branch positive coverage.
- [ ] Execute only the predeclared bounded negative cases through the same fresh-root authority path: wrong expected PID/birth/map/generation/CPU; definition drop/rejected store delivery; native/reader overflow; held delivery/deadline; truncated wire; retention0. These are actual controls, not fabricated receipts. For each record (1) intended fault, (2) observed triggering datum, (3) exact rejecting boundary, (4) errno if any, (5) retirement/retained-owner disposition. A control that did not trigger is not a passing negative.
- [ ] Require all intended root/subtest events, no unexpected skips, and accurate command exits. Stop on rejection of authority or unknown retirement; retain the failed scope as directed by the existing control plane, without an automatic rerun. Missing source denominators leave the relevant lane UNKNOWN and issue 042 OPEN, even if the positive selected capture succeeded.
- [ ] After focused tests pass, run `./gradlew build` once for changed executable behavior/build wiring, using the existing approval/configuration for integration tasks; do not add another redundant native campaign. If approval does not cover that gate's native tasks, record it as blocked rather than silently bypassing it. Run `./scripts/adkw check-backlog`, scoped reference/mirror checks and scoped `git diff --check`; record unrelated frozen auxiliary-patch EOF warnings without editing them. Documentation-only revisions need reference/backlog/diff checks, not new native runs.
- [ ] Update the ledger with exact candidate hashes, commands, case IDs, positive denominators, per-source/lane states and retained failures. Do not archive 042 in this task. Require operator assessment of its complete contract, including any remaining auxiliary admission blocker, before any later archival request.

## Conservative completion matrix

| Lane / decision | Current state | Required positive oracle | Mandatory rejecting control |
| --- | --- | --- | --- |
| Selected events | Implemented; selected qualification accepted; aggregate production evidence missing | Original map/CPU identity, all rows, A=S+F, delivered R/T, pre-retire snapshot and strict retired parent | Wrong map/CPU/expected identity, row contradiction, held delivery, overflow |
| Trace-definition subsource | Native accounting implemented; capture drops it; aggregate evidence missing | Separate prepared binding equals actual original config/counter/perf generation, all rows, native/reader equations, store consumption | Definition drop/store reject, wrong config/map/generation, malformed rows, overflow |
| Definitions lane | Unproven beyond trace subsource | Above plus every enabled auxiliary source's own admission/consumption/final-owner receipt | Missing report/native denominator, netlink overflow, unjoined callback, unexercised required source |
| Enrichment | Selected terminal checks exist; whole-source receipt missing | Owned admission/classification plus successful event/terminal writes and zero pending after Finish | Sink fails terminal after logical classification, queue-byte skip delivery fails, held worker |
| Wire | Encoding/parsing exist; end-to-end receipt missing | Independently matching exact pre-summary record/byte/hash receipt, one summary, EOF and valid pairing | Truncation/short write, altered bytes, duplicate summary, trailing record |
| Retention | Bounded local pairing/discards exist; independent final receipt missing | Positive admitted pairs/recoveries reconcile with kept/discarded counts and bytes, all pending resolved | Retention0 with actual discards, forged counts, missing terminal, overflow |
| Global completeness | False; must remain false until conjunction proved | All five lanes from actual receipts, complete source inventory, final delivery and strict retirement; known loss separately reported | Remove one lane receipt or add an unknown active source; global result must revert to UNKNOWN/false |

Completion is not “the selected run lost nothing.” It is a defensible denominator and source coverage for every lane, with no missing/invalid receipts and no authority/retirement uncertainty. The matrix is also the acceptance report outline; every cell needs an artifact or an explicit unresolved blocker.

## Separate follow-ups, not covert core gates

- **Resolved Java names:** genuine method-name/descriptor evidence is stronger than a `HotSpotFrame` marker. It is required only where definition-store/enrichment delivery claims depend on those exact symbols; use the existing genuine Java fixture and definition-swap oracle there. Broader method-name quality is a separately scoped follow-up, not a replacement for lane accounting.
- **Installed kernel-image identity:** uname/version strings and an event marker do not prove the installed image. Preserve its unproved status and plan independent image/build provenance if requested. Do not block core accounting on an unrelated kernel-branch probe unless the issue's explicit contract requires that identity for the actual claimed source path.

## Plan validation and handoff

This document was written from retained, audited whole-file source reads and the accepted qualification receipts. It proposes no native action during planning. Before execution, self-review must confirm: definitions auxiliary coverage is not silently omitted; expected identities originate at preparation; snapshots precede resource close; final proof follows retirement; wire/retention are client-owned; optional proof extensions preserve legacy compatibility; every fault asserts its trigger; changed graphs receive fresh honest qualification labels. Primary-session review is sufficient for this planning handoff; no automatic additional agent or execution workflow is authorized.
