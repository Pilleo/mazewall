---
document_type: execution_plan
base_revision: 8be633cd
status: approved_milestones_1_2_3_complete
---

# MazeWall HotSpot Agentless Profiler Integration Plan

> Execute inline, one scoped milestone at a time, with review after each passing
> gate. This is a planning draft, not authorization to implement public API changes.
> Preserve the current worktree and unrelated operator edits. No delegation,
> worktrees, remote publication or pushes are implied.

**Goal:** Make the validated external HotSpot collector an explicitly selected,
packaged MazeWall profiler strategy with Kotlin sessions, structured results and
honest coverage, beginning with the runtime/event scope already proved.

**Architecture:** Retain the Go Gadget/OTel engine as an owned external process.
Add a bounded Kotlin protocol/evidence adapter and pure session lifecycle machine
inside `:profiler`, using existing process-launch traits. Expose an additive
`HOTSPOT_AGENTLESS` strategy through `MazewallProfiler`, with process attachment
first and workload convenience only after that lifecycle is verified.

**Tech Stack:** Existing Kotlin/JDK profiler module, existing Kotlin serialization,
Linux x86-64, pinned Go1.26 Gadget/profiler engine and build assets; no JNI bridge
or replacement unwinder. Existing JDK baseline remains unchanged.

**Spec:** Proposed requirements and interfaces in this document, grounded in
[profiler design](../../internals/designs/profiler/profiler-design.md),
[original agentless specification](../../plans/2026-09-29-agentless-hotspot-kubescape-plan.md)
and [verified auxiliary milestone](../../../experiments/hotspot-agentless/results/auxiliary-worker-retirement.md).
The original Kubescape-first sequencing is superseded for this work: MazeWall
integration proceeds independently; Kubescape remains a separate contract gate.

## Goal and initial scope

The first supported version is a real public backend, with installable assets and
normal Kotlin lifecycle/results. Its initial evidence scope is **process-wide
synchronous openat handler entry on the exact pinned Temurin25 interpreter/Serial
profile**. Other C2/tiered modes have controlled corpus evidence but not the same
general lifetime guarantee; they are not silently admitted to this first profile.

An existing JVM cannot be switched to `-Xint`/SerialGC by attaching a profiler.
Attach must check the actual runtime/build/startup configuration and refuse an
unsupported process. A default running JIT JVM therefore may be refused. A fresh
pinned fixture JVM provides the first end-to-end acceptance. Broad JIT support
is a subsequent capability milestone, not a condition hidden by API integration.

The process-wide observation window is not an InvocationId/task boundary. Background
threads are within the target PID scope. Logical virtual-thread/asynchronous-parent
identity and full syscall/io_uring coverage are not claimed.

## Current API findings

- `ProfileStrategy` is in `profiler/api/ProfilingCoverage.kt`; `AUTO` selects
  USER_NOTIF. Existing EBPF handles recorded logs and refuses live attachment.
- `MazewallProfiler.profile` currently delegates to USER_NOTIF except its existing
  recorded-EBPF branch. `ProfilerService`/`ProfilerSession` are USER_NOTIF-specific.
- `ProfilingResult` owns immutable legacy `StackTraceElement` projections and
  coverage; these cannot represent optional BCI/native position/provider quality
  or unique occurrence terminals losslessly.
- `ProfilingCoverage.complete` derives from internal integrity and gates policy
  compilation. Joining transports must not make selected-event evidence complete.
- `ProcessLauncher.startProcess(args, redirectErrorStream)` already exists. Launch
  collector with `redirectErrorStream=false`: stderr cannot be merged into JSONL.
- `:profiler` already has Kotlin serialization, explicit API and ABI validation.
  Native collector packaging is additional work, not an existing Gradle feature.

## Global constraints

- Keep USER_NOTIF/Tier E, InvocationId, native ACK protocol, task correctness and
  existing public constructor/method descriptors intact. Do not refactor all
  profiler sessions into a new generic framework for this feature.
- Add explicit `ProfileStrategy.HOTSPOT_AGENTLESS`; preserve AUTO's current choice
  and existing EBPF semantics. Additive APIs/enums still require compatibility review.
- Keep the Go unwinder, layouts, caches and native lifecycle checks. Promote one
  canonical source tree; do not vendor another profiler or maintain divergent engines.
- Root Gadget/profiler replacements and all asset digests remain pinned. No new
  JVM library or Gradle plugin dependency is authorized by this draft.
- Collector privileges belong to an explicit operator-configured launch mechanism.
  Do not escalate the JVM, invoke sudo automatically or change host security settings.
  Probe the actual collector, not just the unprivileged parent JVM's eBPF capability.
- Target identity is host PID plus process start generation, with existing exec/
  epoch checks. Never reinterpret an OTel correlation ID as an occurrence/InvocationId.
- Preserve ordered immutable frames, raw diagnostics, optional BCI/source positions,
  unavailable and skipped terminals. No timestamp-nearest or same-TID substitutions.
- Preserve EPERM/EACCES and failed native ownership. Unjoined workers retain leases.
- Initial native bounds stay event256KiB, definition512KiB/CPU, producerLRU256,
  frameCache16384, raw3072 words, four workers, pending1024/16MiB, original-event
  deadline1s, strict session60s, remaining native stop budget2s.
- Kotlin retention adds a separately reported limit: proposed maximum4096 retained
  occurrences and32MiB retained evidence per session. Hitting it marks retention
  incomplete; continue draining/terminal accounting without pretending retained
  results are complete. Validate these proposed limits before publication.
- No native pointer, C integer layout or FFM downcall moves into public API code.
  Reuse platform traits; read platform instructions before extending any trait.
- Never modify/filter/handle `GITHUB_TOKEN`.

## Proposed public surface to review in milestone 1

Names below are proposed new APIs, not current symbols:

```kotlin
val profiler = MazewallProfiler.open(
    ProfileOptions(
        strategy = ProfileStrategy.HOTSPOT_AGENTLESS,
        processWide = true,
        captureStacks = true,
    ),
)
profiler.use {
    it.attach(targetPid, HotSpotOptions(assetsDirectory = assetsDirectory)).use { session ->
        val during = session.snapshot()
        val final = session.finish()
        val typed = final.structuredEvidence
    }
}
```

- Reuse `io.mazewall.platform.lifecycle.Pid` for `targetPid`.
- `MazewallProfiler.attach(Pid, HotSpotOptions): HotSpotProfilingSession` is additive.
  Only explicit HotSpot strategy admits it in the first version.
- `HotSpotOptions` is a separate immutable configuration type; do not add constructor
  parameters to existing `ProfileOptions` data-class JVM descriptors. It contains
  assets/configured collector argv prefix, strict runtime admission, readiness
  timeout and bounded retention settings. No shell command string interpolation.
- `HotSpotProfilingSession.snapshot(): ProfilingResult<Unit>` returns an immutable
  partial snapshot; `finish(): ProfilingResult<Unit>` attempts the finite stop/join
  and preserves failure evidence. `close()` is idempotent and cannot bypass a pending
  finish owner. Failed startup/stop throws a typed exception retaining partial evidence.
- Add a read-only optional `ProfilingResult.structuredEvidence` getter backed by
  an immutable evidence owner/internal factory. Existing public constructor and
  copy signatures remain; copies preserve the evidence owner. Other strategies
  have no HotSpot-specific metadata fabricated onto them.
- `StructuredProfileEvidence` contains provider/target generation, immutable
  occurrence records, typed frames, terminal states, retention health and component
  finalization. Represent uint64 fields without signed truncation. Missing BCI is
  absent, while observed zero remains zero.
- Legacy stack/BoB projections remain diagnostic and partial. Keep raw openat
  arguments in structured records; do not claim a relative/truncated path is a
  resolved Landlock operand or that handler entry was a successful file operation.
- HotSpot coverage always has `complete=false` in this version and existing policy/
  dedicated-worker conversion gates reject it. Physical stop completion is separate
  from semantic coverage completeness and partial native loss.
- Workload `profile { ... }` integration comes in milestone 5. It targets the current
  PID only when that JVM passes strict admission, records process-wide window
  semantics, and returns the block value plus structured evidence. Unsupported
  scope/options fail explicitly before the block runs; no USER_NOTIF fallback.

## File/dependency map

New names and directories are proposed; existing paths are verified:

- Public models/session: `profiler/src/main/kotlin/io/mazewall/profiler/api/StructuredProfileEvidence.kt`,
  `HotSpotOptions.kt`, `HotSpotProfilingSession.kt`.
- Wire parsing/admission/adapter: new focused files under
  `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/`.
- Pure lifecycle state/effects: `internal/hotspot/HotSpotSessionMachine.kt`.
- Owned launcher/process supervision: `internal/hotspot/HotSpotCollectorProcess.kt`,
  using existing `platform/.../process/ProcessLauncher.kt`.
- Canonical Go engine promotion: proposed `profiler/hotspot-agentless/`, carrying
  its module, pin lock, authored overlays and collector entrypoint. Keep oracle/
  stress/measurement evidence in `experiments/hotspot-agentless`; delegate its
  build scripts to the canonical engine instead of copying runtime logic.
- Strategy dispatch/coverage/result ownership: existing
  `api/MazewallProfiler.kt`, `api/ProfilingCoverage.kt`, `api/ProfilingResult.kt`.
- Packaging/API snapshot: `profiler/build.gradle.kts`, `profiler/api/profiler.api`.
- Tests: focused new `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/` classes
  and guarded `profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/` fixtures.

Dependency order: typed evidence -> canonical assets -> wire readiness/lifecycle ->
owned Kotlin adapter -> explicit public strategy/workload facade -> live compatibility
acceptance -> supported distribution/documentation. Each milestone has its own
backlog scope, red/green tests, review and local commit. Advance only on passing gates.

## TDD cases

- Exact occurrence/session/generation pairing; shared correlation references must
  not collapse distinct events. Reordered event/enrichment delivery is handled.
- Immutable/copy-preserved typed frames; leaf/root order and uint64 ranges retained;
  absent BCI is not zero and BCI is not a source line/native address.
- Unsupported schema/target generation/duplicate terminal/malformed/oversized/truncated
  records and EOF without final summary become explicit protocol failures.
- Startup waits for collector-ready, not the early session-start marker or first
  useful Java frame; unsupported runtime/privileges fail before user work runs.
- Parent cancellation during startup, readiness timeout, collector early exit,
  target exit/exec, permission failure and blocked stderr/readout remain bounded.
- One finish owner; duplicate finish/close cannot retire under an active callback.
  Workload exception and cleanup/native failure causes are both preserved.
- Retention caps produce explicit incomplete retention while draining/accounting
  continues. Missing native loss remains unknown, not a default zero.
- AUTO/USER_NOTIF/recorded-EBPF/Tier E behavior and old constructor/copy descriptors
  remain compatible; incomplete HotSpot results never become enforcement policies.

## Milestone 1 — Typed evidence and coverage contract

**Deliverable:** Kotlin can replay genuine collector records into bounded immutable
evidence, with a precise completeness decision. No collector launch/strategy switch yet.

**Files:** Create the proposed structured model and
`internal/hotspot/HotSpotWireReader.kt`, `HotSpotEvidenceAssembler.kt`,
`HotSpotCoverageAdapter.kt`; add `HotSpotWireReaderTest.kt`,
`HotSpotEvidenceAssemblerTest.kt`, `HotSpotCoverageTest.kt` and retained JSONL fixtures.
Limit initial public exposure until the API review accepts its names/descriptors.

**Interfaces:** Reader consumes bounded lines from `InputStream` and produces
typed wire records. Assembler consumes session/event/enrichment/summary records
and produces `StructuredProfileEvidence`; coverage adapter produces an explicitly
incomplete `ProfilingCoverage`. These are proposed internal interfaces.

- [ ] Review/accept the scope and public model choices above; run blast-radius for
  `ProfilingResult`/`ProfilingCoverage` and record degraded evidence if applicable.
- [ ] Extract a compact genuine event/enrichment/summary fixture and preserve its
  source bundle/frame count. Add reordered/shared-reference/permission variants.
- [ ] Write red tests for identity, frame fidelity, retention and policy eligibility.
  Representative contract assertions after the proposed model exists:

```kotlin
assertEquals(listOf("leaf", "caller"), evidence.records.single().frames.map { it.functionName })
assertEquals(null, evidence.records.single().frames.first().bci)
assertFalse(coverage.complete)
assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
```

- [ ] Run `./gradlew :profiler:test --tests '*HotSpotEvidenceAssemblerTest'` and
  `./gradlew :profiler:test --tests '*HotSpotCoverageTest'`; observe intended failures.
- [ ] Implement explicit field/domain parsing and immutable pairing, retaining raw
  diagnostics. Bound line/record/frame allocation before accumulation. Do not apply
  BobCompiler path rules to unresolved raw filename arguments.
- [ ] Verify focused tests and old `ProfilerSessionApiTest`; review API/serialization
  compatibility before committing only this milestone.

**Gate:** Replay cannot invent useful attribution, complete coverage or resolved
policy paths. Operator reviews the typed contract before native process wiring.

## Milestone 2 — Promote the canonical collector and reproducible assets

**Deliverable:** One maintained Go engine can build/install a digest-verified collector
asset bundle; the experiment remains a test/evidence harness for that engine.

**Files:** Proposed `profiler/hotspot-agentless/` module/lock/overlays/entrypoint;
delegate experiment preparer/Make targets; bounded artifact manifest reader and
`HotSpotAssets.kt`; `HotSpotAssetsTest.kt`; explicit Gradle asset build/install tasks.

- [x] Write red asset tests for checksum/version/ABI mismatch, partial installation,
  missing helper and required gadget/BPF data; no runtime compiler/network assumptions.
- [x] Promote source without duplicated runtime trees, preserving root replacements,
  zero-fuzz overlays and native copyright/SPDX notices. Rename the demo entrypoint
  to a collector-facing command; keep fixture/fault variants separate.
- [x] Build executable plus pinned gadget data/OCI artifact through existing pinned
  builders. Prove the chosen local/cache loader against the actual Gadget API;
  embed/export/load the required assets without assuming an unimplemented file loader.
- [x] Package/install only verified owned paths. Use explicit operator assets lookup;
  do not trigger native build, registry pull or privilege escalation at session startup.
- [x] Run existing experiment `make verify`, native finalization gates and a live
  strict capture with the promoted engine. Compare actual selected module/layout
  and artifact fingerprints; original claims do not migrate without evidence.
- [x] Run `./gradlew :profiler:test --tests '*HotSpotAssetsTest'`; review and commit.

**Gate:** Installed assets launch on the tested host and preserve capture correctness;
ordinary JVM-only builds need no Go/Clang/Docker merely to compile `:profiler`.

## Milestone 3 — Production readiness/control protocol and pure session machine

**Deliverable:** A versioned lifecycle handshake distinguishes process start,
native collector readiness, cold stack availability, partial snapshots and terminal stop.

**Files:** Promoted Go command/capture lifecycle; proposed
`internal/hotspot/HotSpotSessionMachine.kt`, `HotSpotProtocol.kt`;
`HotSpotSessionMachineTest.kt`; update wire fixtures/version admission.

- [x] Write red transition-table tests: Created -> Starting -> Ready -> Running ->
  Finishing -> Completed, plus failed startup/abort/retained cleanup. Effects own I/O;
  no state assignment inside interpreters and no silent exhaustive branches.
- [x] Add a collector-ready record only after the actual target generation, strict
  runtime, native attachment and required readers are established. Session-start
  is insufficient. Useful stack readiness remains per-event and may be unavailable.
- [x] Define a locked production protocol version including ready/summary/error
  records and typed per-session clocks/identities; reject stale incompatible helpers.
  Version changes keep old experiment artifacts readable only through explicit adapters.
- [x] Ensure the owned graceful stop mechanism reaches the Go signal/control path
  already used for draining. Verify SIGTERM handling if JVM Process.destroy is used;
  do not assume it sends SIGINT. Never send signals to an invented/stale collector PID.
- [x] Preserve the 60-second session/remaining native stop budget and failed ownership
  behavior. Add startup-failure, parent-death and EOF-without-summary regressions.
- [x] Run state/wire tests and promoted native ownership tests; update the sealed
  outcome allow-list in `ArchitectureTest` when a new closed hierarchy requires it.
- [x] Review the wire/state protocol and commit separately from public dispatch.

**Gate:** Ready and completed cannot be fabricated by a log line, EOF, caller timeout
or an unjoined worker. No JVM native/FFM layout changes are required.

## Milestone 4 — Owned Kotlin collector adapter and attach session

**Deliverable:** Kotlin supervises the real collector against a supported target,
with bounded stdout/stderr consumption, snapshots and one checked finish owner.

**Files:** Proposed `internal/hotspot/HotSpotCollectorProcess.kt`,
`HotSpotBackend.kt`, `HotSpotSessionInterpreter.kt`; `api/HotSpotProfilingSession.kt`;
`HotSpotCollectorProcessTest.kt`, `HotSpotSessionLifecycleTest.kt`.

- [ ] Write red fault-injection tests for missing assets, denied privileges,
  wrong runtime/PID generation, ready timeout, early exit, malformed output,
  blocked stderr, target exit/exec, duplicate finish and held-reader cleanup.
- [ ] Reuse `ProcessLauncher.startProcess(argv, redirectErrorStream=false)` and
  explicit operator-configured launch argv. Do not run sudo or infer collector
  authority from the parent's UID/user namespace. Keep ordinary targets ordinary.
- [ ] Consume machine JSONL and bounded stderr concurrently. Compute deadlines from
  monotonic time, preserve native/workload failures and drain before process/resource
  retirement. Retain a pending owner instead of turning incomplete stop into success.
- [ ] Expose immutable snapshots and finite finish; separately report producer drops,
  unavailable/skipped terminals, transport/auxiliary joins and Kotlin retention limits.
- [ ] Keep target alive through graceful metadata/enrichment completion. Treat observed
  target exit/exec as abort; never recapture a later stack to repair old evidence.
- [ ] Run `./gradlew :profiler:test --tests '*HotSpotSessionLifecycleTest'` and
  `./gradlew :profiler:test --tests '*HotSpotCollectorProcessTest'`; use existing
  process/native traits for mocks, then review and commit.

**Gate:** No leaked/unowned child or pipe can be hidden by close, and helper/native
failure returns a typed failure with retained partial evidence.

## Milestone 5 — Explicit profiler strategy, result compatibility and workload facade

**Deliverable:** The backend is selectable through MazeWall's public profiler API,
with typed evidence retained and policy eligibility unchanged.

**Files:** Modify `api/MazewallProfiler.kt`, `api/ProfilingCoverage.kt`,
`api/ProfilingResult.kt`, API snapshot; add proposed `HotSpotOptions.kt`;
`HotSpotStrategyTest.kt`, `HotSpotResultCompatibilityTest.kt`; existing strategy tests.

- [ ] Write red tests for explicit selection, unchanged AUTO, unsupported scope/
  runtime/options, missing helper, no fallback and block-not-run-before-readiness.
- [ ] Add HOTSPOT_AGENTLESS and an explicit dispatch branch. First-version admission
  requires processWide/captureStacks true and verified target runtime. Parent eBPF
  probe results cannot override an independently privileged collector's preflight.
- [ ] Add attach/session entrypoint and compatible result evidence owner/getter.
  Verify every old constructor/copy descriptor; preserve evidence across copy/snapshot.
  Any lossy legacy frame projection must remain secondary to the full typed sequence.
- [ ] Implement current-PID `profile {}` using the checked attach lifecycle, preserving
  returned block value and both block/finish failure causes. Label process-wide window
  semantics; do not claim lambda-only, invocation or virtual-thread attribution.
- [ ] Set incomplete coverage explicitly for selected-event/unknown-loss evidence,
  even if all observed frames/joins succeed. Keep dedicated-worker/policy conversion
  rejection and document serialization round trips for the new enum/evidence.
- [ ] Run strategy/result/session tests, `:profiler:test`, ABI validation and Detekt;
  review approved additive API diff before committing this milestone.

**Gate:** A public type genuinely launches the collector and returns its typed evidence;
no enum-only implementation, swallowed startup error or complete-policy projection.

## Milestone 6 — Real JVM/kernel acceptance and compatibility

**Deliverable:** The new public strategy passes through Kotlin, packaged collector
and real native hooks, including failure paths and existing backend regression checks.

**Files:** New guarded integration tests/fixtures under
`profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/`; promoted collector
integration gates; retained versioned reports in experiment results.

- [ ] Write a red public-API integration test against a fresh ordinary-user pinned
  interpreter/Serial JVM: two known call paths, unique operation tokens, correct order,
  exact PID generation and explicit unavailable outcomes. Gate using the repository's
  Linux/rootful capability annotations; do not disable tests to pass unsupported hosts.
- [ ] Test real permission refusal, target restart/exec, original-deadline expiry,
  queue/retention pressure, genuine first-definition loss/recovery, unloading and
  native held perf/netlink/event callbacks. All claims carry actual graph/provenance.
- [ ] Exercise the same public wrapper in a supported current JVM for workload-profile
  semantics; a normal unsupported JIT parent must fail clearly before the block.
- [ ] Verify old USER_NOTIF/Tier E/recorded-EBPF and API/document/policy gates. The new
  backend must neither install seccomp nor alter the existing daemon/ACK/session path.
- [ ] Run focused `:profiler:integrationTest --tests '*HotSpot*'`, promoted Go/native
  gates, then the repository merge gate `./gradlew build`. Kernel suites are required
  for actual attachment/lifecycle changes, not for the earlier replay-only tasks.
- [ ] Record rejected cases and overhead with actual measurement windows; compare
  Kotlin wrapper versus standalone on the same fixture without inventing an overhead target.
- [ ] Review acceptance evidence and commit. Keep unproved runtime/coverage gates explicit.

**Gate:** Installed public API can reproduce useful event-correlated Java evidence,
bounded failures and checked retirement without experimental-directory setup.

## Milestone 7 — Supported distribution and operator guidance

**Deliverable:** A discoverable, versioned profiler backend that users can install,
configure and run with accurate capability/coverage documentation.

**Files:** `profiler/build.gradle.kts` explicit distribution tasks, module/public
guidance, canonical profiler design/diagrams and runnable supported example.

- [ ] Publish/document the collector+asset bundle for the tested platform, checksums,
  licenses, JVM library/API version pairing and explicit operator privilege setup.
- [ ] Make ordinary library use/build require no experiment checkout, registry/network
  access or runtime compilation. Keep native asset build optional and reproducible.
- [ ] Document attach/workload examples, strict JVM startup requirements, supported
  events, background-thread scope, cold availability, partial loss and failure behavior.
- [ ] Run the installed-artifact example from a clean fixture directory; verify
  mismatched/tampered assets and unsupported runtime are rejected.
- [ ] Update API snapshot/design knowledge map, run backlog/link/whitespace/lint checks,
  review the release surface and commit only scoped artifacts/docs.

**Gate:** The feature is first-class and installable, with evidence-backed capabilities;
experimental stress/oracle tools remain optional verification tools.

## Execution and review checkpoints

Milestone 1 completed: internal bounded schema-1 evidence replay and incomplete
coverage adapter, 14 focused tests, full profiler unit/static-analysis/warning-ratchet
and merge gate pass. Public model/strategy exposure is deferred to milestone 5;
no native collector launch is added by this step. See
[milestone evidence](../../plans/2026-10-03-mazewall-hotspot-evidence-milestone.md).

Milestone 2 completed: canonical collector source with delegated experiment
builds, pinned reproducible static executable/local OCI graph, internal bounded
Kotlin asset admission and explicit offline install tasks. Native finalization,
three installed strict capture runs and the merge gate pass. See
[asset milestone evidence](../../plans/2026-10-04-mazewall-hotspot-assets-milestone.md).

Milestone 3 completed: explicit production schema-2 start/ready/error protocol,
validated native readiness, parent-pipe control/abort, verified SIGTERM drain and
pure sealed finite single-owner Kotlin lifecycle. Existing replay stays schema 1;
version-2 installed assets and full merge gate pass. See
[protocol milestone evidence](../../plans/2026-10-04-mazewall-hotspot-protocol-milestone.md).

Start with **milestone 1**, after operator review of this proposed scope/API. Do not
implement all phases in one diff. Every runtime/API milestone gets a scoped backlog
item, intended red tests, focused green verification and a reviewable local commit.
Before changing core/public symbols run `./scripts/adkw blast-radius`; read nearest
module instructions. Run `./scripts/adkw check-backlog` before closing each item.
Stage only current work. Preserve native/permission errors and never push directly.

The initial integration is complete at milestone 7 for the declared scope. Later
capability work covers general concurrent C2/tiered reclamation, additional syscall
families, complete native loss, broader runtimes and logical virtual-thread identity.
Kubescape's fork/schema alignment proceeds independently and is not a prerequisite
for this MazeWall-first integration.
