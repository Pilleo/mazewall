# Agentless HotSpot Quality Metadata Implementation Plan

> **For agentic workers:** Use `executing-plans` to implement this plan task-by-task, inline in the existing workspace. Use checkbox steps to track progress. Do not delegate or create worktrees unless the operator changes those constraints.

**Goal:** Preserve observed HotSpot PC-scope selection, BCI availability and scope-walk termination in event-correlated structured output without inventing completeness.

**Architecture:** Extend the pinned profiler's interned frame value with comparable, immutable HotSpot metadata. Populate it in the existing HotSpot symbolization paths, preserve it through Gadget's existing correlation cache, and normalize it into per-frame JSONL quality fields. Retain conservative stack-level summaries and the existing collection-health observations.

**Tech Stack:** Go 1.26; pinned Gadget and OTel profiler fork; ordinary-user Temurin HotSpot JDK 25; Linux x86-64; existing privileged collector container.

**Spec:** Approved conversation design: typed exact-PC/preceding-PC/method-only metadata, separate observed versus fallback BCI zero, and partial scope walks. This specializes Task 3 of [the overall plan](2026-09-29-agentless-hotspot-kubescape-plan.md), especially its quality and untrusted-decoder requirements. The operator explicitly approved extending the public profiler frame representation.

**Execution status (2026-09-30):** Tasks 1–5 implemented locally; focused race,
decoder, live tiered/Xint, recovery, native LRU and pinned-container gates passed.
See `experiments/hotspot-agentless/results/README.md` for run-specific evidence.
The detailed checkboxes below remain the original execution recipe. Source review
corrected the interpreted cookie assumption: `getMethod` ignores that argument;
the separate follow-up issue records the gap. The fuzz recipe is now `make
quality-fuzz`, because Go refuses `-fuzz` for a dependency outside the main module.

## Global constraints

- Work in `/home/leanid/Documents/code/java/jseccomp`, branch `fast-master`; no worktrees. Preserve unrelated user edits. No automatic staging, commits, pushes or upstream submission.
- Keep MazeWall Tier E, C++ agent, InvocationId protocol, task storage, session APIs and USER_NOTIF unchanged.
- Gadget base: `7097db144ccd8dab15373f27ec98d2c3d22e4588`; profiler replacement base: `ef6cf29492c8a980f889052903fa2dd0c0765cc0`. Retain dependency and image pins.
- Author reviewable patch files and `.go.txt` additions. Apply them without fuzz to checksum-verified, ignored `build/deps/` sources; never edit the module cache or vendor a profiler tree.
- Use existing caches, waiters, pruning, process discovery, symbolization and enrichment workers. No metadata side cache, new retry queue, replacement unwinder or deduplication optimization.
- Do not change BPF layouts, attach semantics, LRU capacity, perf buffers, pruning intervals or enrichment budgets in this milestone.
- New `libpf.Frame` fields must be comparable: no slices/maps or mutable pointer payloads. The zero value means unknown, not successful lookup or available BCI.
- Exact-PC matching describes scope-table selection only. It does not prove a complete inline chain, instruction-boundary-valid BCI, correct full stack walk, or Tier E equivalence.
- Preserve native address/source-line/raw-position separation. Final event/definition loss remains unknown; existing health observations stay partial.
- Run focused tests first, then required live checks, then `./gradlew build` once. Register a scoped backlog issue during implementation and keep its review status open until reviewed.

## Source facts to preserve

Paths below refer to the pinned profiler module copied by `make deps`:

| Source | Relevant behavior |
|---|---|
| `libpf/trace.go:71–96` | `Frame` values are interned via `unique.Make`; `Frames` stores immutable handles. |
| `interpreter/hotspot/method.go:31–51` | Method symbolization replaces BCI outside `bytecodeSize` with zero before source-line lookup. |
| `interpreter/hotspot/method.go:87–110` | Existing PC selection chooses the greatest PC delta not exceeding the captured delta; exact match stops the search. Missing scope offset falls back to method name with BCI zero. |
| `interpreter/hotspot/method.go:114–154` | Scope links must decrease; a nonzero out-of-range/nondecreasing link currently ends the loop without expressing partial quality. Scope BCI greater than zero is decremented once. Encoded zero cannot establish observed BCI zero. |
| `interpreter/hotspot/instance.go:878–922` | Interpreter, compiled, stub and vtable paths differ. Compiled-cookie mismatch returns an error; interpreted method lookup ignores the check argument. |
| `support/ebpf/hotspot_tracer.ebpf.c:274–299` | Interpreter capture uses `0xffff` as invalid BCI. No eBPF modification is required to expose that fact. |
| Experiment `internal/capture/lookup.go:39–46` | Returned stacks currently have stack-level scope `unknown`; error frames cause walk `partial`. |

Before source edits, outline the affected source files and inspect symbol callers. Use `./scripts/adkw blast-radius Frame` and `./scripts/adkw blast-radius Normalize`; for external module symbols also inspect callers directly in the verified generated module. An atlas that does not index the external module is not evidence of zero callers.

## File map

All paths are repository-relative unless marked **generated upstream target**.

| File | Responsibility |
|---|---|
| `experiments/hotspot-agentless/patches/profiler-quality.patch` | Changes to generated upstream `libpf/trace.go`, `interpreter/hotspot/method.go`, `interpreter/hotspot/instance.go`. |
| `experiments/hotspot-agentless/patches/profiler-additions/libpf/hotspot_quality.go.txt` | Comparable metadata types and enums. |
| `experiments/hotspot-agentless/patches/profiler-additions/libpf/hotspot_quality_test.go.txt` | Interning/value-isolation regression. |
| `experiments/hotspot-agentless/patches/profiler-additions/interpreter/hotspot/quality.go.txt` | Small helpers for quality annotation and checked scope-table selection; reuse existing selection behavior. |
| `experiments/hotspot-agentless/patches/profiler-additions/interpreter/hotspot/quality_test.go.txt` | Real method/JIT decoder fixtures and malformed-input matrix. |
| `experiments/hotspot-agentless/scripts/prepare-gadget.py` | Apply/hash the new quality patch alongside existing health and structured patches. |
| `experiments/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/structured_test.go.txt` | Prove existing cache/lookup preserve quality and identity. |
| `experiments/hotspot-agentless/internal/model/stack.go`, `stack_test.go` | Normalize per-frame evidence and optional BCI. |
| `experiments/hotspot-agentless/internal/quality/classify.go`, `classify_test.go` | Conservative scope/walk aggregation and raw diagnostics. |
| `experiments/hotspot-agentless/internal/capture/lookup.go` | Use normalization/classification on returned provider frames. |
| `experiments/hotspot-agentless/tests/integration/quality_test.go` | Live metadata delivery and fallback honesty. |
| `experiments/hotspot-agentless/Makefile` | Include relevant upstream decoder/libpf tests in focused verification. |
| Experiment `README.md`, `patches/README.md`, `results/README.md` | Contracts, reproducible commands, actual evidence and limitations. |

## Task 1 — Define immutable provider evidence and reproducible patch plumbing

**Consumes:** Existing `libpf.Frame`, `libpf.Frames.Append`, verified dependency preparer.

**Produces:** `libpf.Frame.HotSpotQuality` and the following value-only contract. Names in later tasks use this contract.

```go
type HotSpotScopeLookup uint8
const (
    HotSpotScopeUnknown HotSpotScopeLookup = iota
    HotSpotScopeInterpreter
    HotSpotScopeExactPC
    HotSpotScopePrecedingPC
    HotSpotScopeMethodOnly
    HotSpotScopeStub
)
type HotSpotBCIState uint8
const (
    HotSpotBCIUnknown HotSpotBCIState = iota
    HotSpotBCIObservedInRange
    HotSpotBCIUnavailable
    HotSpotBCIOutOfRange
)
type HotSpotScopeWalk uint8
const (
    HotSpotScopeWalkUnknown HotSpotScopeWalk = iota
    HotSpotScopeWalkReturned
    HotSpotScopeWalkPartial
)
type HotSpotQuality struct {
    ScopeLookup HotSpotScopeLookup
    BCIState HotSpotBCIState
    ScopeWalk HotSpotScopeWalk
    RawBCI uint32
    Diagnostic String // actual decoder error or fixed fallback reason; zero if absent
}
// Add to Frame: HotSpotQuality HotSpotQuality
```

- [ ] Scaffold the implementation issue with `./scripts/adkw new-issue --title "Expose observed HotSpot scope and BCI quality in agentless frames"`; declare the file map above, add the new plan and issue/index files to scope, and register it in the backlog index.
- [ ] Add a test proving the metadata participates in existing interning and survives mutation of the caller's original value:

```go
func TestHotSpotQualitySurvivesInterning(t *testing.T) {
    f := Frame{Type: HotSpotFrame, HotSpotQuality: HotSpotQuality{
        ScopeLookup: HotSpotScopeExactPC, BCIState: HotSpotBCIObservedInRange}}
    var frames Frames
    frames.Append(&f)
    f.HotSpotQuality.ScopeLookup = HotSpotScopeMethodOnly
    frames.Append(&f)
    if frames[0] == frames[1] || frames[0].Value().HotSpotQuality.ScopeLookup != HotSpotScopeExactPC {
        t.Fatal("quality conflated or mutated after interning")
    }
}
```

- [ ] Extend the preparer to include `profiler-quality.patch` in its hash and no-fuzz application. During the red cycle, an empty patch is unnecessary: install test additions against the current prepared provider and confirm missing-field/type failure; then add patch application with the implementation.
- [ ] Run red: `go test -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/libpf -run TestHotSpotQualitySurvivesInterning -count=1` from the experiment directory after preparing the test additions. Expect missing quality field/types, not an environment error.
- [ ] Implement the value-only types and frame field; preserve existing frame contents and copyright/SPDX notices. Audit unkeyed literals, equality/hash consumers, rendered output and exporter field selection across the selected module graph.
- [ ] Run `make deps` and the same test with `-race`. Build the whole experiment to catch downstream compatibility problems.

**Gate:** Distinct quality stays distinct in interned values; zero metadata remains unknown; unchanged rendered/exported fields still compile. Record that adding a public struct field can affect external unkeyed literals and memory footprint; do not claim universal source compatibility or unchanged overhead.

## Task 2 — Annotate real method and JIT scope-decoder paths

**Consumes:** `libpf.HotSpotQuality`, captured interpreter/JIT positions and existing HotSpot method caches.

**Produces:** Quality attached to existing HotSpot frames, preserving frame order and the existing PC-selection algorithm. Update the private method call contract consistently:

```go
func (m *hotspotMethod) symbolize(bci uint32, ii *hotspotInstance,
    frames *libpf.Frames, quality libpf.HotSpotQuality)

func selectScopePC(scopes []byte, stride, pcOffset, scopeOffset uint,
    ripDelta int32) (uint32, libpf.HotSpotScopeLookup, error)
```

- [ ] Build fixtures using the existing `hotspotData.GetOrInit` and `Attach` pattern in upstream `instance_test.go`, plus an in-memory `remotememory.RemoteMemory`. Set explicit `PcDesc` field layout and real encoded scope bytes. Preload the instance's existing method cache for valid method pointers; use failing remote reads for unreadable metadata. Do not mock the selection result being tested.
- [ ] Add table tests with these exact expectations:

| Input | Scope | BCI | Scope walk/error |
|---|---|---|---|
| PC delta equals matching descriptor, encoded BCI 1, method size > 0 | exact-PC | observed in-range 0 | returned scope chain |
| Captured PC between two descriptors | preceding-PC | decoded BCI, if in range | returned scope chain |
| No eligible descriptor or selected offset 0 | method-only | unavailable, even though legacy position is 0 | scope walk unknown |
| Empty scope metadata | method-only | unavailable | unknown |
| Interpreter BCI 0, nonempty bytecode | interpreter | observed in-range 0 | scope walk not applicable/unknown |
| Interpreter BCI `0xffff` or BCI >= method size | interpreter | out-of-range; preserve raw input | legacy line-lookup fallback 0 retained |
| Encoded scope BCI 0 | selected PC mode | unavailable | never expose as measured BCI 0 |
| Empty bytecode method | any method path | no in-range BCI | no fabricated zero |
| Nonzero scope link >= scope-data length, self-link or increasing link | selected PC mode | retain already emitted evidence | partial with diagnostic |
| Truncated unsigned5 scope triplet | selected PC mode if known | no fabricated BCI | actual decoder error retained |
| Method index outside metadata, zero method pointer, unreadable method | selected PC mode if known | unavailable for failed frame | error retained |
| Compiled-cookie mismatch | no successful frame claimed | unavailable | existing failure preserved; interpreted identity checking is a separate gap |
| Stub/vtable frame | stub | unavailable | unknown scope walk |

- [ ] Run the matrix red: `go test -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/interpreter/hotspot -run 'TestHotSpotQuality|TestHotSpotScope' -count=1`.
- [ ] Factor the current PC loop into `selectScopePC`, preserving signed deltas, greatest preceding PC and first exact-match exit. Reject zero stride, field offsets extending past a descriptor and incomplete descriptors before reading. Use subtraction-based bounds checks (`offset <= stride && stride-offset >= 4`) so offset arithmetic cannot overflow. Validate method indexes with `methodIdx <= uint32(len(metadata)/8)` before multiplying/indexing; reject null pointers. These checks must return diagnostics rather than silently consuming zero-filled out-of-bounds reads.
- [ ] Pass interpreter/method-only/selected-scope/stub provenance at the existing call sites. Set `RawBCI` before legacy range normalization. Only `HotSpotBCIObservedInRange` permits a public BCI; compiled positive encoded BCI retains the pinned provider's existing decrement and documents its provenance.
- [ ] Annotate appended interned frames by constructing/reinterning value copies. Finalize scope-walk metadata after checking termination: `scopeOff == 0` is returned; a nonzero rejected link is partial. No arbitrary new walk-count cap is needed: checked byte bounds plus strictly decreasing offsets bound this existing walk.
- [ ] Preserve actual errors. Inspect the downstream symbolization failure path before choosing annotation placement: already emitted partial frames must carry diagnostics if retained; if the provider discards the definition, leave the event unavailable and preserve existing error delivery/logging. Do not claim per-event decoder-error export where that transport does not exist. Record this limitation in evidence.
- [ ] Run the full upstream HotSpot package with `-race`, including existing unsigned5/recording-reader tests. Add a fuzz seed test for `selectScopePC` malformed inputs and run `go test -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/interpreter/hotspot -run '^$' -fuzz FuzzHotSpotScopePC -fuzztime=10s`.

**Gate:** All quality comes from executed provider branches; malformed/capped termination cannot look complete. Range-checked BCI does not claim opcode-boundary validation. Decoder failures do not bypass cookie checks or become successful lookups.

## Task 3 — Prove Gadget preserves quality through the existing cache

**Consumes:** Provider frames containing `HotSpotQuality`; existing `storeTrace`/`ResolveStructured`.

**Produces:** The same immutable quality on event-correlated returned frames without a new cache or resolver API.

- [ ] Extend `structured_test.go.txt` with `TestStructuredLookupPreservesHotSpotQuality`: store native + exact-PC + method-only frames; resolve using matching PID/TID/reference; compare every quality field and order.
- [ ] Repeat lookup for a reused reference, replace the result slice locally, and verify cached handles/metadata remain unchanged. Wrong PID/TID must still fail. Retain existing cancellation, pruning and recovery tests.
- [ ] Run `make upstream-test`. A passing result is expected if the existing value-handle transport already preserves the field; do not manufacture a new production implementation merely to force a red test.
- [ ] If the transport strips quality, reproduce that specific failure and fix only the relevant value conversion. Include the new provider test packages in the Make target:

```make
$(GO) test -race -modfile build/experiment.go.mod \
    go.opentelemetry.io/ebpf-profiler/libpf \
    go.opentelemetry.io/ebpf-profiler/interpreter/hotspot
```

**Gate:** Cache identity, cancellation and recovery semantics remain verified; quality survives ordinary typed delivery.

## Task 4 — Normalize per-frame evidence and conservative stack summaries

**Consumes:** Provider quality from Tasks 1–3 and existing `model.Normalize` output.

**Produces:** Per-frame JSON fields `scope_lookup`, `scope_walk`, `bci_state`, optional `raw_bci`, optional `quality_diagnostic`; existing optional `bci` becomes available only with explicit provider evidence. Stack-level scope uses `mixed` for differing known frame modes and `unknown` if any relevant Java frame has unknown quality.

Wire labels are fixed: scope `unknown`, `interpreter`, `exact_pc`, `preceding_pc`,
`method_only`, `stub`; walk `unknown`, `returned`, `partial`; BCI state `unknown`,
`observed_in_range`, `unavailable`, `out_of_range`. Stack scope additionally accepts
`mixed`. Hyphenated phrases elsewhere in this plan are prose, not wire labels.
`raw_bci` is the captured interpreter index or provider-decoded scope index after
the existing positive encoded-value decrement, before method-range fallback.

```go
// Add to model.Frame; native frames omit these fields.
ScopeLookup string  `json:"scope_lookup,omitempty"`
ScopeWalk string    `json:"scope_walk,omitempty"`
BCIState string     `json:"bci_state,omitempty"`
RawBCI *uint32      `json:"raw_bci,omitempty"`
QualityDiagnostic string `json:"quality_diagnostic,omitempty"`

// internal/quality/classify.go
type Classification struct { ScopeLookup, Walk string; RawErrors []string }
func Classify(frames []model.Frame) Classification
```

- [ ] Add `TestNormalizeExplicitBCIAvailability`: same raw legacy position 0 with observed-in-range metadata versus method-only/unavailable metadata. Only the former serializes `"bci":0`; unavailable/out-of-range never serializes BCI. Keep the existing zero-metadata normalization test unchanged in meaning.
- [ ] Test native and kernel frames ignore accidental HotSpot metadata; retain source line/address domains, frame order and original raw position. `raw_bci` is present only when the provider reports a captured/decoded input, including out-of-range input, not for method-only fallback.
- [ ] Run local model tests red before implementing enum-to-string normalization. Unknown enum values degrade to unknown, never success or available BCI.
- [ ] Add classifier tests: all exact known Java frames → exact-PC; exact + preceding → mixed; any unknown Java quality → unknown; method-only → method-only; no Java frames → unknown. Native frames do not force Java scope unknown. Stub/interpreter are distinct known modes, not compiled exact matches.
- [ ] Any error frame or partial scope walk makes stack walk partial. Other returned provider stacks stay `returned`, never `complete`. Gather actual retained quality diagnostics into `RawErrors`; preserve existing unavailable errors.
- [ ] Run classifier tests red, implement the pure classifier, then call it from `gadgetLookup.Lookup`. Avoid an import cycle: model does not import quality; capture imports both.
- [ ] Run `make unit` and `make upstream-test`. Update JSONL examples and quality wording in the same change. Retain schema version 1 for additive fields; consumers must tolerate optional fields/new scope labels. Do not modify delivered-event terminal accounting or collection-health completeness.

**Gate:** Available zero, fallback zero and invalid raw input are distinguishable end-to-end. A successful lookup never upgrades unknown walk/inline fidelity.

## Task 5 — Verify live delivery, recovery and publish the bounded result

**Consumes:** Final quality patch bundle, existing ordinary-user fixture and pinned runtime.

**Produces:** Reproducible live evidence with actual metadata distributions, plus a reviewed patch candidate.

- [ ] Add integration gate `TestHotSpotQualityMetadataIsDelivered` to the existing environment-driven JSONL harness. Require at least one genuine fixture application frame with provider quality; check each optional BCI obeys `bci_state=observed_in_range`; reject method-only/stub frames carrying BCI. Reject stack-level `complete` claims and missing partial collection-health semantics.
- [ ] Run the gate red against a retained pre-quality capture to prove it rejects missing metadata. Missing artifacts must fail, not skip. Then run the new binary with `MW_CAPTURE_GATE=TestHotSpotQualityMetadataIsDelivered` via the existing demo harness.
- [ ] Run default tiered and interpreter-only captures separately. Interpreter-only application frames must show interpreter provenance; default JIT observations may be exact, preceding or method-only. Do not require all modes to occur by chance: the decoder fixtures establish rare fallback cases.
- [ ] Run these focused gates sequentially from the experiment directory:

```bash
make verify
make kernel-cache-test DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
DOCKER_HOST=unix:///var/run/docker.sock COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make demo
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  MW_JAVA_XINT=true MW_CAPTURE_GATE=TestHotSpotQualityMetadataIsDelivered \
  bash scripts/run-demo.sh
DOCKER_HOST=unix:///var/run/docker.sock COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make recovery
make container-build DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

- [ ] Check the final container-built binary with the live quality gate if it replaces the host-built output. Record exact bundle hashes, module bases, JVM flags, artifact paths, per-mode counts, available/unavailable BCI counts, errors and terminal accounting. Retain unresolved cases.
- [ ] Update `README.md`, `patches/README.md`, `results/README.md` and the scoped issue. State exact decoder-error propagation limits discovered in Task 2. Keep the issue open for review, and do not present the quality gate as an inline-fidelity or semantic BCI oracle.
- [ ] From the repository root run `./scripts/adkw check-backlog`, then `./gradlew build` once after focused tests pass. Inspect the task diff against its declared scope; report unrelated work separately without modifying it.

**Gate:** Fresh live quality evidence, existing recovery/LRU checks and merge gate pass. Approval of the frame extension is not approval to submit or merge upstream.

## Subsequent project steps, after this quality gate

These are the next independently scoped milestones from the overall plan; write their detailed execution plans against the completed quality API before implementing them.

1. **Deterministic inline correctness corpus:** unique `(run, worker, sequence, branch)` operation tokens and manifests; 1,000 operations per path after explicit warm-up; compiler logs proving selected application methods were actually inlined. Validate frame order/branch attribution, and validate call-site BCIs independently of the Java stack-trace reference.
2. **Runtime/lifecycle and loss matrix:** interpreter, tiered, controlled C2; 4/16 platform threads; cold startup, exit, restart and JIT/class-unloading pressure; deep/unsupported cases. Add real producer-submit and transport-loss injection; report collection/drain finality accurately. Run GraalVM JVM mode separately.
3. **Overhead envelope:** no collector / event-only / event+unwind modes; vary rate and stack diversity independently; measure throughput/latency, collector CPU/RSS, actual native buffers/maps, coverage, readiness and loss. Compare repeated runs; tune only with before/after evidence.
4. **Optional Kubescape integration:** scoped upstream proposal, explicit opt-in flags, bounded asynchronous enrichment, typed frame storage and lifecycle ownership. Preserve ordinary detection when enrichment is unavailable. Do not make enforcement or Tier E equivalence claims.

## Plan self-review and handoff

- Contract is value-only and includes zero/unknown states; enum names match Tasks 1–4.
- Interpreter sentinel, ambiguous encoded BCI zero, method-only fallback and nondecreasing/out-of-range scope links have explicit tests.
- Cookie/read failures, malformed input, interning isolation, Gadget preservation, local normalization, live propagation and recovery each have a gate.
- No new cache, BPF behavior, buffer setting or global exporter is introduced.
- Full inline fidelity, semantic BCI correctness, complete loss accounting, memory overhead and Kubescape are not claimed by this milestone.
- Execute inline on the approved branch. No additional approval is needed to start this approved extension; stop only if implementing it requires a broader API/protocol or unwinder redesign than specified here.
