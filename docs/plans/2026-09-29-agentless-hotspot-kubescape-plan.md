# Agentless HotSpot Event Profiling → Kubescape Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` or `superpowers:subagent-driven-development` to implement this plan task-by-task. Complete each test/review gate before starting its dependent task. This document authorizes no repository writes by itself.

**Goal:** Demonstrate agentless HotSpot stack attribution for one synchronous file-opening event in a standalone MazeWall experiment, then contribute an optional, bounded integration to Kubescape.

**Architecture:** A small Go executable embeds Inspektor Gadget and its compatible OpenTelemetry eBPF profiler dependency. A selected-event BPF gadget captures an operation and an OTel stack reference at the same hook; existing OTel code unwinds and symbolizes the stack. A thin adapter preserves structured frames and quality information, first in JSONL and later in Kubescape.

**Tech stack:** Go; Gadget eBPF C and its build tooling; an unmodified HotSpot JVM; Java test fixtures; Linux eBPF. No new C++ JVM agent. No Elasticsearch service, Kibana, or remote profiling backend.

**Spec:** The inline specification in §1 records the scope agreed in the conversation and explicit proposed implementation choices. Subsequent sections distinguish verified existing mechanisms from code to implement.

**Status:** Source-reviewed on 2026-09-29. No combined collector was built, loaded, or benchmarked while preparing this plan. Source snapshots below are starting points, not a certified compatible release set.

## Global constraints

- Keep existing MazeWall Tier E, its C++ agent, InvocationId protocol, task-storage correctness path, session APIs, and USER_NOTIF implementation unchanged.
- Host the experiment in `experiments/hotspot-agentless/`, with its own Go module. This is a proposed new directory, not an existing module.
- First target: Linux x86_64, one recorded distribution kernel with BTF and the required BPF/probe features, platform threads, and one pinned JDK 25 HotSpot/C2 build.
- Run the collector with elevated host privileges on a dedicated test machine/VM; run Java as an ordinary user. Do not require the application container to be privileged.
- Validate Oracle GraalVM 25 JVM mode separately. Do not infer that it passes from a C2 result.
- First event: entry into the x86_64 `openat` syscall handler, using a compatible kprobe. Preflight the actual symbol and ABI on the selected kernel; fail explicitly if unavailable.
- The initial event means “the openat handler was reached,” not “the open succeeded,” “every filesystem operation,” or “every attempt denied earlier in the syscall path.”
- No virtual-thread execution identity, asynchronous-parent reconstruction, OpenJ9, Native Image, ARM64, universal syscall coverage, enforcement, or policy generation in the first milestone.
- Resolve stacks using the reference captured with the event. Never use timestamp-nearest CPU samples, the latest stack for a TID, or a post-event JVM stack request.
- Keep event occurrence IDs separate from OTel cache/correlation IDs and from any normalized stack-content identifier.
- Unknown or partial evidence remains unknown or partial. Observations are diagnostic context, not a proof of all reachable behavior or a new security boundary.
- Reuse upstream lifecycle, unwinding, caches, and symbolization. Any necessary changes must be small, independently reviewable patches, not copied profiler internals.
- Use pinned dependencies, builder images, and gadget image digests. Preserve component-specific copyright/SPDX notices; do not assume every file has the repository-root license.

## Review focus

1. Reordered, missing, or evicted stack definitions must never attach another stack to an event. Covered by Task 2.
2. A reused PID, restarted collector, or a reused JIT address must not reuse stale attribution. Covered by Tasks 2–4.
3. A successful symbolizer return must not imply a complete inline chain or a trustworthy BCI. Covered by Task 3.
4. Bursts and unresolved stacks must not stall Kubescape’s ordinary detection or grow memory indefinitely. Covered by Tasks 2, 5, and 7.
5. Storage conversion must preserve frame order, Java method names, optional BCI, and quality without abusing existing fields. Covered by Task 7.

---

## 1. Inline project specification

### User-visible result

The first demo runs an ordinary Java process, captures selected file-opening events externally, and emits records that distinguish two Java call paths leading to the same operation. At least one controlled test must prove that an inlined application method appears in the reconstructed stack.

The demo must also show unresolved/partial cases, collection-loss counters, and overhead. A stack printed successfully is not, by itself, a passing correctness test.

### Narrow architecture

```text
Unmodified Java process
  → selected synchronous openat handler-entry hook
      → operation payload + occurrence ID
      → Gadget's OTel capture helper and stack reference
          → existing OTel native + HotSpot unwinding
          → existing OTel process-memory symbolization
              → structured stack lookup / bounded enrichment worker
                  → JSONL demo
                  → later: optional Kubescape adapter
```

The lines describe a logical pipeline, not an instruction to replace Gadget’s BPF tail-call implementation. Reuse its existing helper and loader/map replacements.

### Local contracts to implement

These interfaces are proposed for the experiment, not claimed upstream APIs:

- `Collector.Run(ctx context.Context, cfg Config, sink Sink) error`: owns one collector session, its selected target, and shutdown.
- `StackLookup.Lookup(ctx context.Context, ref StackRef) (StackEvidence, error)`: thin adapter to Gadget’s existing OTel cache/resolver; never recaptures the process stack.
- `Sink.Write(ctx context.Context, record Record) error`: writes an event, enrichment result, or session summary.
- `Normalize(e StackEvidence) NormalizedStack`: preserves leaf-to-root order and field meanings; never fills missing data with invented values.

`Config` supplies a target PID or container/cgroup selector, the locked dependency/build information, an enrichment deadline, pending-count and pending-byte limits, and output location. Proposed prototype defaults: 1 second per-event enrichment deadline measured from the original event clock, 4 enrichment workers, 1,024 pending events, and a 16 MiB pending-data cap. These are tunable prototype limits, not measured production recommendations. They do not cap OTel’s own memory; that is measured separately.

Every selected event gets an `event_id` unique within a random collector-session ID. An eBPF occurrence key can use `(CPU, per-CPU sequence)`; increment before attempting event-buffer submission and report submit failures. Include the host PID/TID, capture timestamp and clock domain, process generation when available, cgroup/container identity, event kind, and argument-read/truncation status.

`StackRef` contains the collector session and OTel correlation/cache ID. A repeated stack reference is valid for several distinct events. It is not an operation ID and must not become Kubescape’s `CallID` automatically.

`StackEvidence` preserves typed frames, provider/build identity, raw errors, and separate dimensions:

- **Attribution:** event-correlated / unavailable.
- **Walk:** returned / partial / failed / unknown. “Returned” does not claim completeness.
- **Scope lookup:** exact-PC / preceding-PC / method-only / unavailable / unknown, where the provider actually exposes it.
- **Collection health:** observed counts, failures, explicitly skipped events, and loss counters at session level.

Java BCI and source line are separate optional values. Native addresses are a third type. Zero is not a substitute for “not available.” Preserve upstream method identifiers as delivered; do not advertise them as collision-free cross-classloader identities.

---

## 2. Source map: what to reuse

### Elastic origin and maintained upstream

- [Elastic’s former `otel-profiling-agent` URL](https://github.com/elastic/otel-profiling-agent) redirects to the maintained repository below.
- [OpenTelemetry eBPF profiler](https://github.com/open-telemetry/opentelemetry-ebpf-profiler) is the implementation to reuse. This is Elastic’s profiling technology, not Elasticsearch server code.

| Existing component | Source | Reuse boundary |
|---|---|---|
| HotSpot kernel-side stack walking | [`hotspot_tracer.ebpf.c`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/main/support/ebpf/hotspot_tracer.ebpf.c) | Reuse interpreted/compiled-frame walking and transitions through the whole profiler integration. Do not extract this file as a standalone unwinder. |
| JVM detection and layout introspection | [`interpreter/hotspot/`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/tree/main/interpreter/hotspot), especially [`hotspot.go`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/main/interpreter/hotspot/hotspot.go) and [`instance.go`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/main/interpreter/hotspot/instance.go) | Reuse VM metadata discovery, process-memory reads, caches, and compilation-cookie checks. |
| Inline expansion and source mapping | [`method.go`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/main/interpreter/hotspot/method.go) | Reuse `hotspotJITInfo.symbolize` and method symbolization. Add observable quality only if needed; do not reimplement the decoder. |
| Native frames surrounding Java | [`native_stack_trace.ebpf.c`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/main/support/ebpf/native_stack_trace.ebpf.c) | Reuse native unwinding so libc/JNI frames do not prevent reaching Java. |
| Process, mapping, and BPF lifecycle | [`processmanager/`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/tree/main/processmanager), [`tracer/`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/tree/main/tracer) | Reuse through Gadget’s profiler startup and lifecycle. Do not implement a second process scanner or JIT metadata service. |
| Structured frames | [`libpf/trace.go`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/main/libpf/trace.go) | Preserve typed frame fields instead of parsing a rendered stack string. |
| Offline unwinder regression harness | [`tools/coredump/README.md`](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/main/tools/coredump/README.md) | Reuse when a decoder bug needs a deterministic reproducer. This is optional until such a bug is found; it does not validate live event correlation. |
| Event-triggered OTel bridge | [Gadget `user_stack_map.h`](https://github.com/inspektor-gadget/inspektor-gadget/blob/main/include/gadget/user_stack_map.h) | Reuse `gadget_get_user_stack_from_kprobe`, matching entry-program maps, and correlation-reference capture. |
| OTel startup and stack resolution | [Gadget `pkg/symbolizer/otel/otel.go`](https://github.com/inspektor-gadget/inspektor-gadget/blob/main/pkg/symbolizer/otel/otel.go) | Reuse the tracer, correlation cache, waiters, and pruning. Extend a structured result boundary, not the underlying engine. |

The Gadget resolver inspected here retains `libpf.Frames` internally but converts its user-facing results into function names. Structured evidence therefore needs a small API/data-path change; it is not already an exported, lossless drop-in interface.

### Dependencies that must travel together

[Upstream Gadget’s `go.mod`](https://github.com/inspektor-gadget/inspektor-gadget/blob/main/go.mod) selects a patched profiler fork. The reviewed replacement is:

```text
go.opentelemetry.io/ebpf-profiler
  => github.com/inspektor-gadget/opentelemetry-ebpf-profiler
     v0.0.202611-0.20260715114040-ef6cf29492c8
```

Its comments identify patches for correlation IDs, the stack-cache map, BPF map-key compatibility, and entry-program accessors. The [fork’s inline decoder at that revision](https://github.com/inspektor-gadget/opentelemetry-ebpf-profiler/blob/ef6cf29492c8/interpreter/hotspot/method.go) also has preceding-PC and method-only fallback paths.

Go dependency `replace` directives are not inherited from dependency modules. The experiment’s root `go.mod`, and later node-agent’s root module, must carry the required replacements explicitly. See the [Go module reference](https://go.dev/ref/mod#go-mod-file-replace).

Observed reference snapshots:

- MazeWall search snapshot: `98da39f7f71b64a14633ab3f19046f6bc2afc0e3`.
- Inspektor Gadget search snapshot: `7097db144ccd8dab15373f27ec98d2c3d22e4588`.
- Node-agent search snapshot: `44c4cb2951d5de2ce09231920d9b21c61329eaca`.
- Node-agent’s [Gadget replacement](https://github.com/kubescape/node-agent/blob/main/go.mod): `github.com/kubescape/inspektor-gadget v0.0.0-20260826074832-06b0d12baca0`.

Record actual build-selected versions with `go list -m -json all`; do not label these snapshots build-tested. Audit the selected profiler fork for relevant fixes that exist only on newer OTel main.

### Existing MazeWall tests to reuse carefully

- [Tier E design](https://github.com/Pilleo/mazewall/blob/master/docs/internals/designs/profiler/tier-e-design.md).
- [`TierEDifferentialSmoke.kt`](https://github.com/Pilleo/mazewall/blob/master/profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEDifferentialSmoke.kt).
- [`UserNotifOracleSmoke.kt`](https://github.com/Pilleo/mazewall/blob/master/profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/UserNotifOracleSmoke.kt).

The current differential smoke launches separate Tier E and USER_NOTIF runs and checks counts, attribution/loss summaries, and speed. It does not compare per-event frame sequences. The USER_NOTIF smoke exposes `StackTraceElement` stacks, not a BCI oracle. Reuse its workload and launch approach, but implement the stronger experiment-specific checks below.

---

## 3. Proposed file layout

All paths below are new unless explicitly identified as existing references.

```text
experiments/hotspot-agentless/
  README.md
  go.mod
  go.sum
  Makefile
  dependencies.lock.json
  cmd/mw-hotspot-demo/main.go
  internal/capture/collector.go
  internal/capture/ig.go
  internal/capture/lookup.go
  internal/model/event.go
  internal/model/stack.go
  internal/quality/classify.go
  internal/output/jsonl.go
  gadgets/openat-stack/program.bpf.c
  gadgets/openat-stack/gadget.yaml
  fixtures/java/InlineOpenFixture.java
  fixtures/java/OracleFixture.kt
  tests/integration/capture_test.go
  tests/integration/correlation_test.go
  tests/integration/inline_test.go
  tests/integration/lifecycle_test.go
  scripts/preflight.sh
  scripts/run-demo.sh
  scripts/run-oracle.sh
  scripts/benchmark.sh
  results/README.md
  patches/README.md
```

Unit tests live beside their Go implementation files. `patches/README.md` records upstream branch/commit IDs and purpose; do not vendor a second profiler tree into MazeWall. The Kotlin oracle fixture is validation-only and runs using MazeWall’s existing test/runtime classpath without changing production code.

Proposed make targets are `unit`, `demo`, `integration`, `oracle`, and `benchmark`. These commands specify deliverables to implement; they are not claimed to exist today.

---

## 4. Task-by-task implementation

### Task 0 — Lock and prove the dependency/runtime combination

**Files:** `go.mod`, `go.sum`, `dependencies.lock.json`, `scripts/preflight.sh`, `Makefile`, `README.md`.

**Consumes:** Source map and scoped runtime requirements.

**Produces:** Reproducible build inputs and a machine-readable preflight result.

- [ ] Write a preflight test that rejects a missing target, wrong architecture, unavailable chosen probe, unreadable required process metadata, and missing privileges. Fail without changing host security settings.
- [ ] Pin Gadget, its required profiler replacement, builder image, JDK image/build, and the initial host-kernel configuration. Preserve required top-level Go replacements; record checksums and `go list -m -json all`.
- [ ] Build using Gadget’s containerized toolchain or pinned development container. Do not require the user to compile HotSpot, a custom kernel, or all dependencies by hand.
- [ ] Run `make unit`; run `scripts/preflight.sh` on the test host. Confirm that refusal cases are explicit and that a valid environment reaches the attach test in Task 1.
- [ ] Commit dependency/preflight changes separately from profiler behavior.

**Gate:** The exact environment and dependency graph can be reproduced. A version number alone is not this gate.

### Task 1 — Produce one genuine event-triggered Java stack

**Files:** `cmd/mw-hotspot-demo/main.go`, `internal/capture/{collector,ig}.go`, `gadgets/openat-stack/{program.bpf.c,gadget.yaml}`, `fixtures/java/InlineOpenFixture.java`, `scripts/run-demo.sh`, `tests/integration/capture_test.go`.

**Consumes:** Locked runtime and Gadget/OTel capture helper.

**Produces:** A selected handler-entry observation carrying an OTel reference and visibly reconstructed Java methods.

- [ ] Write a failing integration test: an ordinary Java process opens a known fixture path, and the observation must contain the selected process identity and an expected application method. No JVM agent arguments are allowed.
- [ ] Implement a minimal `BPF_PROG_TYPE_KPROBE` gadget at the chosen `openat` handler entry. Read arguments using the verified x86_64 syscall-wrapper ABI; do not confuse kernel probe registers with userspace syscall argument registers.
- [ ] Filter non-target processes/cgroups before requesting an unwind. Allocate an occurrence ID, copy a bounded argument snapshot, record read/truncation status, and call the existing Gadget stack helper. Preserve the event even if no OTel reference is returned.
- [ ] Start OTel through Gadget’s existing lifecycle. Do not attach periodic CPU sampling merely to correlate events. Keep whatever process-discovery/map-monitor components the integration needs.
- [ ] Implement a warm-up/readiness phase driven by successful test observations, with an overall timeout; also retain an explicit cold-start test. Do not silently discard discovery-gap statistics.
- [ ] Run `make demo` and `make integration`. Confirm that Java runs without an injected agent and the stack was captured by the event hook, not sampled later.
- [ ] Commit the working minimal demo before adding production-quality adapters.

**Gate:** A live, selected event reaches the existing external Java unwinder. Pretty output is sufficient only for this first gate.

**Hook limitation:** Gadget documents matching OTel entry programs for the kprobe family and `perf_event`, not tracepoint gadgets such as `trace_open`. Do not start by modifying `trace_open` and hoping `--collect-otel-stack` will work. [Reference](https://inspektor-gadget.io/docs/latest/reference/stack-traces/).

### Task 2 — Preserve structured evidence and make correlation safe

**Files:** `internal/model/{event,stack}.go`, `internal/capture/lookup.go`, `internal/output/jsonl.go`, corresponding unit tests, and `tests/integration/correlation_test.go`.

**Upstream patch target:** Gadget `pkg/symbolizer/otel/otel.go` and its symbolizer interface/orchestration files, with focused tests. These are modification targets, not claims that a structured lookup API already exists.

**Consumes:** Events and stack references from Task 1.

**Produces:** `StackLookup.Lookup` and JSONL `Record`s containing typed frames and explicit unresolved results.

- [ ] Write unit tests for event-before-stack, stack-before-event, duplicate/reused stack references, absent reference, delayed definition, expired deadline, cache eviction, output failure, and collector-session restart.
- [ ] Expose a narrow structured lookup/result from Gadget’s existing OTel cache and resolver. Preserve the full returned frame set instead of overlaying interpreted names onto native address slots. Keep native and Java frame domains distinct.
- [ ] Reuse existing waiters and pruning rather than writing a second process/metadata/cache service. On a missing definition, return unavailable; never substitute a same-TID or nearby stack. Verify that the producer has a safe resend/invalidation behavior when a first definition is lost, or explicitly retain the resulting unavailable status.
- [ ] Run lookups in the bounded enrichment workers, using the original event clock/deadline. When limits are reached, write an un-enriched record with the reason and increment counters. Never block the ordinary event-consumption/detection path on the resolver timeout.
- [ ] Add process-generation/session scoping and cleanup on target exit. Do not treat a bare PID or raw JIT address as sufficient identity.
- [ ] Run `go test -race ./...` and the correlation integration tests. Stress simultaneous events from multiple threads; assert zero cross-event/cross-process stack substitutions.
- [ ] Commit adapter and upstream structured-result changes separately.

**Gate:** Reordering and loss affect availability, not attribution correctness. Queue/map sizes remain bounded at the defined layer.

### Task 3 — Expose quality without inventing guarantees

**Files:** `internal/quality/classify.go`, `internal/model/stack.go`, quality and decoder fixtures/tests.

**Possible upstream patch target:** The selected profiler fork’s `interpreter/hotspot/method.go`, its tests, and the minimal typed result metadata needed to propagate quality through Gadget.

**Consumes:** Structured frames and provider errors.

**Produces:** Explicit scope-lookup and walk-quality information, with conservative unknowns.

- [ ] Write fixtures/tests for exact PC matching, preceding-PC matching, absent scope metadata, invalid/out-of-range BCI, unreadable method metadata, compilation-cookie mismatch, and capped/truncated scope walks.
- [ ] Classify only information actually exposed by the provider. Initially use `unknown` for non-observable dimensions. Never infer a complete stack from an empty error or a method name being present.
- [ ] Add a minimal metadata patch to report exact-PC, preceding-PC, and method-only paths when needed to evaluate fidelity. Record BCI availability separately; a fallback to BCI zero must not be presented as a measured BCI zero.
- [ ] Preserve raw errors alongside normalized statuses. Treat target memory/metadata as untrusted input: decoding must be bounded and malformed data must not panic the privileged collector.
- [ ] Run affected upstream decoder tests plus local quality tests. Use OTel’s coredump harness only when a real unwinder failure warrants an offline regression case; use synthetic test processes, not private production dumps.
- [ ] Commit quality changes as a small independent patch suitable for OTel upstream review.

**Gate:** Every reported quality claim is justified by provider evidence. Diagnostic enrichment can continue with unknown quality; strict Tier E equivalence cannot be claimed from it.

### Task 4 — Demonstrate inline recovery and quantify correctness

**Files:** `fixtures/java/{InlineOpenFixture.java,OracleFixture.kt}`, `scripts/run-oracle.sh`, `tests/integration/{inline,lifecycle}_test.go`, result fixtures.

**Consumes:** Structured output, quality reporting, and MazeWall reference workload/launch patterns.

**Produces:** A finite, reproducible correctness report and documented unsupported cases.

- [ ] Write a deterministic fixture with two call paths, `pathA → sharedLeaf → file open` and `pathB → sharedLeaf → file open`. Use unique operation tokens in fixture filenames and a manifest `(run, worker, sequence, branch)` to pair observations with intended operations without timestamp heuristics.
- [ ] Run 1,000 marked operations per path after warm-up in the first single-thread case. Confirm expected application-frame order. Build a diagnostic configuration that verifies selected methods were actually inlined using compiler logs; do not assume source nesting proves inlining.
- [ ] Run the same fixture in a separate USER_NOTIF reference execution. Compare the explicitly agreed application-frame segment and operation classes. Do not claim these are the same physical syscall occurrences across runs.
- [ ] Keep BCI validation separate: the current `StackTraceElement` reference cannot prove BCI equality. Validate known fixture call-site BCIs independently, or add a validation-only reference that actually emits BCI before making that claim.
- [ ] Repeat with interpreter-only execution, default tiered compilation, a controlled C2/inlining configuration, 4 and 16 platform threads, cold process startup, target exit, collector restart, and JIT/class-unloading pressure. Test Oracle GraalVM JVM mode separately after the initial C2 gate.
- [ ] Test a deep Java chain and a deliberately unsupported configuration. They must produce truthful unavailable/partial/unknown evidence rather than an apparently complete successful result.
- [ ] Run `make integration` and `make oracle`. Save JVM flags, compiler evidence, environment manifest, normalized results, mismatches, missing results, and loss counters.
- [ ] Commit the tests and reproducible result recipe, not unverifiable hand-written benchmark numbers.

**Gate:** Zero wrong branch/process attribution in the controlled acceptance corpus; every marked operation is accounted for as observed, unavailable, or lost. Any unexplained loss fails the integrity gate. Separately report resolved-frame coverage; “all accounted for” is not “all stacks resolved.”

Default JVM configuration and no concurrent reference agent must be tested independently, so a reference profiler cannot accidentally improve debug metadata available to the candidate.

### Task 5 — Measure overhead and publish the standalone reproducer

**Files:** `scripts/benchmark.sh`, `results/README.md`, `README.md`, benchmark result schema/tests.

**Consumes:** Passing capture/correlation tests.

**Produces:** Repeatable measurements, including uncertainty/variation, not a performance promise.

- [ ] Write report-validation tests that reject missing counts, invalid denominators, or claiming full coverage when collection loss is unknown/nonzero.
- [ ] Benchmark at least: no collector; event collection without Java unwind; event collection plus OTel unwind/symbolization. Run existing Tier E/USER_NOTIF comparison workloads separately where useful.
- [ ] Measure application throughput and latency, collector CPU/RSS, kernel map/ring-buffer memory where measurable, distinct stack rate, successful enrichment rate, pending queue maxima, losses, and time to readiness.
- [ ] Vary event rate and stack diversity independently. Include repeated identical stacks, many unique inline chains, and burst traffic. Do not substitute CPU-sampling overhead claims from upstream for event-triggered measurements.
- [ ] Repeat runs with the same fixture/environment and report distributions. Tune limits only with recorded before/after results; explicitly report deliberate rate limiting as reduced coverage.
- [ ] Publish a one-command demo using pinned assets. Root privileges apply only to the collector and required test setup, never to the Java workload.
- [ ] Commit the reproducer and report generator.

**Gate:** No wrong attribution or unbounded growth. A useful tested operating envelope is documented; production overhead targets are agreed with Kubescape maintainers rather than invented here.

### Task 6 — Propose and isolate upstream integration changes

**Files:** Demo README, `patches/README.md`, a proposed Kubescape feature issue/design note, focused Gadget/OTel changes from Tasks 2–3.

**Consumes:** Working demo and initial measurement/correctness evidence.

**Produces:** A narrow contribution proposal and independently reviewable upstream patches.

- [ ] Prepare the proposal: “Opt-in HotSpot call-stack enrichment for selected synchronous file-open observations, using Gadget/OTel, without JVM injection or changes to baseline detection.” Include a sample record, tested matrix, discovery/quality limitations, privilege requirements, and overhead results.
- [ ] Ask maintainers to settle the selected event hook, Gadget-fork alignment, event identity, storage representation, and resource limits before building the full node-agent wiring. This is a future implementation task; no issue or PR was opened while writing this plan.
- [ ] Submit generally useful structured-result changes to Gadget and decoder-quality changes to OTel. Track the exact required revisions while review is pending.
- [ ] Produce a dependency-only Kubescape change where feasible. Choose a reviewed backport into Kubescape’s Gadget fork or a compatible fork update; do not bundle unrelated dependency modernization.
- [ ] Re-run the standalone corpus against the dependency set selected for node-agent.

**Gate:** The target event/data contract is explicit, and the demo works with the actual dependency graph intended for Kubescape. Do not assume upstream acceptance.

### Task 7 — Add optional Kubescape enrichment and storage

**Existing integration references:**

- [Node-agent syscall tracer](https://github.com/kubescape/node-agent/blob/main/pkg/containerwatcher/v2/tracers/syscall.go).
- [Container-profile manager interface](https://github.com/kubescape/node-agent/blob/main/pkg/containerprofilemanager/containerprofile_manager_interface.go).
- [`ReportIdentifiedCallStack` implementation](https://github.com/kubescape/node-agent/blob/main/pkg/containerprofilemanager/v1/event_reporting.go).
- [Storage stack types](https://github.com/kubescape/storage/blob/main/pkg/apis/softwarecomposition/v1beta1/types.go).

**Proposed new node-agent files:** `pkg/containerwatcher/v2/tracers/hotspot_stack.go`, `pkg/stackenrichment/hotspot.go`, and matching tests. Existing registration/configuration files are changed following the checked-out repository’s current patterns; do not introduce a parallel watcher framework.

**Consumes:** The selected Gadget/OTel dependency set, structured results, and the agreed storage/event contract.

**Produces:** Opt-in per-container enrichment with unchanged baseline detection.

- [ ] Write integration tests with the feature disabled, enabled, and failed. Disabled means no profiler startup or additional probe. An unavailable stack must not suppress a normal security event.
- [ ] Register the selected companion tracer and reuse node-agent’s target/container lifecycle. It emits its own correlated operation payload plus stack evidence; do not time-join its stacks onto unrelated existing events.
- [ ] Keep `advise_seccomp`/`SyscallTracer` syscall inventory unchanged. That path batches syscall names and is not an event-context source to symbolize after collection.
- [ ] Resolve the schema gap explicitly. At the reviewed snapshot, `StackFrame` has `FileID`, `Lineno`, and `FrameType`; `IdentifiedCallStack` has `CallID` and `CallStack`. This alone does not preserve function names, source information, BCI availability, or quality.
- [ ] Implement an additive, versioned representation for function name, optional source file/line, optional BCI, provider, and evidence quality as agreed in Task 6. Update storage types, schema/generated artifacts, and round-trip tests together. Do not hide method names in `FileID`, use BCI as a source line, or place a transient correlation ID in `CallID`.
- [ ] Convert the complete structured sequence with tested orientation into the existing call-stack tree/profile reporting path. Preserve the raw provider sequence in fixtures so a leaf/root reversal is detectable. Keep two different stacks for the same operation distinct.
- [ ] Reuse `ReportIdentifiedCallStack` and existing profile deduplication/budget mechanisms where their semantics fit. Rate-limit or skip only enrichment under pressure, exposing that fact in counters; never silently alter existing syscall collection.
- [ ] Test two Java containers with overlapping namespace PIDs, target restart, failed metadata reads, repeated and distinct stacks, queue overload, profile size limits, old/new schema round trips, and feature-disabled behavior. The normal detection path must continue while stack lookup is unavailable or slow.
- [ ] Document node-level privileges/host process visibility and version constraints for the tested deployment. Gadget currently documents host-PID visibility for its OTel path; validate the actual node-agent manifest and mounts rather than copying a privileged DaemonSet blindly.
- [ ] Commit schema compatibility, dependency wiring, and optional enrichment as separate reviewable changes where the repositories permit it.

**Gate:** A reviewer can run one Java workload in a small Kubernetes test cluster, inspect method names and quality attached to the correct container’s selected observations, and verify that turning the feature off restores baseline behavior.

---

## 5. Later MazeWall backend decision

Only after the preceding evidence exists, evaluate a public MazeWall backend such as a proposed `HOTSPOT_AGENTLESS` strategy. The name is a placeholder proposal, not an existing enum member.

Define a capability matrix for syscall coverage, inline reconstruction, method identity, logical execution identity, startup gaps, quality/loss reporting, runtime support, and privileges. Integrate through a separate capture capability/adapter only where the current public model can represent the actual evidence. Do not retrofit OTel correlation IDs into Tier E InvocationIds or weaken Tier E to make the new path fit.

Diagnostic stack enrichment can be useful before it satisfies policy-generation requirements. A backend with incomplete evidence must not silently produce a policy described as complete.

## 6. Definition of done for the initial project

The initial project is complete when the standalone reproducer is documented; inline recovery is demonstrated with independent compilation evidence; false attribution, missing evidence, and losses are tested separately; resource usage is measured; and the narrow Kubescape integration or review-ready contribution preserves the evidence without changing ordinary detection.

Universal runtime support, universal syscall capture, formal completeness, virtual-thread identity, and upstream acceptance are not part of this definition.

**Practical order:** First make a real `openat` event produce the expected Java stack. Next make that result structured, correctly correlated, and honest about its quality. Then take the working feature to Kubescape. Do not build a second complete MazeWall profiler before proving these smaller steps.
