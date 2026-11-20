---
document_type: execution_plan
base_revision: a28cbe293289f2549858aa232d9e6ebd7c4756dd
status: host_source_review_complete_external_acceptance_blocked
---

# HotSpot Remaining Integration and Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended only when the operator selects delegation) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Work inline by default; no worktree, commit, publication, deployment or native execution is authorized by this document.

**Goal:** Close the remaining release-graph, owned-runner and real two-consumer acceptance gaps without rebuilding the already implemented collector, bridge, Kotlin API or offline verifier.

**Architecture:** Reuse published bridge v0.2.0, the existing node-agent companion/evidence writer, the reviewed typed-storage implementation and the completed `Profile`/`Corpus` checks. Finish one small acceptance runner executed directly on its explicitly approved test node: local procfs and run-owned host directories provide discovery, worker snapshots and gates; existing Kubernetes/generated storage clients provide object ownership and actual API readback. MazeWall's already compiled direct-host public-API test remains an independent acceptance gate.

**Tech Stack:** Existing Go1.27 node-agent graph, Go storage generated clients, Kubernetes client-go/CRI APIs already in that graph, Linux procfs/cgroupv2, JDK25/Kotlin profiler, pinned Temurin25.0.4.1+1-LTS, existing Gradle tasks.

**Spec:** [Governing A/B/C/D design](../specs/2026-10-18-kubescape-agentless-hotspot-design.md), especially B/C/D; [observed profile contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md); [approved recovery plan and executed checkpoints](2026-10-22-hotspot-two-consumer-recovery.md). Existing subsystem plans remain authoritative: [storage/companion acceptance](2026-10-18-kubescape-agentless-hotspot-acceptance.md) and recovery Tasks3–6. This is their remaining-work delta, not a fresh A/B/C implementation or a replacement for historical evidence.

## Global Constraints

The following requirements are copied from the governing design:

- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Rehash every asset byte and validate the complete local OCI graph; no size/mtime/inode digest cache or trusted-helper self-verification shortcut.
- No dependencies or breaking public APIs; additive native/public interfaces require explicit approval and legacy ABI verification before promotion.
- No swallowed/downgraded EPERM/EACCES. Fallback is explicit operator choice only.
- Captured occurrence/session/process generation/BOOTTIME identity is immutable; never substitute a later stack or infer logical identity from host TID alone.
- Physical drain, selected-stream accounting, whole-session collection and policy eligibility remain separate. Policy coverage stays incomplete for selected openat evidence.
- Native builds remain optional, reproducible and operator-invoked; no startup compiler/download/sudo or registry fallback.
- Preserve user work, scope commits to the relevant backlog item and use the orchestrator-managed review path; no default-branch push.
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.

Applicable recovery constraints resolve the historical strict/observed distinction:

- Observed selects `otel_hotspot25_observed_v1`, schema3/capture ABI2/collector3, ordinary tiered compilation/G1. Every row explicitly carries `policy_eligibility:false`; global coverage remains incomplete. Strict2 and AUTO do not change.
- The companion remains default-disabled, one active collector, eight queued incarnations, one15s window per incarnation,10s discovery,1s reconciliation and256MiB node evidence quota. Ready metadata stays16KiB; capture payload stays32MiB.
- No agent options, dynamic attach, target injection, direct Gadget/OTel embedding, wider JDK support or new symbolizer. Do not republish or retag unchanged bridge v0.2.0.
- Preserve all dirty M/N/S work and the Tier E prototype. No automatic staging, commits, pushes, merges, branch switches or worktrees. Each task ends at a review checkpoint, not an automatic commit.
- Native collector execution, Docker/image operations, cluster operations, fullgate and publication each require their applicable fresh operator authority. Existing HOST slots, N3 candidates and historical Ready receipts grant none.
- Keep strict-eight FAILED/INCOMPLETE, Task5 unaccepted, Task6/fullgate HARD_BLOCKED and all historical custody/resource obligations intact until their own evidence changes. Do not query or resume old protected resources to supply new inputs.

---

## 1. Starting state — do not repeat completed work

Paths below use these prefixes:

```text
M=/home/leanid/Documents/code/java/jseccomp
N=/home/leanid/Documents/code/java/stack-attribution/node-agent
S=/home/leanid/Documents/code/java/stack-attribution/storage
```

| Already implemented/checked | Evidence or location | Not established by it |
|---|---|---|
| Explicit Kotlin `observeHotSpot`, schema3 validation, diagnostic/error mapping, additive ABI | Recovery checkpoint;113 host tests,108passed/5explicitly skipped | Actual public-API native acceptance |
| Direct-host Kotlin acceptance fixture and independent target parser | `M/profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObserved{IntegrationTest,TargetIdentity,TargetIdentityTest}.kt` | Its native body remains unexecuted |
| Published bridge v0.2.0 selected by N | Actual module sums in `N/go.mod`, `N/go.sum` | Typed-storage release readiness |
| Reviewed typed storage and previous local live JSON/protobuf gate | `S/docs/agentless-stack-evidence.md`; reviewed local checkout4c2e1cf1 | A remotely resolvable reviewed module/server image |
| Asset context preparation and derived image recipe | `N/examples/agentless-hotspot-sbob/run.go`, `N/build/Dockerfile.hotspot` | An actual built/qualified node image |
| Decoder-accepted ready receipt through existing ownership/writer | `N/pkg/stackattribution/hotspot/evidence_ready.go`; companion race checks | A target lease, completion or independent acquisition |
| Original saved-JSON symbol verification and400-operation corpus/TID checker | `N/examples/agentless-hotspot-sbob/verify/{profile,corpus}.go`; Linux `cmd/verify --oracle` | Live worker snapshots, API request custody or physical retirement |
| Latest complete example race/vet/format checks | `/tmp/opencode/hotspot-corpus-final-20261023.log`: CLI7.346s, verifier21.282s | Released graph, native acceptance or fullgate |
| Canonical workload copied verbatim and compiled | N `workload/ObservedWorkload.java`; `cmp` and host javac25.0.4.1.1 passed | Image build or workload/native execution |

Storage v0.0.303 is still selected and cannot compile the typed companion normally. Read-only inspection on2026-10-23 also found no `FrameSymbol`/`CallStackEvidence` in v0.0.304 (`c87618e6b701d9b48615f65ad2548092f4fe5d5f`) or remote HEAD (`e3186031c85a15ef13b5f46d45f933d6f5ac8733`); the queried typed branch was absent. Do not select v0.0.304 merely because it is newer.

## 2. Scope, file map and order

This design already separates independently deliverable subsystems. Keep their gates separate rather than writing another all-in-one runtime platform.

| Task | Owning files | Responsibility |
|---|---|---|
| 1: released graph | N `go.mod`, `go.sum`, `docs/agentless-hotspot.md`; existing S contribution | Select real reviewed storage source/version and build N without a workspace |
| 2: independent node-side acquisition | Create N example `runner/identity_linux.go`, `runner/identity_linux_test.go`; extend `verify/corpus.go`, `cmd/verify/main.go` | Validate run inputs, bind node/container/process independently and save gated worker snapshots/source oracle |
| 3: saved API/bundle proof | Create N example `runner/readback_linux.go`, `runner/readback_linux_test.go`, `verify/bundle.go`, `verify/bundle_test.go`; extend `verify/profile.go`, `cmd/verify/{main,main_test}.go` | Record actual JSON/protobuf bodies and verify bounded original acceptance artifacts |
| 4: owned runner | Create N example `runner/run_linux.go`, `runner/run_linux_test.go`, `cmd/run/{main,main_test}.go`; modify N example README | Sequence the existing companion/workload with checked Kube ownership, gates, final capture and cleanup |
| 5: MazeWall native acceptance | Existing M `HotSpotObservedIntegrationTest.kt`, `profiler/HOTSPOT_AGENTLESS.md`, recovery checkpoint | Execute existing public test only after exact input/authority selection |
| 6: Kubescape execution | Existing N image/workload recipes; create `N/docs/agentless-hotspot-acceptance.md` | Build selected images, execute owned runner and retain real positive/negative acceptance |
| 7: quality/review/handoff | Existing project gates/docs/backlog; optional N native workflow after real runner selection | Complete scoped review and actual owning gates without waivers or automatic publishing |

Do not rename the existing example `run.go`: it currently owns only `PrepareImageContext`. Put orchestration in the separate `runner` package (`package hotspotrunner`); the root package and `cmd/prepare` must not import it or `verify`. This preserves standalone preparation's independence from typed storage, including while Task1 is blocked.

**Dependency order:** Tasks2–4 may be developed/tested in the explicitly labeled reviewed development workspace while Task1 is externally blocked. Task5 is independent of the storage release. Task6 requires Tasks1–4 plus fresh image/cluster authority. Task7 cannot declare complete acceptance while Task5/6 or required gates are blocked.

**Direct-node boundary:** N's base image is distroless (`N/build/Dockerfile`); no shell, `cat`, `touch` or `kubectl exec` helper is assumed. Execute the acceptance runner on the explicitly selected Linux test node, with initial-namespace procfs, approved read-only CRI status access and access to its newly owned gate/evidence roots. Use Kubernetes API clients for pod ownership, not remote shell scripts. This placement and its exact permissions must be included in the execution request; if unavailable, stop rather than deploy another privileged inspector or revive a retired VM.

For host-only Tasks2–4, select the already reviewed development workspace explicitly and label its output as development evidence:

```bash
export GOWORK=/tmp/opencode/hotspot-two-consumer-20261022.go.work
export GOTOOLCHAIN=local TMPDIR=/tmp/opencode
go version
```

Require the installed selected Go1.27 toolchain; no automatic toolchain download. Commands below run in N unless M/S is named. If that workspace no longer selects the reviewed storage checkout and published bridge, stop and reconcile it rather than creating a silent replacement. Task1's `GOWORK=off` commands deliberately override this development setting.

## Task 1: Resolve the reviewed storage contribution and normal dependency graph

**Files:** Modify N `go.mod`, `go.sum`, `docs/agentless-hotspot.md`; inspect S `docs/agentless-stack-evidence.md`, generated API and existing contribution diff. No new schema implementation is planned.

**Interfaces:** Consumes actual operator-selected reviewed storage revision; produces a real module resolution receipt and a `GOWORK=off` node build. This is an external release dependency, not a test failure to bypass with a permanent `replace`.

- [x] **Step 1: Record current source/baseline without changing refs.**

```bash
git -C "$N" status --short
git -C "$N" diff -- go.mod go.sum
git -C "$S" status --short
git -C "$S" log -1 --format='%H %s'
```

Preserve the existing scoped S implementation/generated files. Prepare its reviewable contribution through the existing review path if no reviewed remote revision exists; publication/PR creation is a separate operator action. Record `blocked` until the selected revision actually resolves.

Execution checkpoint: read-only baseline and33-file existing S contribution inventory are prepared and independently reviewed for report accuracy. No N/S source change, remote selection, pin/build or runtime acceptance is claimed. Task1 remains blocked on an actual reviewed remote storage revision and matching server/image selection; the independent host-only Tasks2–4 may proceed.

- [ ] **Step 2: Resolve the selected revision, never guess a tag or checksum.**

```bash
: "${STORAGE_REVISION:?operator must select the actual reviewed remote revision}"
GOWORK=off go mod download -json "github.com/kubescape/storage@$STORAGE_REVISION"
```

Run in N. Save the returned `Version`, `Sum`, `GoModSum` and `Origin` beside the review evidence, excluding credentials. Inspect that downloaded revision's `types.go`, generated protobuf/OpenAPI/deepcopy/applyconfiguration and aggregation support. Require `StackFrame.Symbol` field4 and `IdentifiedCallStack.Evidence` field3, optional pointer preservation, explicit JSON false fields and the reviewed full Create response behavior. Missing fields reject selection before editing N.

- [ ] **Step 3: Pin the actual resolved version and check the existing regressions.**

```bash
: "${STORAGE_VERSION:?use Version from the successful reviewed resolution}"
GOWORK=off go mod edit "-require=github.com/kubescape/storage@$STORAGE_VERSION"
GOWORK=off go mod download "github.com/kubescape/storage@$STORAGE_VERSION"
GOWORK=off go test -race ./pkg/stackattribution/hotspot ./examples/agentless-hotspot-sbob/... -count=1
GOWORK=off go test -race ./pkg/containerprofilemanager/v1 ./pkg/objectcache/callstackcache ./pkg/config ./cmd -run 'Hot[Ss]pot|HotSpot' -count=1
GOWORK=off make binary
```

Expected: normal graph compiles and the existing typed/false-policy regressions pass. Do not edit module caches, unify Gadget graphs, change the bridge pin or weaken failed assertions. Use actual resolver output for checksums.

- [ ] **Step 4: Review gate.** Record the exact module/source/sums, normal binary command/result, unchanged default-disabled behavior and remaining server-image prerequisite in N docs. If S source changed, run its owning generated/schema/aggregation tests and `make test` under the separately applicable environment; prior unchanged local storage evidence need not be recreated. Submit only task-owned changes for review when authorized; do not stage the dirty prototype.

**Deliverable:** Either a tested normal released dependency graph, or an exact unpublished/unreviewed storage blocker with a prepared scoped contribution. A local workspace is not an alternative completion verdict.

## Task 2: Bind source oracle and worker identities before opening any gate

**Files:** Create N example `runner/identity_linux.go`, `runner/identity_linux_test.go`; extend `verify/corpus.go`, `verify/corpus_test.go`, `cmd/verify/main.go` and its tests. Read the existing `wire.ParseStartTicks`, companion `targets_proc.go`, and [namespace-custody issue](../../internals/backlog/issue-20261022-230122-825-bind-hotspot-workload-oracle-tids-across-pid-names.md).

Execution checkpoint: Task2 host implementation is complete and independently reviewed after two image-contract findings were reproduced/corrected. Final example race checks pass (runner2.456s, CLI7.845s, verifier21.938s), vet/format and preparation dependency isolation pass. This is not live acquisition or initial-procfs-origin proof. The run-level receipt input fields below are introduced by Task4's preflight correction, not invented by Task2 acquisition.

**Interfaces:** Move the CLI's existing oracle DTO into the existing verify package, without changing its JSON:

```go
// In package verify; shared by acquisition and the existing offline CLI.
type WorkloadOracle struct {
    Operations []Operation `json:"operations"`
    WorkerStatuses map[uint32][]byte `json:"workerStatuses"`
}

func ValidateOracle(target wire.Target, oracle WorkloadOracle) error {
    threads, err := workerThreadIDs(target.PID, oracle.WorkerStatuses)
    if err != nil { return err }
    _, _, err = corpusOracle(oracle.Operations, threads)
    return err
}
```

In the new package `hotspotrunner`, define the actual runner input/result and acquisition types:

```go
type RunOptions struct {
    Kubeconfig, Context, Namespace, Node, NodeUID, NodeBootID string
    NodeImageDigest, StorageImageDigest, WorkloadImageDigest string
    AssetsDir, ProviderManifestSHA, RuntimeEndpoint, Output string
    NodeOriginFile, BuildReceiptFile string // Required by Task4's run-level preflight.
}
type Result struct { ManifestPath, ProfilePath, EvidencePath string }
type TargetSnapshot struct {
    PodName, PodUID string
    Target wire.Target
    StatBefore, StatAfter, Cgroup, Maps, MountNS []byte
    Oracle verify.WorkloadOracle
}
var ErrRunInputs = errors.New("invalid HotSpot acceptance run inputs")
func validateRunOptions(o RunOptions) error
func parseWorkloadOracle(directory string, rows [4][]byte) ([]verify.Operation, error)
func acquireTarget(ctx context.Context, o RunOptions, pod *corev1.Pod) (TargetSnapshot, error)
```

The new types live only in the acceptance example, not in the library/bridge API. `directory` is `/workload`, not the host path. Import `corev1` from existing `k8s.io/api/core/v1`, `wire` from the published bridge and `verify` from this example.

Execution review correction: `RunOptions.WorkloadImageDigest` retains the full immutable repository reference, but the returned target's ImageID must match the existing companion's **bare SHA256 manifest digest**. Validate API desired-image pin and actual digested-reference fields against the independent selection; preserve original status/CRI image-name/tag and local-ID fields separately. Containerd name fields can legitimately be tags and are not manifest identities. Do not equate every image field or alter the shared CallID algorithm. See [the image-contract issue](../../internals/backlog/issue-20261023-074235-300-bind-hotspot-capture-and-profile-image-identities-.md).

- [x] **Step 1: Write input/parser RED tests, using actual malformed values.**

```go
func sourceOracleRows() [4][]byte {
    var rows [4][]byte
    for worker := 0; worker < 4; worker++ {
        route := []string{"routeA", "routeB"}[worker%2]
        for ordinal := worker; ordinal < 400; ordinal += 4 {
            rows[worker] = append(rows[worker], []byte(fmt.Sprintf(
                "marked-%d\t%s\t%d\n", ordinal, route, 11+worker))...)
        }
    }
    return rows
}
func TestRunnerRejectsUnselectedInputs(t *testing.T) {
    if err := validateRunOptions(RunOptions{}); !errors.Is(err, ErrRunInputs) {
        t.Fatal("unselected run accepted", err)
    }
}
func TestOracleParserRejectsDuplicateSourceToken(t *testing.T) {
    rows := sourceOracleRows()
    rows[0] = bytes.Replace(rows[0], []byte("marked-4\t"), []byte("marked-0\t"), 1)
    if _, err := parseWorkloadOracle("/workload", rows); err == nil {
        t.Fatal("duplicate original source token accepted")
    }
}
func TestOracleParserRejectsEscapingSourceToken(t *testing.T) {
    rows := sourceOracleRows()
    rows[0] = bytes.Replace(rows[0], []byte("marked-0\t"), []byte("../marked-0\t"), 1)
    if _, err := parseWorkloadOracle("/workload", rows); err == nil {
        t.Fatal("source token escaped workload root")
    }
}
func TestOracleParserAcceptsOriginalFourWorkerRows(t *testing.T) {
    operations, err := parseWorkloadOracle("/workload", sourceOracleRows())
    if err != nil || len(operations) != 400 {
        t.Fatal("complete original source oracle rejected", len(operations), err)
    }
}
```

Run from N in the labeled development workspace:

```bash
go test ./examples/agentless-hotspot-sbob/runner -run 'TestRunnerRejects|TestOracleParser' -count=1
```

Expected initial RED: new acquisition/input functions absent. That is a source-contract RED, not native evidence.

- [x] **Step 2: Implement the bounded input/parser boundary.** Require explicit absolute regular kubeconfig, exact context, explicit namespace/node UID/boot ID, three immutable `name@sha256:<64 lowercase hex>` image references, absolute approved local CRI socket, absolute read-only verified assets and a new absolute private output root. Reject empty/default-context selection, existing output, symlink roots and invalid digest forms. Read each original `oracle-N.tsv` with64KiB bound; require exactly100 rows/file, three tab-separated fields, canonical `marked-0`…`marked-399`, the source worker's modulo4 distribution, exact route parity and one numeric namespace TID/file. Call `ValidateOracle` after status acquisition; do not reconstruct missing oracle rows from events.

- [x] **Step 3: Implement independent target/worker acquisition and its regression controls.** Use existing CRI/gRPC dependencies for read-only status of the exact API-selected `containerd://` container ID, and require returned runtime/image/container identity to agree. Support the selected containerd verbose status PID envelope explicitly; missing/ambiguous PID metadata rejects, with no runtime/socket autodetection or launch/stop calls. Read local `/proc/<pid>/{stat,status,cgroup,maps,ns/mnt}` and current cgroupv2 inode independently; use `wire.ParseStartTicks`, not HZ/wall time. Require exactly one HotSpot process incarnation and a workload namespace PID1. Record node UID/boot ID plus the operator's direct-node/initial-procfs selection; namespace field consistency alone cannot prove initial origin.

Enumerate `/proc/<pid>/task/*/status` while `ready` exists and `measure`/`done` do not. Read at most64KiB/status, select each worker by the independent oracle's innermost TID plus complete target-bound namespace chain, never by Java thread name or collector TID. Exactly one candidate per worker is required. Save all four original bodies, original TSVs and before/after raw birth/membership; repeat the identity observation after acquisition. Changed birth, container/image/cgroup/mount membership, permission denial or disappeared worker rejects before `measure`.

Tests use a temporary proc tree and fake CRI transport. Cover positive two-level and nested chains; duplicate/missing worker; wrong PID/TGID/image/container; equal endpoints with an impossible intermediate leader/init/collision; changed birth/membership; EACCES; a changed gate during acquisition. Reuse existing corpus tests for mapping/route rules rather than copying their implementation into the runner.

- [x] **Step 4: GREEN and review gate.**

```bash
go test -race ./examples/agentless-hotspot-sbob/... -count=1
go vet ./examples/agentless-hotspot-sbob/...
git diff --check
```

Keep the existing `--oracle` JSON byte representation (base64 original status bytes) and reject an explicitly empty oracle flag. Update README with the direct-node requirement. No local proc fixture or fake CRI response is a live target receipt.

**Deliverable:** Independently acquired, bounded and birth-bracketed source oracle/status evidence usable before measurement, with no cluster/native execution needed for its host tests.

## Task 3: Fetch original persisted JSON/protobuf and verify a complete saved bundle

Execution checkpoint: host source implementation and scoped review are complete after four Important findings were corrected. The amended-revision example race passed; its child-local completion handoff was recovered without rerunning the suite. Final cross-task triage subsequently corrected Minor5 (strict repository-reference grammar) and a runner opaque-metadata compatibility finding in one combined wave with clean scoped re-review. The final covering race passes (runner885.429s, verifier946.683s, run CLI1.111s, verify CLI7.614s), vet/format pass and all26source hashes match. This closes the host source gate, not actual API custody, independent provenance or runtime acceptance.

**Files:** Create N example `runner/readback_linux.go`, `runner/readback_linux_test.go`, `verify/bundle.go`, `verify/bundle_test.go`; extend `verify/profile.go` and `cmd/verify/{main,main_test}.go`. Read S `tests/agentless_stack_roundtrip_test.go` methods `stackAPIRoundTrip`, `stackBodyRecorder.wrap`, `stackCompareTyped` and `TestStackEvidenceWireExamples`; reuse their generated-client/transport semantics, not their synthetic live result.

**Interfaces:**

```go
// In hotspotrunner; identity comes from independently selected workload/profile metadata.
func readProfiles(ctx context.Context, cfg *rest.Config, id verify.ProfileIdentity, directory string) error

// In verify; maximum input sizes remain the existing bounds.
func ProfileProtobuf(raw []byte, expected wire.Expected, id ProfileIdentity, saved []byte) error
func VerifyBundle(root string) error

type BundleTarget struct {
    Capture wire.Expected `json:"capture"`
    Profile ProfileIdentity `json:"profile"`
    ResourceVersion string `json:"resourceVersion"`
    Files map[string]string `json:"files"`
}
type BundleManifest struct {
    SchemaVersion uint32 `json:"schemaVersion"`
    NodeOriginFile string `json:"nodeOriginFile"`
    BuildReceiptFile string `json:"buildReceiptFile"`
    Targets []BundleTarget `json:"targets"`
    SHA256 map[string]string `json:"sha256"`
    PolicyEligibility bool `json:"policyEligibility"`
    GlobalCollectionComplete bool `json:"globalCollectionComplete"`
}

type RetirementState string
const (
    Retired RetirementState = "retired"
    Pending RetirementState = "pending"
    Unknown RetirementState = "unknown"
)
type OwnedResourceReceipt struct {
    Kind, Namespace, Name, UID string
    State RetirementState
}
type RetirementReceipt struct {
    State RetirementState
    CollectorPID uint32
    CollectorStartTicks uint64
    CaptureManifestSHA256 string
    CollectorIncarnationGone bool
    TargetPID uint32
    TargetStartTicks uint64
    TargetExitObserved bool
    Resources []OwnedResourceReceipt
    Errors []string
}
type MediaReceipt struct {
    Requested, ContentType string
    Status int
    Complete bool
    Bytes int64
    SHA256 string
}
```

`Files` has exactly `raw`, `capture`, `profileJSON`, `profileProtobuf`, `oracle`, `identity`, `ready`, `readyManifest`, `captureManifest`, `retirement`, and `media` for each target, each a run-root-relative regular file. Retain both original companion manifests and all files they digest. `identity` is Task2's original independent acquisition document, not a duplicate expected tuple manufactured by the collector. `retirement` contains the runner's actual owned-resource/cleanup result and the companion publication/terminal observations described in Task4. The manifest schema is private to this acceptance example; it is not a new library wire version.

`media` contains exactly two `MediaReceipt` values, JSON then protobuf, digesting the corresponding original profile bodies. `RetirementReceipt` is accepted only for `Retired`, a source-bound capture manifest, the observed disappearance of that exact collector incarnation, observed target exit, no errors and every acquired resource retired with its actual UID. `Pending`/`Unknown` remain valid rejected-report states, never acceptance. Do not parse error text to invent an expected errno or a successful cleanup.

`NodeOriginFile` and `BuildReceiptFile` are required bounded root-relative originals, covered by the same digest inventory. They record the actual selected node name/UID/boot ID/procfs-origin receipt and reviewed source/diff/module/compiler/image/asset identities respectively. Preserve the operator-provided independent origin/build receipts; a runner-authored Boolean does not certify its own namespace origin or grant consent.

Execution interface correction: Task4 receives these original external trust inputs through explicit `RunOptions.NodeOriginFile`/`BuildReceiptFile` and `--node-origin`/`--build-receipt` flags, validates them before any client/resource effects, and copies their original bytes into the new bundle. Task3 defines their bounded schema and consistency checks for that handoff. Their presence is not runtime authorization or cryptographic proof manufactured by this offline checker; the separately approved operator/input custody remains mandatory. Task2's lower-level acquisition preflight does not fabricate these missing run-level receipts.

Execution review correction: compiler identities must be associated with the finite built-artifact roles `nodeAgent`, `storage`, `collector` (Go) and `workload` (javac), independently retaining their actual versions/hashes. Values may coincide only when actually identical. One shared Go identity cannot describe the already admitted Go1.26.0 collector and Go1.27 node build; do not rebuild or relabel unchanged artifacts to satisfy a schema. The bridge is a library compiled inside its consumers, not a separately invented compiler artifact.

Complete-bundle cleanup requires the exact independently acquired Pod and ContainerProfile UID for each target, and consistent shared workload/control Namespace UIDs plus the control Pod named `node-agent` in `<workload-namespace>-control`; both positive targets share the single run workload namespace. These fixed tuples are derivable, unlike arbitrary omitted live resources. Task4 still records the complete actual created config/RBAC/resource inventory and unknown outcomes, with actual acquired UIDs. See [the bundle-acceptance issue](../../internals/backlog/issue-20261023-085514-293-keep-hotspot-offline-bundle-acceptance-fail-closed.md).

- [x] **Step 1: Write RED tests at the actual transport boundary.** Use `rest.Config.WrapTransport` and a local `http.RoundTripper`, following S's existing generated-client negotiation test. Return a typed profile response with JSON content type to a protobuf request; require rejection even if the object itself decodes correctly. Return an erroring/truncated response reader; require original read/close causes and no successful receipt. Test absent symbols, omitted JSON false fields, wrong UID/resourceVersion and byte corruption. Do not assert success from a marshaled DTO or fake importer ACK.

For the new protobuf verifier, use the existing `syntheticProfile(t)` helper in `verify/profile_test.go` (same package), the storage generated `scheme` package, and this actual test shape:

```go
func TestProfileProtobufRejectsJSONMasqueradingAsProtobuf(t *testing.T) {
    raw, expected, identity, saved := syntheticProfile(t)
    data, err := json.Marshal(saved)
    if err != nil { t.Fatal(err) }
    if err := ProfileProtobuf(raw, expected, identity, data); err == nil {
        t.Fatal("JSON accepted as Kubernetes protobuf")
    }
}
```

Run:

```bash
go test ./examples/agentless-hotspot-sbob/... -run 'TestProfileProtobuf|TestReadback|TestBundle' -count=1
```

- [x] **Step 2: Implement exact negotiated GETs and opaque body custody.** Build two copies of the explicitly selected `rest.Config`; select JSON and Kubernetes protobuf separately, each with exact `ContentType`/`AcceptContentTypes`, no negotiated fallback. Use the actual generated `SpdxV1beta1().ContainerProfiles(namespace).Get` call after a bounded metadata selection tied to the workload instance/image; require exactly one matching profile and retain its actual name/UID. Never Create/import an expected stack to make a positive persisted-profile test pass.

Wrap the existing transport to save bounded original response bytes before decoded objects are used; record only requested media, response Content-Type/status and read-completeness, never credentials/full headers/URLs. Limit original bodies to32MiB, use new0600 files, checked writes/close and preserve partial evidence on failure. Use one30s total readback context, not independent unbounded retries. Wait only for profile availability; a semantic mismatch rejects rather than being retried until hidden. Both format responses must have the same acquired UID/name/namespace and resourceVersion. A version race is a rejected snapshot, not fabricated equality.

- [x] **Step 3: Implement protobuf and bundle validation without remarshal shortcuts.** Require the Kubernetes `k8s\x00` envelope, then decode using the selected generated storage scheme:

```go
if !bytes.HasPrefix(saved, []byte{'k', '8', 's', 0}) {
    return ErrProfileIdentity
}
object, _, err := scheme.Codecs.UniversalDeserializer().Decode(saved, nil, nil)
if err != nil { return errors.Join(ErrProfileIdentity, err) }
profile, ok := object.(*v1beta1.ContainerProfile)
if !ok { return ErrProfileIdentity }
return checkTypedProfile(raw, expected, id, profile)
```

Keep existing bounded input guards before that code. The original JSON response is still checked with `Profile`, including explicit false-field presence. A protobuf zero default is not an independent explicit-JSON-denial receipt; require the same-revision original JSON companion for complete bundle acceptance.

At the existing HotSpot-specific saved-profile identity boundary, reproduce and correct the full repository-reference versus bare target-digest mismatch identified by Task2 review. Keep the original saved Spec.ImageID/raw response unchanged; compare only strictly validated immutable manifest identities to the independently selected digest. Preserve legacy exact-equality controls where required, reject mutable-tag/local-config-ID substitutions and wrong digests, and prove projected canonical CallIDs remain in the unchanged companion digest scope. This does not authorize rewriting baseline profile fields or accepting an image identity from the collector.

`VerifyBundle` requires schema1, exactly two distinct positive targets, explicit false authority fields, distinct container IDs/host incarnations and independently captured acquisition/retirement/media documents. Resolve only normalized relative paths below `root`, reject symlinks/nonregular files/unknown roles and impose the existing per-file limits plus256MiB total. Hash actual files, then call `Profile`, `ProfileProtobuf` and `Corpus` for each target; independently compare the ready row/target/session, capture manifest hashes and acquisition tuple. A checksum is necessary but never substitutes for semantic checks.

For mutation regressions, use two synthetic target profiles with distinct images/frames and update the manifest digest after swapping/pruning the saved response: the negative must reach symbol/identity verification rather than fail only a stale checksum. Missing/false retirement, `ownerPending:true`, unknown cleanup, wrong node/boot origin or missing transport proof reject. Original native/permission and cleanup failures remain in a rejected report.

- [x] **Step 4: Extend the existing verifier CLI, not a second command.** Add `--bundle` as mutually exclusive with `--raw/--expected/--profile/--oracle`. The existing explicit profile-only mode stays supported and honestly labeled. Bundle mode calls `VerifyBundle`; no Kubernetes access occurs during offline verification. Add missing-bundle, mixed-mode, oversized/symlink and semantic-mutation CLI tests.

```bash
go test -race ./examples/agentless-hotspot-sbob/... -count=1
go vet ./examples/agentless-hotspot-sbob/...
git diff --check
```

**Deliverable:** Real generated-client readback/opaque-body recorder with host fake-transport regressions, and a bounded offline bundle checker. Passing local wire examples is not API custody acceptance.

## Task 4: Implement the run-owned node-local Kubernetes runner

Execution checkpoint: host-source work is complete and independently reviewed as serial4a watcher lifecycle,4b shared image ownership and4c actual runner/CLI units. Task4a's overtaken-Add finding and Task4c's five production-contract findings were corrected with behavioral controls and clean scoped re-review. Final cross-task review and one combined correction wave are also complete: all source findings, including original Minor5, are addressed. The final covering race passes (runner885.429s, verifier946.683s, run CLI1.111s, verify CLI7.614s), vet/format pass; unchanged owning gates are reused. The first application/feature-ready observation has a separate120s startup bound for unchanged baseline initialization; actual collector10s readiness/15s window and original BOOTTIME bounds remain unchanged. Preserved image cwd is `/root`, with HOME/config separate. All effects are synthetic; actual UID1000 filesystem/capabilities, external custody and Task6 runtime acceptance remain unqualified. No whole-plan acceptance is claimed. See the [host-source handoff](../../internals/reviews/2026-10-23-hotspot-host-source-handoff.md).

**Files:** Create N example `runner/run_linux.go`, `runner/run_linux_test.go`, `cmd/run/main.go`, `cmd/run/main_test.go`; modify example README. Preserve asset-preparation `run.go`, the default node image and all Tier E files. Linux-only command/runner files use the existing Linux build-tag convention.

**Interfaces:** Consumes Task2 `RunOptions`, `TargetSnapshot`, `acquireTarget`; Task3 `readProfiles` and `verify.VerifyBundle`; existing ready/capture evidence contracts. Produces:

```go
func Run(ctx context.Context, options RunOptions) (Result, error)
```

Use existing Kubernetes clients with private fake-client/transport injection for tests. No new exported backend/lifecycle framework, helper process supervisor, continuous monitor or production introspection endpoint is needed.

Execution preflight addition: the current N checkout still registers HotSpot after watcher startup through a receiver set copied at startup and command-local raw collection replay. Before runner acceptance, reproduce this known boundary using the existing [lifecycle issue](../../internals/backlog/issue-20261019-170844-967-deliver-hotspot-container-lifecycle-events-after-w.md), then minimally port the already reviewed current-membership/registration/replay correction available at local N commit `fd108c3b410dbc06e53984e311b6abc9b9a0fe29`. Source scope is `N/cmd/hotspot.go`, `N/pkg/containerwatcher/v2/{container_watcher,container_watcher_collection,containercallback}.go` and the corresponding `hotspot_receivers_test.go` plus `cmd/hotspot_test.go`. The small predecessor live-subscription/built-in callback-assembly changes from `2fd19c150b7ad916ff9d25bf370d9b2256528c4d` are necessary: fd108c3's isolated diff assumes them. Adapt its host fixture to the current metadata path with a local fake client/context rather than adding unrelated production predecessor guards. Preserve unrelated/current namespace-filter work; do not copy entire files, reset, cherry-pick over dirty work or invent another subscriber. This is a host-only prerequisite correction under the existing issue, not new native execution or a new API.

The [image-contract issue](../../internals/backlog/issue-20261023-074235-300-bind-hotspot-capture-and-profile-image-identities-.md) also requires a host regression/correction in N `pkg/containerprofilemanager/v1/hotspot_capture.go` and its existing tests: shared watched ImageID retains the full API reference while the companion supplies the bare manifest digest. Fix the HotSpot ownership comparison once using strictly validated immutable identities; preserve watched metadata, stored profile strings, legacy behavior and the shared CallID scope. Do not make the runner bypass the existing sink or import expected stacks to hide this defect.

- [x] **Step 1: Write the pre-effect RED checks.**

```go
func TestRunnerRejectsInputsBeforeClientConstruction(t *testing.T) {
    result, err := Run(context.Background(), RunOptions{})
    if !errors.Is(err, ErrRunInputs) || result.ManifestPath != "" {
        t.Fatal("invalid selection reached acquisition", result, err)
    }
}
func TestRunnerCancellationBeforeAcquisition(t *testing.T) {
    ctx, cancel := context.WithCancel(context.Background())
    cancel()
    _, err := Run(ctx, RunOptions{})
    if !errors.Is(err, context.Canceled) {
        t.Fatal("cancelled run acquired resources", err)
    }
}
```

Run `go test ./examples/agentless-hotspot-sbob/runner -run '^TestRunner' -count=1`; confirm initial RED. Implement early context/options checks before loading kubeconfig, dialing CRI or creating directories/resources.

- [x] **Step 2: Implement fail-closed environment admission and owned objects.** Read only the explicitly named kubeconfig and reject a different current context; never call context-switch commands. Require selected Node UID, Linux-amd64, boot ID, runtime and direct-node procfs/bootstrap evidence to match. Fetch the deployed storage image identity and served schema before launching a feature-enabled node agent. Missing storage capability rejects; existing production capability logic remains authoritative, with a failed node admission never relabeled ready.

Create a new private run directory with `evidence`, `gates/workload-a`, `gates/workload-b` and `receipts`. `RunOptions.Namespace` selects the new run-unique workload namespace; create a separate `${Namespace}-control` namespace for node-agent so its own-namespace exclusion does not exclude the targets. Validate both names/lengths before effects; either pre-existing namespace is not owned. Record each successful API-issued UID immediately. Create a narrowly scoped service account/RBAC, one standalone node-agent pod in the control namespace and two standalone workload pods in the workload namespace, pinned to the selected node and immutable image digests; use `restartPolicy: Never` and `imagePullPolicy: Never` so failure does not silently create a new incarnation or trigger registry acquisition. Images must already be loaded under Task6's explicit image authority. The runner may deliberately create a replacement pod only as a separate recorded restart control.

The fixed control Pod name is `node-agent`; record its actual API-issued UID. The existing Task2 gate layout is `<Output>/gates/workload-a` and `workload-b` (not a second root-level gate layout). Preserve the original companion evidence layout and map/copy its original files into the final bundle roles rather than changing producer files.

Construct Kubernetes objects through existing typed clients, not executable YAML substitutions. Node-agent gets only the explicitly approved hostPID/BPF/proc visibility, read-only assets and writable run-specific evidence mount. Workloads get their separate run-owned hostPath at `/workload`, PID1 plain Java as UID1000, no hostPID/agent/GC overrides/Docker socket. Host gate directories must be writable by UID1000 before pods start; the evidence root stays private to the selected node-agent/runner ownership. Save sanitized rendered objects and actual UIDs/images; do not print kubeconfig or credentials.

Build test config using existing N `config.Config`: application profiling enabled; `NamespaceName` set to the control namespace; `IncludeNamespaces` restricted to the workload namespace; `AgentlessHotspot` enabled with only the existing assets/digest/evidence fields; `InitialDelay=time.Second`, `UpdateDataPeriod=time.Second`, `MaxSniffingTime=5*time.Minute`, zero jitter. These are test profile-publication timers, not larger native budgets. Keep node-agent's own namespace exclusion and baseline behavior; test that unrelated namespaces never enter this feature's target queue. The shortened existing save interval makes actual profile readback possible within the30s bound without an importer ACK or production save-method call. Populate its existing approved storage/API connection settings from the selected environment, not ambient production configuration.

- [x] **Step 3: Implement the per-target gate sequence and observable ordering tests.** For each queued target, in actual companion-ready order:

1. Wait for its original workload `ready` and four TSV files with the process alive.
2. Acquire the independent API/runtime/proc tuple and Task2 snapshots while workers are gated; persist original acquisition evidence first.
3. Wait for the matching existing `collector_ready` manifest/ready.json. Verify its manifest bytes/digest, schema/profile/provider, current independently rechecked tuple and session. Acquire the actual collector child PID/raw birth from the independently resolved node-agent process and admitted executable/argv/target binding; retain its original proc observation. Generic `Running`, elapsed time or an old session never qualifies.
4. Recheck workload pod UID/container incarnation and task membership, then create only that workload's `measure` file with no-replace semantics.
5. Require original `done` contents `400\n`, keep target alive, and wait for the matching existing completed capture/raw manifest within the unchanged native window. Failed diagnostics, quota failure or pending ownership reject immediately; do not start another window or substitute a later stack.
6. Verify and retain the original raw capture/source oracle, then service the next actual ready target before doing slow storage readback. Once both native windows are complete, use Task3 to fetch each actual JSON/protobuf profile. Never consume the second target's15s window waiting on the first profile API. Keep targets alive until collector ownership is actually retired; no release based only on wire finality.

Tests use local gate files plus fake Kube/CRI transports. Assert that `measure` is absent on wrong/missing ready, mismatched incarnation, invalid worker snapshots or EACCES; present only after matching readiness/acquisition; and that `release`/pod deletion stays absent while the existing companion owner is pending. Test both target orders, ensure the second target stays gated during the first15s window, and block the first API readback to prove it cannot delay the second measurement gate. This is acceptance sequencing, not a second model of native unwinding. Real EOF/Wait/read-callback ownership remains covered by the owning companion package's existing disposable-child tests; do not import its private fixture builders into the new runner.

- [x] **Step 4: Implement cleanup without invented ownership.** Persist a run ledger of exact created namespace/pod/config/RBAC UIDs, independent target identities, gate state and companion evidence paths. Capture publication is trusted only when bound to the reviewed supervisor source whose success path has consumed EOF/joined readers/Wait; raw logical finality alone is insufficient. Retain corresponding actual node process observations and terminal feature/cleanup evidence when stopping the owned node agent.

Release a target only after its collector is proven retired: the reviewed supervisor's completed publication must bind the actual session/target, and the independently acquired child incarnation must be gone rather than zombie/pending. Record this as source-bound supervisor publication plus independent disappearance, not a direct `Wait` observation by the runner. On pending/unknown ownership, preserve the target, node-agent and run namespace/directories and return a rejected result with unresolved owners; no timeout or best-effort delete becomes retirement. If a request may have created an object but no trustworthy UID was acquired, record unknown acquisition and require operator reconciliation; never mint a UID or delete by name. Cleanup acquired objects with UID preconditions and independent5s cleanup contexts, join original and cleanup errors, and retain all receipts even if writing one receipt fails. Never delete an operator namespace, old environment or shared deployment. A namespace Delete alone does not prove pod/process retirement: observe owned pod deletion/terminal state and associated host incarnation disappearance before declaring cleanup complete.

Host controls must include lost Create response, foreign UID/name reuse, receipt-write failure, refused Delete, cancelled main context, late EACCES, hanging reader/cleanup and a retained target while pending. Reuse existing `ErrOwnerPending` and companion retry behavior; do not introduce a new retry daemon or treat process disappearance alone as successful `Wait`.

- [x] **Step 5: Write command/bundle output and run GREEN.** `cmd/run` accepts the exact flags shown in Task6's invocation, mapping one-to-one to Task2 `RunOptions`; reject positional/unknown flags and missing inputs. It invokes no ambient cluster commands and performs no image build/pull/publication. Preserve partial `run-manifest.json`, `report.md`, original evidence and failure/cleanup receipts on rejection; only emit an accepted verdict after `VerifyBundle` and cleanup succeed. Paths returned in `Result` remain available for diagnosis on error; no automatic deletion. Keep distinct pre-cleanup diagnostic and final-manifest files so finalization does not erase a failed acquisition or its first report.

```bash
go test -race ./examples/agentless-hotspot-sbob/... -count=1
go test -race ./pkg/stackattribution/hotspot -run 'TestReady|TestSupervisor|TestRuntimeController' -count=1
go vet ./examples/agentless-hotspot-sbob/...
GOWORK=off go test ./examples/agentless-hotspot-sbob ./examples/agentless-hotspot-sbob/cmd/prepare -count=1
git diff --check
```

**Deliverable:** A host-tested owned runner and CLI with synthetic effects explicitly labeled. No claim that the node/procfs/API/runtime boundary has been qualified until Task6 runs.

## Task 5: Execute the existing MazeWall public observed test under a fresh grant

**Files:** Existing M profiler test and `profiler/HOTSPOT_AGENTLESS.md`; record results in the recovery checkpoint. No new native fixture, symbolizer or remote launcher is planned.

**Interfaces:** Existing `MazewallProfiler.observeHotSpot(target, HotSpotOptions)` and `HotSpotObservedIntegrationTest`; consumes independently trusted observed assets, exact pinned JVM executable and the operator-selected direct-host environment.

- [ ] **Step 1: Prepare one reviewable execution request.** Record current source/diff digest, reviewed binary/assets provenance and trusted manifest SHA, exact Java executable/build, host kernel/boot/initial-procfs/cgroup visibility, permissions for BPF/uprobes/perf/process-memory reads, one selected test and owned-child cleanup/retention scope. Read only newly selected operator-provided inputs, never recover a consumed HOST/N3 candidate. If any input is unavailable, report that exact prerequisite rather than inventing a runnable command.

- [ ] **Step 2: After approval, verify bytes and invoke the existing test once.** Require the following environment variables to contain the approved actual values, not paths copied from historical plans:

```bash
: "${MW_HOTSPOT_OBSERVED_JAVA:?select the approved pinned JVM executable}"
: "${MW_HOTSPOT_ASSET_TEST_DIR:?select the approved observed asset directory}"
MW_HOTSPOT_OBSERVED_ACCEPTANCE=true \
MW_HOTSPOT_OBSERVED_JAVA="$MW_HOTSPOT_OBSERVED_JAVA" \
MW_HOTSPOT_ASSET_TEST_DIR="$MW_HOTSPOT_ASSET_TEST_DIR" \
./gradlew :profiler:test \
  --tests 'io.mazewall.profiler.hotspot.HotSpotObservedIntegrationTest'
```

Keep its existing cache bypass,400operations/four workers/20ms, false-policy checks, pending-owner hold, actual child exit and retained scratch. No sudo/container/strict fallback on failure.

- [ ] **Step 3: Inspect actual output, not only Gradle exit.** Require an executed non-skipped XML test, exact400marked records/terminals, at least360correct useful routes, zero wrong TIDs/routes/order, incomplete policy rejection, retained original diagnostics and checked collector/target retirement. Record original JVM flags/maps and actual compiled-frame evidence; absence of `-Xint` alone does not certify compiled coverage. Preserve the first rejection. Reuse matching collector six-case qualification; rerun affected controls only if source/bytes or a concrete concern invalidate it. Strict-eight is a separate ledger.

**Deliverable:** Independently reported MazeWall public-API native acceptance or its exact rejection/unexecuted prerequisite, never inferred from113host tests.

## Task 6: Build selected images and execute real Kubescape persisted-profile acceptance

**Files:** Existing N `build/Dockerfile.hotspot`, example workload recipe/source and new Task4 CLI; create `N/docs/agentless-hotspot-acceptance.md`. Update M recovery checkpoint only with actual results.

**Interfaces:** Consumes successful Task1 normal graph, Tasks2–4 source, reviewed schema-capable server image, actual node/workload image digests and a freshly approved direct-node cluster scope. Produces original two-target API/capture/acquisition/cleanup bundle accepted by Task3.

- [ ] **Step 1: Consolidate exact image/node/storage/runtime execution inputs.** Operator selection must name current cluster/context/namespace policy, actual node UID/boot ID/initial PID namespace, absolute kubeconfig/CRI endpoint, approved writable host directories and process-memory/BPF permissions, reviewed source/build inputs, schema-capable server image digest and accepted collector manifest. Include image build/load/pull operations separately and name whether publication is excluded. Do not claim releaseReady from the historical local handoff or restore retired environments.

- [ ] **Step 2: After image authority, build only the missing selected images.** Reuse `PrepareImageContext`, named `hotspot_assets` context and unchanged companion binary. Select actual immutable node-agent base and Temurin image; package the same admitted collector bytes used by MazeWall. Record actual Docker image/manifest IDs and every packaged asset hash, not the chosen local tag. Verify disabled startup launches no helper/probes and preserves baseline sensors. Workload recipe stays plain UID1000/PID1 Java,400/4/20; no agent/interpreter/GC switches. Do not change the default image or run `make gadgets`/registry acquisition without explicit scope.

- [ ] **Step 3: Verify server schema before enabling collection.** The selected deployed server must serve the complete typed OpenAPI and preserve actual JSON/protobuf fields. Reuse S's existing `TestStackEvidenceAPIRoundTrip` only if the selected server/source differs or its prior gate is not applicable, under its explicit build tag and approved context/namespace. That server roundtrip is a prerequisite, not a substitute for node-agent-originated profile acceptance.

- [ ] **Step 4: Execute the Task4 runner on the approved node, then verify the original bundle.** Supply each explicit flag from the recorded `RunOptions`; the CLI must not infer ambient values. Require two distinct workload/container/image/host-incarnation bindings with overlapping namespace PID1,400marked pairs each, at least360correct useful routes each, zero wrong/swapped routes/TIDs/order, actual saved JSON/protobuf with exact typed symbols/locations/BCI/uint64 precision/provenance and explicit JSON false fields. Both API formats must be original responses for the same selected object revision. Check original gate/runtime/compiler/boot/process receipts and all owned cleanup.

```bash
go run ./examples/agentless-hotspot-sbob/cmd/run \
  --kubeconfig="${ACCEPTANCE_KUBECONFIG:?approved absolute kubeconfig}" \
  --context="${ACCEPTANCE_CONTEXT:?approved explicit current context}" \
  --namespace="${ACCEPTANCE_NAMESPACE:?approved new run workload namespace}" \
  --node="${ACCEPTANCE_NODE:?approved dedicated node name}" \
  --node-uid="${ACCEPTANCE_NODE_UID:?actual selected Node UID}" \
  --node-boot-id="${ACCEPTANCE_BOOT_ID:?actual selected node boot ID}" \
  --node-agent-image="${ACCEPTANCE_NODE_IMAGE:?loaded immutable node image reference}" \
  --storage-image="${ACCEPTANCE_STORAGE_IMAGE:?deployed reviewed server image reference}" \
  --workload-image="${ACCEPTANCE_WORKLOAD_IMAGE:?loaded immutable plain-JVM image reference}" \
  --assets="${ACCEPTANCE_ASSETS:?approved read-only absolute asset directory}" \
  --manifest-sha256="${ACCEPTANCE_MANIFEST_SHA256:?independently trusted manifest digest}" \
  --runtime-endpoint="${ACCEPTANCE_RUNTIME_ENDPOINT:?approved absolute local CRI socket}" \
  --node-origin="${ACCEPTANCE_NODE_ORIGIN:?original independent node/procfs origin receipt}" \
  --build-receipt="${ACCEPTANCE_BUILD_RECEIPT:?original reviewed source/module/image/asset receipt}" \
  --output="${ACCEPTANCE_OUTPUT:?new private absolute run directory}"

: "${ACCEPTANCE_BUNDLE:?use the actual retained output directory from cmd/run}"
go run ./examples/agentless-hotspot-sbob/cmd/verify --bundle "$ACCEPTANCE_BUNDLE"
```

This is an offline recheck after the live runner; it grants no runtime authority and cannot fabricate missing acquisition receipts.

- [ ] **Step 5: Run the existing negative/control matrix without hiding first failures.** Record each case separately: container/process replacement; new-route/new-incarnation capture; feature-disabled baseline; old/pruning storage; corrupt assets; native read/permission denial; target exit/exec; queue/quota pressure; hung child/reader/callback cleanup. Use existing host fault cases for faults already proven without a new native need. Any requested real-native destructive control must be explicitly included in the grant. Positive results do not erase failed controls or unknown owners. A source-new-route control must change actual test source/image, not remap an oracle to manufacture observed frames.

- [ ] **Step 6: Record verdict and review gate.** `N/docs/agentless-hotspot-acceptance.md` must list exact selected graph/images/JVM/node/kernel/boot, original response/capture digests, marked/useful/unavailable counts, loss qualifiers, API formats/object identities and actual ownership results. A rejected capture remains rejected; missing release/runtime prerequisites remain unexecuted. Keep related backlog issues open until their real integration/review conditions are satisfied.

**Deliverable:** Actual ordinary JVM → companion → storage → API-fetched typed profile acceptance, or the exact retained rejection. No production deployment, policy-completeness or universal JVM lifetime claim follows.

## Task 7: Finish the owning quality gates and contribution handoff

**Files:** M root/project-required gates; N existing CI/gates; changed S gates if applicable; M/N public guidance, recovery checkpoint and backlog. Follow [the testing strategy](../../internals/designs/core/testing-strategy.md).

**Interfaces:** Consumes final source revisions and actual Task5/6 results; produces separately classified source/native/persisted-profile/strict/fullgate outcomes and reviewable diffs.

- [ ] **Step 1: Resolve or explicitly retain the separate Detekt blocker.** [The maintenance issue](../../internals/backlog/issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin.md) owns16existing out-of-scope findings. Do not silently absorb unrelated fixture edits into the HotSpot contribution, suppress rules, update a baseline to hide them or claim the gate passed. If that maintenance scope is selected separately, reproduce its exact findings and use its focused fixture tests before the full gate. Otherwise leave full quality status blocked.

- [ ] **Step 2: Run final gates once per actual completion revision/environment.** Reuse valid same-revision focused/ABI checks; repeat only for source changes or concrete unresolved concerns. Full build includes integration selection and requires its own applicable authority:

```bash
./gradlew build
```

For N, run Task1's normal `GOWORK=off` build and final focused race/vet/example checks, then its configured CI-equivalent package scope in the approved environment. Existing N `.github/workflows/go-basic-tests.yaml` runs gadget preparation and privileged package tests; do not silently execute its `make gadgets`/sudo paths on an unapproved host. Record exact equivalent commands and unavailable prerequisites. Run S's owning full gates only when its source changed or selected server evidence requires them. Never substitute empty pre-index LSP diagnostics for compilation/tests.

- [x] **Step 3: One scoped connected-code review and correction wave.** Review actual diffs/callers for strict/observed isolation, independent target origin, original API bodies, JSON false-field presence, protobuf negotiation, source oracle/status mapping, frame order/precision, same-revision identity, default-disabled behavior, enforcement-index exclusion, bounded evidence, lost acquisition and all cleanup owners. Fix reproduced defects with RED/GREEN at their shared owner; do not add a duplicate review workflow or broader monitoring system. Rerun only affected gates after corrections.

- [ ] **Step 4: Add opt-in native CI only after selecting a real runner.** If the operator has an actually configured approved native test runner, add the existing planned N `.github/workflows/agentless-hotspot-acceptance.yaml` with manual dispatch, required explicit immutable inputs, run-owned output and original failure-artifact retention. No ambient production context, automatic cluster provisioning, unapproved credential handling or fake self-hosted label. Until such a runner is selected, keep the documented manual command and mark CI integration unconfigured rather than adding a workflow that cannot run.

- [ ] **Step 5: Validate docs/backlog and prepare scoped review handoffs.**

```bash
git diff --check
./scripts/adkw check-backlog
```

Run from M; also run N/S diff checks for their task-owned changes and validate new local documentation links. Keep M library, N companion/acceptance and S schema contributions distinct. Present exact candidate file lists/diffs through the orchestrator-managed review path; commits/publication/merge need the operator's applicable instruction. Never stage everything in the dirty repositories.

**Deliverable:** Honest review handoff with four separate statuses: source gates, MazeWall observed native acceptance, Kubescape persisted-profile acceptance, and historical strict/fullgate/custody state. No `complete` declaration while a required gate or owner is unresolved.

## Final stop conditions and intentionally skipped work

Stop at a real missing reviewed release, actual image/input identity, runtime authority, initial-procfs origin or trustworthy acquisition/cleanup outcome. Preserve the exact evidence and consolidate the missing prerequisites into one operator decision instead of repeated broad approval prompts. Continue independent host-only tasks where their inputs are available.

Excluded: another collector/bridge/storage schema; Gadget graph migration; more target agents; a privileged inspector pod or remote-shell framework; wider JVM/runtime/architecture support; continuous collection; global policy admission; strict-eight reruns without a concrete invalidation; rebuilding unchanged admitted assets; automatic publication, worktrees or commits.

## Execution handoff

Plan-only work does not authorize any of the runtime commands above. Choose **Subagent-Driven** for fresh per-task agents/review, or **Inline Execution** to continue serially in the current checkout using `executing-plans`. Task1 may remain externally blocked while the explicitly labeled host-only parts of Tasks2–4 proceed. Request native/image/cluster/fullgate/publication authority only with the exact prepared inputs for the applicable task.
