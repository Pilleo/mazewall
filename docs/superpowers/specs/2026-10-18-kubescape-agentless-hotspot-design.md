# Kubescape agentless HotSpot collection — governing design

**Status:** P0 implementation/design scope approved on 2026-10-18 by the user's explicit Subagent-Driven selection after the six-document plan handoff. This documentation checkpoint records that decision; implementation and runtime qualification remain pending.

## Goal and architecture

Collect event-correlated Java stacks for Kubescape using the Elastic-origin
OpenTelemetry eBPF unwinder and external Go symbolization. Application JVMs need
no injected library, attach operation, agent option, modified image or restart.
The initial planned target is the pinned Temurin25 build on Linux-amd64 with
its ordinary tiered compiler/default G1 configuration; that support is not yet qualified.

The first integration runs the existing collector as a **node-agent-owned
companion process**. It keeps its independently pinned Gadget/OTel graph; the
node-agent's older Gadget fork continues running baseline sensors. This avoids a
whole-fork migration, cross-program-type tail calls, and importing the collector's
Go `internal` packages into node-agent. A small shared Go bridge module owns asset
verification and the versioned wire contract used by both Go consumers.

```text
node-agent container/process selection and ownership
  -> independently verified collector + local OCI assets
  -> synchronous openat-handler-entry BPF stack capture
  -> external OTel HotSpot metadata reads / symbolization
  -> bounded event + terminal stream
  -> typed ContainerProfile stacks + separate capture evidence
```

This is four independently deliverable subprojects: A provider/bridge, B storage
schema, C node-agent ownership, D cluster acceptance. A and B can be implemented
independently; C consumes both; D consumes all three. Direct in-process Gadget
embedding is not required for this first end-to-end slice.

## Verified baselines and worktree rules

|Name|Location / revision|Important state|
|---|---|---|
|M|`/home/leanid/Documents/code/java/jseccomp`, `16fab8e6f36f3f1fdb57964003affd30a420219c`|Only existing operator `.codanna/settings.toml` edit; preserve it|
|N|`/home/leanid/Documents/code/java/stack-attribution/node-agent`, `1c4fb954cdd51edb98a2ad4100be392cd806e44e`|Dirty `feat/tier-e-sbob-importer`; six modified and28 untracked operator files|
|S|`/home/leanid/Documents/code/java/stack-attribution/storage`|Not present; operator must provision/authorize a writable checkout before B|
|Storage base|`github.com/kubescape/storage v0.0.303`, commit `d963c7941f5bd493751109324e6eba41f7d2b5af`|Cached module is read-only evidence, never the editing location|
|Node Gadget|`06b0d12baca0e2ba61df1aa5a00dc9e449567a10`|No OTel bridge; remains unchanged in this design|
|Collector graph|Gadget7097db144ccd; profiler replacementef6cf29492c8|Current patch8212ee25dc2fb58f070c235e30eb01666a492286763df318d90576b742372a4c|
|Reference upstream|`open-telemetry/opentelemetry-ebpf-profiler` at36f875fd7d625a80de939a9325e1cde8fd43a9f6|Reference only, not an automatic rebase|

Do not create a worktree, switch these repositories, stage prototype work, or
publish refs without the operator's applicable authorization. Before modifying N's
existing files, obtain a recoverable operator-approved baseline of its dirty work;
new plans are not permission to include that work in our commits. No other worker
may mutate an assigned checkout concurrently. Explicit source snapshots and actual
working-tree hashes, not ancestor VCS stamps, identify builds.

## Approval and dependency boundaries

**P0 implementation/design approval is recorded:** the user's 2026-10-18
Subagent-Driven selection after the six-document handoff approves this scope by
reference. Its approved additions are one shared Go bridge module, an opt-in observation
profile/wire3, two additive storage evidence types, and the companion integration.
Approval derives from that selection, not the earlier allocator work. Actual native
qualification/admission, SDK publication, writable storage provisioning, the dirty
node-agent baseline and cluster operations retain their separate named gates.
No breaking existing API, new enforcement authority, target injection or broad
Gadget upgrade is proposed. Native code, schema3 and dependencies are activated
only after their named gates pass. Failed gates produce a concrete rejected
report; do not invent a lease, weaken schema2, or silently use schema1.

The shared module is `github.com/Pilleo/mazewall/hotspot-bridge`, source directory
`M/hotspot-bridge`, Go1.26, standard library only. Its narrow exported API is
justified by two actual consumers: collector tooling and node-agent. Development
may use an explicitly recorded local `go.work`; no absolute replacement enters
release `go.mod` files. A reviewed module/storage revision must actually exist
before updating a consumer to it. Record actual commit/tag/sum in build receipts;
never invent future hashes. External publication remains operator-controlled.

## Global Constraints

- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Production remains pinned Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`, synchronous `openat_handler_entry` until a separately approved capability gate passes.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Rehash every asset byte and validate the complete local OCI graph; no size/mtime/inode digest cache or trusted-helper self-verification shortcut.
- No dependencies or breaking public APIs; additive native/public interfaces require explicit approval and legacy ABI verification before promotion.
- No swallowed/downgraded EPERM/EACCES. Fallback is explicit operator choice only.
- Raw FFM remains behind NativeEngine/io.mazewall.ffi; confined arenas, immediate errno capture, acquired descriptor ownership and checked close.
- Captured occurrence/session/process generation/BOOTTIME identity is immutable; never substitute a later stack or infer logical identity from host TID alone.
- Physical drain, selected-stream accounting, whole-session collection and policy eligibility remain separate. Policy coverage stays incomplete for selected openat evidence.
- Never install seccomp from a virtual thread or change JVM coordination syscall floors; do not touch GITHUB_TOKEN.
- Native builds remain optional, reproducible and operator-invoked; no startup compiler/download/sudo or registry fallback.
- Preserve user work, scope commits to the relevant backlog item and use the orchestrator-managed review path; no default-branch push.
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.

## A. Provider / bridge contract

### A1. Profiles and wire versions

Existing schema2 `temurin25_xint_serial` stays strict and backward compatible.
Schema1 remains experimental only. New schema3 is explicitly selected with
`--protocol-version=3 --runtime-profile=otel_hotspot25_observed_v1` and owned full
drain/control. It uses the same bounded `kind`-based event/enrichment/recovery/
lifecycle/summary shape and capture ABI2. New ready lifecycle fields are
`validation_mode=upstream_generation_checks`, `cgroup_id` and `mount_namespace`.
Every schema3 row has explicit `policy_eligibility:false`; the shared Record DTO
uses `*bool` so schema2 absence remains distinguishable from schema3 false.
No safepoint lease/check is claimed in that profile. All schema3 stack evidence
remains observational, global collection unknown and policy-ineligible.

The CLI additionally requires `--expected-start-ticks`, `--expected-cgroup-id`
and `--expected-mntns` for schema3. Validate the independently supplied tuple
before attaching and in ready; check event PID/birth/cgroup against it. Always
own an exec barrier/pidfd for owned sessions, independently of strict safepoint
mode. Target loss/exec/native failure cannot become successful completion.

General compiled/GC lifetime is not proved by compile-ID validation. P0 explicitly
accepts a separate **observed**, OTel-style profiling claim; strict attribution and
enforcement gates are unchanged. Before admitting schema3, gate A-OBS must show
bounded safe external reads, retained error causes, no wrong observed routes in
the controlled corpus, and honest unavailable outcomes. If that cannot be shown,
keep schema3 unadmitted and return its exact blocker. Do not lower strict claims.
Pre-admission qualification uses an explicit test-only build tag
`mw_hotspot_qualification` and internal candidate entry point; the shipped CLI
cannot bypass the admission decision. After qualified review, the admitted CLI
is rebuilt and exercised directly before its artifact is handed to node-agent.

### A2. Shared API

The `wire` package owns plain JSON DTOs extracted from
`profiler/hotspot-agentless/internal/model/{event,record,stack,identity}.go` without
OTel/BPF imports: `Record`, `Lifecycle`, `Event`, `Frame`, `Mapping`, `Provider`,
`HotSpotIdentity`, `StackEvidence`, `Summary`, `Stats`, `RecoveryResult`, selected
accounting and native-finality observation types. Runtime-specific conversion,
`StackRef`, normalization and native methods stay in the collector.

```go
type Profile string
const (
    Strict25 Profile = "temurin25_xint_serial"
    Observed25 Profile = "otel_hotspot25_observed_v1"
)
type Target struct {
    ContainerID, ImageID string
    PID uint32
    StartTicks, CgroupID, MountNS uint64
}
type Expected struct {
    Target Target
    Provider Provider
    Profile Profile
    Schema int
}
type Pair struct { Event Event; Evidence StackEvidence }
type Capture struct {
    SessionID string
    Target Target
    Profile Profile
    JVMBuildID, JVMSHA256 string
    Provider Provider
    Pairs []Pair
    Summary Summary
    Discarded uint64
    RawSHA256 string
}
func NewDecoder(expected Expected) *Decoder
func (d *Decoder) Accept(line []byte) error
func (d *Decoder) Ready() (Lifecycle, bool)
func (d *Decoder) Finish(processErr error) (Capture, error)
```

`Decoder` has private state. It enforces schema/profile/provider/session order,
one ready/summary, exact event and terminal pairing, no conflicting/duplicate IDs,
integer overflow checks, all event fields and filename-byte consistency, original
clock/scope, limits and final native flags. `session_started` may have birth0 and
empty build IDs because it declares a request; ready must bind real target/build.
Summary is last; EOF plus successful child Wait is also required. There is no
invented `session_stopped` record. Unknown extension fields are retained as raw
bytes; invalid UTF8, duplicate JSON keys, trailing data and excessive nesting64
are rejected. Typed uint64 decoding preserves values above2^53.

Retain at most4096 pairs/32MiB, track at most32768 occurrences/65536 wire rows;
all discarded pairs still count and reconcile. A failure keeps diagnostic prefix
data but returns no publishable capture. A completed capture can include explicit
unavailable/skipped stacks, selected loss, and unknown global lanes.

The `bundle` package exports `Verify(root, expectedManifestSHA string, profile
wire.Profile) (Verified, error)`. `Verified` has private fields and checked getters
`Collector()`, `Store()`, `Image()`, `Expected(target)`; zero values are invalid.
Move the existing Go full-byte/OCI-graph validation rather than duplicate it.
Existing strict tooling retains its exact accepted-version policy around this
common validator. The new bundle has collector-version3/wire3; capture ABI2 stays.
Expected manifest SHA comes from operator-owned deployment config, never the child.
Assets must be operator-owned/read-only through verify and exec.

### A3. Native qualification exit

Pinned Temurin25 default tiered/G1, no agent options or forced interpreter/GC flags:
400 marked opens on four platform workers/two distinct routes at20ms pacing;
400 captured events/terminals, at least360 useful correctly bound marked stacks,
zero wrong/swapped routes; remaining outcomes explicit. Also run compiled-code
churn/class unload and target exec/exit controls. Those may be unavailable/rejected
but never wrong success. Read denial must retain EPERM/EACCES and fail the session.
Record actual JVM flags/maps, source/compiler inputs before builds, binary hashes
before runs, checked retirement and two identical full bundles. Preserve dense600
failures and existing strict ABI/source controls; do not rerun old matrices unless
new code or a concrete concern invalidates them.

## B. Additive storage contract

Add `StackFrame.Symbol *FrameSymbol` at protobuf field4 and
`IdentifiedCallStack.Evidence *CallStackEvidence` at field3. Existing nil-field
JSON/protobuf behavior stays byte-compatible. No rendered method text is hidden
in FileID, BCI is never Lineno, and missing data remains absent.

`FrameSymbol` fields, in protobuf order1-14: `SchemaVersion uint32`, `Provider
string`, `FunctionName string`, `SourceFile string`, `SourceLine *uint32`,
`SourceColumn *uint32`, `NativeAddress *string`, `BytecodeIndex *uint32`,
`RawPosition string`, `PositionDomain string`, `ScopeLookup string`, `ScopeWalk
string`, `BCIState string`, `QualityDiagnostic string`. Address/raw-position
strings are unsigned decimal, avoiding generic JSON numeric truncation.

`CallStackEvidence` fields, order1-12: `SchemaVersion uint32`, `Provider string`,
`RuntimeProfile string`, `EventScope string`, `IdentityScope string`,
`PolicyEligibility bool`, `GlobalCollectionComplete bool`, `JVMBuildID string`,
`JVMSHA256 string`, `GadgetRevision string`, `ProfilerRevision string`,
`PatchSHA256 string`. Both booleans serialize explicitly false for this provider.

Generate and test deepcopy, OpenAPI, applyconfiguration, protobuf source and
marshaller code. The existing `hack/update-codegen.sh` explicitly does **not**
regenerate protobuf automatically; a green JSON-only test is insufficient.
Aggregation must retain actual frame values and evidence: current storage
reconstructs from FileID/Lineno path strings and discards even FrameType. Typed
symbols alone count as nonempty frames. Conflicting evidence groups must not be
silently coalesced, and output pointers must not alias caller input.
Release/roll out the server schema before enabling the node feature. Older clients
can read legacy portions; no lossless old-client read-modify-write promise is made.

## C. Node-agent integration contract

Feature config is `agentlessHotspot` with only `enabled`, `assetsDir`,
`manifestSHA256`, `evidenceDir`. Disabled defaults launch nothing and do not probe
assets/storage/kernel. Enabled requires application profiling and supported
storage symbol/evidence schema. A failed admission is a typed feature failure
with the original cause, not a fallback to Tier E or a successful missing stack.
Baseline sensors continue independently; new observations are not spliced into
their successful syscall-exit events.

Use the existing container receiver and process-tree APIs. Exactly one active
collector per node, a fixed queue of8 target incarnations, one15s window per JVM
incarnation. Reconciliation polls at1s only while a container's target is pending,
with10s discovery budget. Process identity uses host PID + raw `/proc/stat` ticks,
actual container CgroupID/Mntns and image/container identity, not wall-clock StartTime.
Reject ambiguous multiple-JVM targets in v1 rather than choosing arbitrarily.

Supervisor signature is `Run(ctx context.Context, target wire.Target,
verified bundle.Verified, duration time.Duration) (wire.Capture, error)`.
Use owned `exec.Cmd`/pipes, concurrent bounded stderr drain, readiness10s, control
`stop\n`, stdout consumption through final summary, one Wait, kill/join on abort.
No shell, stdin EOF-as-success, unjoined goroutine or timeout-as-retirement.
Failed/pending physical owners block new collection and remain inspectable/retryable.

Profile publication is one checked manager operation after a valid completed
capture and a fresh container/image check. Keep unavailable counts/evidence even
when no useful stack is published. Raw/typed evidence is atomically published in
`evidenceDir/<container-id>/<session-id>/` with a content-digest manifest; use
regular no-symlink paths, O_EXCL temporary files, fsync/checked close and rename.
Capture/raw retention remains32MiB; no unbounded per-event files or infinite history.

CallID is SHA256 of a canonical struct containing identity scope
`otel-observed-provider-frames/v1`, image/JVM/provider revisions/profile and ordered
observable frame content. Exclude session/event/correlation IDs, host IDs and
absolute mapping placement. Equal observable symbols may coalesce; unknown class
loader identity is not silently upgraded to universal Java-method identity.
Retain full typed/raw evidence separately. Native FileID uses genuine mapping
identity, Lineno only genuine source line, FrameType preserves provider type.

New stack evidence is profiling-only. `CallStackSearchTree.AddCallStack` must
exclude explicit `Evidence.PolicyEligibility=false` observations from enforcement
indexes; nil legacy evidence preserves old behavior. Symbol-aware frame keys and
dedup must distinguish new Java methods while preserving legacy keys byte-for-byte.
No new blocking/allowlisting policy authority is introduced.

## D. End-to-end exit

An explicitly configured node-agent image includes the qualified helper/bundle;
no startup compiler, download, registry fallback or Docker socket is needed.
Deploy only to an operator-approved test cluster/node. The new example uses plain
Java/default tiered/G1, shared gate files only in the **test** workload, and no
`-agentpath`, `-javaagent` or dynamic attach. Capture starts before releasing the
marked workload and stops while the target is still alive.

Verify two containers with overlapping namespace PIDs/two workers, restart to a
new host process incarnation, saved API profile with exact typed symbols/CallIDs,
raw event-to-terminal correspondence, metadata/loss qualifiers and all-owner
retirement. Also prove feature-disabled baseline, unsupported storage, permission
denial, target exit, pressure and hanging-child failures. The final bundle contains
saved `ContainerProfile`, `stack-evidence.json`, original bounded JSONL, manifest,
source/JVM/node/boot identities and failure/cleanup receipts. Do not synthesize
success from importer acknowledgement or a file without querying persisted data.

## References and unchanged work

- [Contribution proposal](../../plans/2026-10-03-hotspot-kubescape-contribution-proposal.md)
- [Existing dependency/storage issue](../../internals/backlog/issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an.md)
- [Allocator evidence](../../plans/2026-10-18-hotspot-correlation-allocator-evidence.md)
- [Remaining-work native requirements](../../plans/2026-10-17-hotspot-remaining-work-spec.md)

The existing Tier E example/importer, current Kotlin schema2 facade, strict
interpreter/Serial admission, historical failed captures and open USER_NOTIF
ENOSYS cleanup investigation retain their original scope. This design does not
claim they have been replaced, repaired or qualified by a plan.
