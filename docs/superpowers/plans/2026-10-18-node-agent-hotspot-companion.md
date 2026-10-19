---
document_type: execution_plan
base_revision: 1c4fb954cdd51edb98a2ad4100be392cd806e44e
status: c1_c2_c3_c4_c5_local_host_wiring_verified_pending_released_pins_image_native
---

# Node-Agent HotSpot Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Do not mutate the existing dirty Tier E prototype until its operator-approved baseline is recorded. No Gadget fork migration belongs to this subproject.

**Goal:** Add default-disabled agentless HotSpot collection owned by node-agent, with exact container/process binding and typed observational profile publication.

**Architecture:** Reuse the A bridge verifier/decoder and separately packaged collector process, not Go internal imports or target injection. A bounded controller selects one JVM incarnation, supervises pipes/control/retirement, and publishes only a validated completed capture through a checked manager operation. Baseline sensors stay independent.

**Tech Stack:** Node-agent Go1.27, standard exec/JSON/filesystem, existing container/process caches, bridge A and storage B. Current Gadget06b0d12baca0 stays pinned.

**Spec:** [Governing design, C](../specs/2026-10-18-kubescape-agentless-hotspot-design.md#c-node-agent-integration-contract).

**Execution checkpoint (2026-10-19):** The operator explicitly selected the current
dirty Tier E prototype at1c4fb954 as baseline, with recoverable custody and exclusion
of all pre-existing work from commits. [C1 local evidence](../../plans/2026-10-19-hotspot-companion-local-evidence.md)
records commit94bce485, exact-index race checks and byte-preservation proof for all
34original files. Development uses a recorded Go1.27.0 workspace with reviewed
local A/B source. Runtime wiring and published dependency/release handoffs are not
claimed. Do not discard or reimplement the preserved Tier E prototype.

**Later local execution:** C2bf44378e and C3 prerequisite/streaming/controller
commits e7c9ab51,15b9db85,3aa32df6 pass independently exported staged-tree race
checks without the prototype. [Executed companion evidence](../../plans/2026-10-19-hotspot-companion-local-evidence.md)
records ownership, pending cleanup, atomic evidence, restart claims and exact limits.
The newly approved disposable VM has an initial PID namespace and verified BPF/BTF/
cgroupv2 kernel support; guest-only K3s is Ready with CA verification. Existing
MicroK8s remains untouched. This is environment preflight, not final native D
acceptance or SDK publication. C4e1d0c368 now adds checked profile publication and
policy-index exclusion; its exact exported tree passes connected race checks with
zero skips. C5 commit72034696 adds local startup/disabled-baseline wiring, explicit
admission/error ownership and terminal status, with independently checked source
and binary receipts. Ordinary image/actual released pins and final native D remain
open; host-side checks do not establish a running native companion.

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

## Preflight and file responsibilities

Use N from the specification. Go must actually be1.27; the current mazewall host
Go1.26 is not sufficient. A local recorded workspace may select reviewed A/B source
for development; CI/release must resolve real approved module versions and sums.
The plan does not supply a fabricated storage version/hash.

|Path relative to N|Responsibility|
|---|---|
|`pkg/config/{config.go,config_test.go}`, `configuration/config.json`|Default-disabled four-field feature config|
|`pkg/stackattribution/hotspot/{types,project,project_test}.go`|Typed projection/content IDs, explicit observation metadata|
|`pkg/stackattribution/hotspot/{supervisor,supervisor_test}.go`, `testdata/child/main.go`|Owned child/pipes/control/Wait and hermetic process controls|
|`pkg/stackattribution/hotspot/{targets,targets_test}.go`|Host PID/birth/cgroup/mount/container binding using existing process tree|
|`pkg/stackattribution/hotspot/{controller,controller_test,evidence,evidence_test,capability,capability_test}.go`|One active job, queue8, bounded artifacts and storage admission|
|`pkg/containerprofilemanager/v1/{hotspot_capture,hotspot_capture_test}.go`|Atomic checked publication; no recursive withContainer calls|
|`pkg/containerprofilemanager/v1/helpers.go`, new `hotspot_hash_test.go`|Symbol-aware hash extension with exact legacy behavior|
|`pkg/objectcache/callstackcache/{callstackcache.go,callstackcache_test.go}`|Do not place profiling-only observations in enforcement indexes|
|`cmd/{main.go,hotspot.go,hotspot_test.go}`|Feature construction, container receiver registration, startup/stop ownership|
|`go.mod`, `go.sum`|Only reviewed bridge/storage revisions; inspect indirect changes|

Do not edit `tiere` source, its example, baseline open tracer/program, generated
Gadget assets or current root replacements as a shortcut. Reuse existing profile
save/dedup paths where their semantics are correct.

### Task 1: Add config and pure typed projection

**Interfaces:**

```go
// pkg/config
type AgentlessHotspotConfig struct {
    Enabled bool `mapstructure:"enabled"`
    AssetsDir string `mapstructure:"assetsDir"`
    ManifestSHA256 string `mapstructure:"manifestSHA256"`
    EvidenceDir string `mapstructure:"evidenceDir"`
}
// Config adds AgentlessHotspot AgentlessHotspotConfig `mapstructure:"agentlessHotspot"`.
func ValidateAgentlessHotspot(c Config) error
// pkg/stackattribution/hotspot
func ProjectCapture(c wire.Capture) ([]*v1beta1.IdentifiedCallStack, error)
```

- [ ] **Step1: Add config defaults/invalid-enabled tests** in the existing config
  fixture pattern. Disabled ignores unused paths and performs no IO. Enabled
  requires application profiling, absolute directories and a64-lowercase-hex
  trusted manifest digest. Add pure projection regression:

```go
func TestProjectionDoesNotUseCorrelationAsCallID(t *testing.T) {
    p := wire.Provider{Name: "otel-ebpf-profiler-via-gadget"}
    c := wire.Capture{SessionID: "session-a", Profile: wire.Observed25, Provider: p,
        Target: wire.Target{ContainerID: "container-a", ImageID: "image-a"},
        Pairs: []wire.Pair{{Event: wire.Event{CorrelationID: 1}, Evidence: wire.StackEvidence{
            Attribution: "event_correlated", Walk: "returned",
            Frames: []wire.Frame{{ProviderType: 6, FrameType: "jvm", FunctionName: "void Fixture.routeA()"}},
        }}},
    }
    a, err := ProjectCapture(c)
    if err != nil { t.Fatal(err) }
    c.SessionID = "session-b"
    c.Pairs[0].Event.CorrelationID = 9007199254740993
    b, err := ProjectCapture(c)
    if err != nil { t.Fatal(err) }
    if len(a) != 1 || len(b) != 1 || a[0].CallID != b[0].CallID { t.Fatal("occurrence ID leaked into content identity") }
    f := a[0].CallStack.Root.Children[0].Frame
    if f.Symbol == nil || f.Symbol.FunctionName != "void Fixture.routeA()" || f.FileID != "" || f.Lineno != "" {
        t.Fatal("fabricated legacy file/line or lost Java symbol", f)
    }
    if a[0].Evidence == nil || a[0].Evidence.PolicyEligibility || a[0].Evidence.GlobalCollectionComplete {
        t.Fatal("observation promoted to policy/global proof")
    }
}
```

- [ ] **Step2: Run RED.**

```bash
cd "$N"
go test ./pkg/config ./pkg/stackattribution/hotspot -run 'TestAgentlessHotspot|TestProjection' -count=1
```

- [ ] **Step3: Implement config, DTO projection and ownership.** Set
  `agentlessHotspot.enabled=false` in config defaults. Do not access unused dirs
  when disabled. Use A's CanonicalStackID and scope; preserve true source lines,
  BCI/quality/numeric type, genuine Mapping.FileID, decimal positions and nil
  locations. Copy pointed values; do not parse FunctionName into guessed Java
  class-loader/descriptor fields. Convert leaf-first provider sequence to the
  existing caller-rooted tree with explicit dummy root, preserving every frame.

```go
func tree(frames []v1beta1.StackFrame) v1beta1.CallStack {
    var children []v1beta1.CallStackNode
    for _, f := range frames {
        children = []v1beta1.CallStackNode{{Frame: f, Children: children}}
    }
    return v1beta1.CallStack{Root: v1beta1.CallStackNode{Children: children}}
}
```

Attach schema1 CallStackEvidence with explicit false policy/global flags and actual
profile/provider/JVM hashes. Useful stacks alone become profile rows; unavailable,
skipped/error frames remain in capture evidence/counts, never fake stacks.
- [ ] **Step4: GREEN / mutation checks.** Test A/B frame order, same names with
  different BCI/quality, nil source data, native file IDs, source-list mutation,
  unsupported schema/profile, no useful frames, and exact unavailable counts.

```bash
go test -race ./pkg/config ./pkg/stackattribution/hotspot -run 'TestAgentlessHotspot|TestProjection' -count=1
git diff --check
git add pkg/config/config.go pkg/config/config_test.go configuration/config.json pkg/stackattribution/hotspot/types.go pkg/stackattribution/hotspot/project.go pkg/stackattribution/hotspot/project_test.go go.mod go.sum
git commit -m "feat(node-agent): add typed opt-in HotSpot stack projection"
```

Use actual approved dependency pins or local development workspace, never a fake
future checksum. Stage only task-owned hunks after the operator baseline is settled.

### Task 2: Implement owned process supervision and error controls

**Interfaces:** `Run(ctx context.Context, target wire.Target, verified
bundle.Verified, duration time.Duration) (wire.Capture,error)` and private child
owner. `ErrOwnerPending` is an explicit retained physical-owner outcome, not success.
No public fake process API; test same-package boundaries/real disposable processes.

- [ ] **Step1: Add a disposable Go child fixture** in `testdata/child/main.go`.
  It accepts mode `ok`, `early-eof`, `bad-session`, `permission`, `stderr-flood`,
  `hang-after-stop`; emits actual typed synthetic wire3 rows; reads exactly
  `stop\n` for graceful mode. Native errno13 is an explicit collector_error/exit1;
  no true kernel-denial claim comes from this fixture. Build it once into an owned
  test directory; synthetic full OCI assets pin those bytes through bundle.Verify.
- [ ] **Step2: Write/run failing controls.**

```go
func TestSupervisorRejectsEarlyEOF(t *testing.T) {
    v, target := childBundle(t, "early-eof")
    if _, err := Run(context.Background(), target, v, time.Second); err == nil {
        t.Fatal("missing summary/exit proof admitted")
    }
}
func TestSupervisorRetainsPermissionCause(t *testing.T) {
    v, target := childBundle(t, "permission")
    _, err := Run(context.Background(), target, v, time.Second)
    if !errors.Is(err, syscall.EACCES) { t.Fatal("permission downgraded", err) }
}
```

`childBundle(t,*mode*)` is a private helper implemented in `supervisor_test.go`:
copy the built fixture to the normal collector name, write its mode config next
to it, build a valid small synthetic OCI graph and trusted manifest; return
bundle.Verify plus Target. No unverified-path constructor is exposed for tests.

- [ ] **Step3: Implement the process/pipe owner.** Use exec.Command, not shell
  or automatic CommandContext kill on graceful request. Arguments are fixed from
  verified getters and Target; decimal FormatUint/FormatInt, no interpolation.

```go
args := []string{"--capture", "--protocol-version=3", "--runtime-profile=otel_hotspot25_observed_v1",
    "--hotspot-identity=unknown", "--drain-full", "--control-stdin",
    "--pid=" + strconv.FormatUint(uint64(target.PID), 10),
    "--expected-start-ticks=" + strconv.FormatUint(target.StartTicks, 10),
    "--expected-cgroup-id=" + strconv.FormatUint(target.CgroupID, 10),
    "--expected-mntns=" + strconv.FormatUint(target.MountNS, 10),
    "--duration=" + duration.String(), "--image=" + image, "--store=" + store}
cmd := exec.Command(collector, args...)
```

Here collector/image/store are successful Verified getter results. Start only
after full byte verification; assets are read-only operator storage. Own stdin,
stdout and stderr before Start; exactly one Wait. Read capped1MiB records through
wire.Decoder, drain stderr concurrently into64KiB tail with explicit truncation.
Readiness10s; graceful stop writes stop once and keeps pipes open while decoding
summary/EOF. Abort kills/reaps and joins readers; timeout returns pending owner
with retryable cleanup, never another capture or false retirement. Convert explicit
errno to a wrapped syscall.Errno; preserve exit/stderr/native causes together.
- [ ] **Step4: GREEN and bounded physical checks.** Test malformed row, oversize,
  wrong provider/target/session, stderr pressure, early exit, stdin EOF, target loss,
  held stdout and repeated Stop. Use subprocess PID absence/Wait and goroutine
  joins, not a deadline alone. Diagnostic prefix survives failures; no publishable
  Capture is returned on failed finality. No native matrix is implied by these tests.

```bash
go test -race ./pkg/stackattribution/hotspot -run '^TestSupervisor' -count=1
git add pkg/stackattribution/hotspot/supervisor.go pkg/stackattribution/hotspot/supervisor_test.go pkg/stackattribution/hotspot/testdata/child
git commit -m "feat(node-agent): supervise bounded agentless HotSpot captures"
```

### Task 3: Bind real targets, storage capability and bounded control state

**Interfaces:**

```go
type Resolver interface {
    Resolve(context.Context, *containercollection.Container) (wire.Target, error)
}
type Runner interface {
    Run(context.Context, wire.Target, bundle.Verified, time.Duration) (wire.Capture, error)
}
type Sink interface {
    ReadyForHotSpot(containerID, imageID string) bool
    PublishHotSpot(containerID, imageID string, stacks []*v1beta1.IdentifiedCallStack) error
}
type procIdentity struct {
    pid, tgid uint32
    birth, cgroup, mntns uint64
    hasHotSpot bool
}
type inspector interface { Observe(pid uint32) (procIdentity, error) }
type StatusKind uint8
const (Disabled StatusKind = iota; Discovering; Running; Retiring; Failed; Closed)
```

Create `Controller` with `ContainerCallback`, `Run(ctx) error`, `Stop(ctx) error`,
`Status() (StatusKind,error)`. Constructor consumes config, Resolver/Runner/Sink,
verified bundle, storage capability and evidence writer. Effects stay here; pure
target/queue/status decisions are separately table-tested.

- [ ] **Step1: Add identity/queue/capability RED cases.** Candidate PID lists
  use existing `ProcessTreeManager.GetPidList` and
  `GetContainerProcessTree(containerID,pid,false)`. A private Inspector reads
  actual `/proc/stat` ticks, maps/libjvm and namespaces. Feed two candidates with
  same namespace PID but different host PID/birth/cgroup; only the exact current
  Container.CgroupID/Mntns/image tuple may be selected. Zero/multiple JVMs yields
  explicit unavailable, not arbitrary choice. Test remove/re-add/PID reuse,
  queue8 overflow, delayed metadata, second active job and pending owner rejection.
  Define private pure queue types in controller.go and test them directly:

```go
type jobKey struct { containerID string; pid uint32; birth uint64 }
type queueState struct { active *jobKey; pending []jobKey; ownerPending bool }
type admission uint8
const (queued admission = iota; duplicate; full; blockedOwner)
func enqueue(queueState, jobKey) (queueState, admission)
```

```go
func TestControllerDoesNotReusePendingOwner(t *testing.T) {
    s := queueState{ownerPending: true}
    got, a := enqueue(s, jobKey{containerID: "new", pid: 123, birth: 456})
    if a != blockedOwner || got.active != nil || len(got.pending) != 0 {
        t.Fatal("new capture admitted before physical retirement", got, a)
    }
}
```

`enqueue` clones its bounded pending slice, rejects duplicates/full without
mutation, and has no native/process side effect; Controller owns effects.
- [ ] **Step2: Implement storage capability before launching anything.** Query
  the selected storage API's OpenAPI via existing REST client with deadline; use
  the B generated model names, require StackFrame.symbol and IdentifiedCallStack
  .evidence refs/schema1. Missing fields or401/403 propagate to Failed and launch
  zero children/probes. Do not infer support from the locally linked Go type.
- [ ] **Step3: Implement target selection and state/effect table.** Use raw
  start ticks (extract/reuse the existing pure ParseStartTicks in the shared
  bridge), not Process.StartTime or guessed HZ conversion. Require PID is a TGID,
  actual libjvm mapping and current exact container membership. Deep-copy receiver
  inputs; never retain mutable container callback pointers. Reconcile at1s with
 10s discovery bound; one15s window per incarnation, one active child/node, queue8.
  Native readiness/stop budgets do not expand. Container removal cancels its job;
  pending physical owner prevents reuse. Every failure retains original cause.
- [ ] **Step4: Add bounded, atomic evidence output.** New private writer creates
  per-container/session roots exclusively, bounded JSONL/typed data and SHA manifest,
  fsyncs/checks close then renames. Max32MiB raw/capture, fixed256MiB node quota,
  no automatic deletion of older evidence. Reject new jobs when quota is full.
  If current raw output reaches its bound, request stop, continue draining without
  blocking stdout, mark evidence exhausted/diagnostic-only and publish no stacks.
  Test symlink/traversal, concurrent destination, rename/close/ENOSPC and crash
  prefix. Directory permissions/errors are failures, not warnings/fallbacks.
- [ ] **Step5: GREEN / commit.**

```bash
go test -race ./pkg/stackattribution/hotspot -run 'TestTarget|TestController|TestCapability|TestEvidence' -count=1
git add pkg/stackattribution/hotspot
git diff --cached --check
git commit -m "feat(node-agent): bind bounded HotSpot jobs to container lifetimes"
```

Stage only task files, excluding already committed files and unrelated prototypes.

### Task 4: Publish typed stacks without changing enforcement authority

**Files:** New manager files/tests, helper hashing, callstackcache filtering/tests.
**Interfaces:** The concrete manager implements Sink's two named methods; the
large existing manager interface/mocks need no new method merely for this feature.

- [ ] **Step1: Add storage-boundary and enforcement-index regressions.**

```go
func TestProfilingEvidenceIsNotAnEnforcementStack(t *testing.T) {
    tree := NewCallStackSearchTree()
    observed := v1beta1.IdentifiedCallStack{CallID: "obs", Evidence: &v1beta1.CallStackEvidence{
        SchemaVersion: 1, PolicyEligibility: false,
    }, CallStack: v1beta1.CallStack{Root: v1beta1.CallStackNode{Children: []v1beta1.CallStackNode{
        {Frame: v1beta1.StackFrame{Symbol: &v1beta1.FrameSymbol{SchemaVersion: 1, FunctionName: "routeA"}}},
    }}}}
    tree.AddCallStack(observed)
    if _, ok := tree.PathsByCallID["obs"]; ok { t.Fatal("profiling observation became policy authority") }
}
```

Also test nil legacy Evidence still indexes exactly as before. Manager tests must
reject stale image/container, true policy/global claims and partial failed captures;
accepted symbols/order/pointers must survive `getCallStacks`/save conversion.
- [ ] **Step2: Implement one checked publish operation.** `ReadyForHotSpot`
  checks current watched container/image. `PublishHotSpot` rechecks inside one
  map/entry transaction, validates source evidence, computes existing manager
  dedup keys and stores owned deep copies under the existing profile-size budget.
  Return errors directly; do not call public ReportIdentifiedCallStack recursively
  while holding its container lock. No profile from failed decoder/Wait/retirement.
  Inspection found that `Close` holds map then entry locks, while `withContainer`
  releases its map lock before entering the callback. The new publication path
  must therefore keep map-read then entry-write ownership throughout its checked
  batch, not reacquire a map lock inside `withContainer` or permit a stale detached
  entry to receive an accepted publication. Pre-check the existing size budget
  before any mutation; do not enter the old over-budget split-channel send path.
- [ ] **Step3: Make hashes/keys symbol-aware only for new typed data.** Preserve
  exact legacy CalculateSHA256CallStackHash output when Symbol/Evidence are nil.
  For new data, hash canonical length-delimited JSON of typed frame/evidence data;
  tests catch same legacy FileID/line with different symbols. Cache dummy-root
  detection considers Symbol; exclude explicit false-policy observations before
  enforcement indexing. No enabling of their policy eligibility is added here.
- [ ] **Step4: GREEN and regression suite.**

```bash
go test -race ./pkg/containerprofilemanager/v1 ./pkg/objectcache/callstackcache ./pkg/stackattribution/hotspot -count=1
git add pkg/containerprofilemanager/v1/hotspot_capture.go pkg/containerprofilemanager/v1/hotspot_capture_test.go pkg/containerprofilemanager/v1/helpers.go pkg/containerprofilemanager/v1/hotspot_hash_test.go pkg/objectcache/callstackcache
git diff --cached --check
git commit -m "feat(node-agent): publish observational typed HotSpot stacks safely"
```

Existing kernel/environment tests may require selected integration prerequisites;
record explicit skips, run required new pure tests uncached, do not hide failures.

### Task 5: Wire startup/lifecycle/stop and release handoff

**Files:** cmd/main.go/new helper/tests, exact config/docs/dependency wiring,
Makefile and normal/debug Docker package-build entrypoints.
**Interfaces:** the following private cmd helper; its types are actual existing
interfaces plus the Sink declared above. The helper owns no target agent.

```go
func startAgentlessHotspot(ctx context.Context, cfg config.Config,
    watcher containerwatcher.ContainerWatcher, processTree processtree.ProcessTreeManager,
    sink hotspot.Sink, storageClient storageapi.StorageClient,
) (*agentlessHotspotFeature, error)
```

Imports in cmd/hotspot.go use `storageapi` for pkg/storage; avoid colliding with
main.go's existing v1 storage alias. The concrete manager implements Sink without
importing the hotspot package, avoiding a package cycle.

The private handle owns the controller, failure channel and idempotent receiver
unregistration/stop; returning only a stop closure would lose asynchronous errors
or require global state. `Controller.WaitForAdmission` distinguishes completed
storage/assets/evidence checks from constructed Discovering state and native
collector_ready. Terminal status receipts are bounded, retain pending ownership,
and never invent target/session identity. Unadmitted failures cannot pretend a
filesystem receipt was written. These are internal wiring choices, not new native
qualification or a public fake process API.

Existing Make and both Dockerfiles build only cmd/main.go; switch those three
node-agent entrypoints to ./cmd so the new helper participates. Keep the separate
SBOM scanner entrypoint, gadget sources/locks and release dependency gate unchanged.

- [ ] **Step1: Add disabled/no-side-effect and enabled/failure wiring tests.**
  A private constructor counter must stay0 when disabled, even with nonexistent
  assets/storage. Enabled rejects missing application profile, unsupported schema,
  invalid bundle and EACCES with no child. Baseline receiver dispatch still runs.
- [ ] **Step2: Register controller using existing watcher methods.** After
  baseline watcher exists, register ContainerReceiver and replay current
  `GetContainersBySelector(GetContainerSelector())` through the same callback.
  No modification of open.go/BPF exit events. On stop unregister first, cancel
  discovery, request/finish owned child, retain pending owners, then report final
  feature state. Integrate with actual main signal/stop ordering before returning.
- [ ] **Step3: Feature failures are explicit.** Controller.Run returns the real
  cause to the owning main goroutine and records Failed; it does not report Ready,
  launch an alternative collector or keep retrying a native permission failure.
  Baseline sensors remain active independently. Expose status in the bounded
  evidence status receipt for operators/tests, including pending-owner cause.
- [ ] **Step4: Focused/owning checks and commit.**

```bash
go test ./cmd -run '^TestAgentlessHotspot' -count=1
go test ./pkg/config ./pkg/stackattribution/hotspot ./pkg/containerprofilemanager/v1 ./pkg/objectcache/callstackcache
make binary check-legacy-packages check-ig-pin
git diff --check
git add cmd/main.go cmd/hotspot.go cmd/hotspot_test.go configuration/config.json pkg/config
git commit -m "feat(node-agent): wire opt-in owned HotSpot collection"
```

Follow N's actual CI/gadget tests and source instructions. Do not claim a new
attached JVM or persisted API result from host tests; D is that gate.
- [ ] **Step5:** Produce `build/hotspot-node-handoff.json` with actual node/SDK/
  storage commits, artifact manifest, config/test results and image build context.
  Use the existing approved gadget build/export prerequisites, then build the
  ordinary base node image with `make docker-build-only`; record its actual
  node_image_digest. Registry pushes remain operator-approved. Self-review
  default-disabled baseline/source locks before D consumes it.
