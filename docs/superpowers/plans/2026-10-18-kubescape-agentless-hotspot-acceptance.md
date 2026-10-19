---
document_type: execution_plan
base_revision: 16fab8e6f36f3f1fdb57964003affd30a420219c
status: pending_released_a_b_c_handoffs_native_vm_preflight_verified_retired
---

# Kubescape Agentless HotSpot Kubernetes Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Run only after actual A/B/C handoffs and explicit test-cluster authorization. No application agent, silent profile fallback or default-branch publication is implied.

**Goal:** Demonstrate a complete ordinary-JVM→external collector→node-agent→persisted typed ContainerProfile/SBoB path, including failure and cleanup evidence.

**Architecture:** Package the already qualified static collector/bundle into the node-agent image. A dedicated test workload runs plain Java and explicit fixture gates; the runner waits for collection readiness, releases marked work, fetches persisted profiles, verifies symbols/CallIDs/identity and retains every failure. The collector's baseline sensors remain separate.

**Tech Stack:** Existing Go1.27 Docker build, static Go collector, approved local Kubernetes cluster, pinned Temurin25, generated storage client/protobuf, standard Go example commands.

**Spec:** [Governing design, D](../specs/2026-10-18-kubescape-agentless-hotspot-design.md#d-end-to-end-exit).

**2026-10-19 checkpoint:** The operator approved a disposable local4vCPU/8GiB/
30GiB KVM VM with Ubuntu26.04/K3sv1.35.8+k3s1. Actual initial PID namespace,
cgroupv2/BTF/BPF/uprobe kernel options and CA-verified Ready node preflight passed.
Existing MicroK8s and Multipass VMs were not touched. This is not D execution:
required SDK review/publication, storage release provenance, real consumer pins
and the ordinary node image are still unavailable. C1–C5 host-side wiring is
locally committed/tested, not a release-ready C handoff. Both owned disposable
test environments were subsequently retired with checked physical/container
ownership and retained receipts, rather than left idle while release review is
pending. See [executed companion evidence](../../plans/2026-10-19-hotspot-companion-local-evidence.md).
Future D setup must acquire new actual node/process/image identities after the
required handoffs exist; historical Ready receipts cannot stand in for them.

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

## Files and inputs

All implementation paths below are relative to N; M supplies immutable A artifacts.

|Path|Responsibility|
|---|---|
|`build/Dockerfile.hotspot`|Derived opt-in image copies named collector context; default image recipe unchanged|
|`examples/agentless-hotspot-sbob/workload/{Dockerfile,AgentlessWorkload.java}`|Plain pinned JVM, four workers/two routes, finite test gates|
|`examples/agentless-hotspot-sbob/manifests/{config,node-agent,workload,rbac}.yaml`|Dedicated test deployment, explicit feature config and read-only host visibility|
|`examples/agentless-hotspot-sbob/{run.go,verify.go,run_test.go,verify_test.go}`|Owned cluster runner, raw/typed/API/profile verification|
|`examples/agentless-hotspot-sbob/cmd/{run,verify}/main.go`|Small explicit Go command entry points|
|`examples/agentless-hotspot-sbob/README.md`|Actual prerequisites, commands, scope and failures|
|`.github/workflows/agentless-hotspot-acceptance.yaml`|Opt-in configured native environment only|
|`docs/agentless-hotspot-acceptance.md`|Durable executed results, artifacts and open limits|

Do not edit the Tier E example. No workload -agentpath/-javaagent, dynamic attach,
JVM compiler/GC workaround, startup compiler, Docker socket or registry fallback.
The helper artifact already contains its compatible BPF/OCI graph.

### Task 1: Package verified assets without coupling Gadget dependency graphs

**Interfaces:** A provider.json + C node handoff→node image with
`/opt/kubescape/hotspot/{manifest.json,mw-hotspot-collector,oci/...}`. Image build
receipt records actual source/module/compiler/assets/base-image identities.
Task1 also produces `PrepareImageContext(assets, expectedDigest, output string)
error` in run.go: verify with bundle.Verify/Observed25, then copy exclusively into
an owned output context. It performs no Docker/native invocation itself.

- [ ] **Step1: Add a build-context inclusion test before Dockerfile change.**
  `TestImageContextRequiresVerifiedHotSpotBundle` in example run_test.go must
  reject absent/wrong digest and transitive corruption before invoking Docker.
  Use bundle.Verify from A; never run a helper to certify its own executable.

```go
func TestImageContextRejectsMissingManifest(t *testing.T) {
    assets := t.TempDir()
    output := filepath.Join(t.TempDir(), "context")
    if err := PrepareImageContext(assets, strings.Repeat("a", 64), output); err == nil {
        t.Fatal("unverified assets entered an image build context")
    }
    if _, err := os.Stat(output); !errors.Is(err, os.ErrNotExist) {
        t.Fatal("failed verification published a context", err)
    }
}
```

Also replay A's same-size transitive mutation against a valid synthetic bundle;
static image COPY alone is not asset verification.
- [ ] **Step2: Add a derived image recipe with one named build context.**

```dockerfile
ARG NODE_AGENT_IMAGE
FROM ${NODE_AGENT_IMAGE}
# hotspot_assets is an explicit BuildKit context, not a registry image.
COPY --from=hotspot_assets / /opt/kubescape/hotspot/
```

Keep node-agent/sbom-scanner/tracers and inherited entry point unchanged. The
default Dockerfile and ordinary `make binary` require no native assets or empty
context. Tests verify the default recipe remains independent. The build wrapper
rejects NODE_AGENT_IMAGE unless it is the actual recorded digest-pinned base.

- [ ] **Step3: Build once from actual handoffs and record exact output.** The
  derived Dockerfile contains a digest-pinned `ARG NODE_AGENT_IMAGE` base selected
  by its build input receipt, then the single COPY. Build command:

```bash
cd "$N"
ASSETS="$(jq -r '.assets_dir' "$M/build/kubescape-handoff/provider.json")"
BASE="$(jq -r '.node_image_digest' build/hotspot-node-handoff.json)"
docker buildx build --platform linux/amd64 --load \
  --build-context "hotspot_assets=$ASSETS" --build-arg "NODE_AGENT_IMAGE=$BASE" \
  -f build/Dockerfile.hotspot -t kubescape-hotspot:test .
```

Provider handoff also records assets_dir as a local output path; the trusted
manifest digest remains content authority. Node handoff must contain a real base
node image digest before this task; no placeholder image is accepted.
- [ ] **Step4: Inspect packaged bytes and disabled behavior.** Export/read the
  built image without starting collectors; compare every packaged asset and full
  reachable graph to A. Run feature-disabled startup/container tests, prove no
  helper launch/extra HotSpot probes and baseline open handling unchanged.
- [ ] **Step5: Commit tested image recipe/docs, no binaries.**

```bash
go test ./examples/agentless-hotspot-sbob -run '^TestImageContext' -count=1
git diff --check
git add build/Dockerfile.hotspot examples/agentless-hotspot-sbob
git commit -m "build(node-agent): package opt-in verified HotSpot collector assets"
```

Do not add the COPY to the default Dockerfile as well. No absolute source paths or
mutable image tags enter the qualification manifest.

### Task 2: Implement the owned runner and independent saved-profile verifier

**Interfaces:**

```go
type RunOptions struct {
    Context, Namespace, Node, NodeImageDigest, StorageImageDigest string
    ProviderManifestSHA, Output string
}
type Result struct { ManifestPath, ProfilePath, EvidencePath string }
func Run(context.Context, RunOptions) (Result, error)
func VerifyBundle(root string) error
```

`RunOptions` are explicit CLI inputs from approved cluster/A/B/C receipts. Run
refuses the ambient current context if it differs from Options.Context; no target
namespace or existing deployment is patched by assumption.

- [ ] **Step1: Write RED verifier tests with distinct frame fixtures.**

```go
func TestVerifierDetectsCrossContainerStackSwap(t *testing.T) {
    root := acceptanceBundle(t)
    // This fixture helper owns two distinct container/image/process/route captures.
    swapSavedProfiles(t, root)
    if err := VerifyBundle(root); err == nil { t.Fatal("cross-container stack swap accepted") }
}
func TestVerifierRequiresPersistedTypedSymbols(t *testing.T) {
    root := acceptanceBundle(t)
    removeSavedSymbol(t, root, "routeA")
    if err := VerifyBundle(root); err == nil { t.Fatal("CallID-only storage success accepted") }
}
```

Implement the three same-package helpers in verify_test.go using SDK captures and
B typed API objects serialized into a temporary bundle. They mutate actual saved
response fixtures, leaving source expected routes/IDs independent. Add missing
terminal/retirement, wrong manifest/process birth, false ready, loss/unknown and
zero-useful-symbol cases with specific expected failure classes.
The swap/remove helpers update the mutated response's file digest in the synthetic
manifest, while leaving the independent expected container/routes/CallIDs unchanged.
Thus the negative reaches semantic attribution/symbol checks instead of passing
only because a checksum was stale. Helpers are `acceptanceBundle(*testing.T)
string`, `swapSavedProfiles(*testing.T,string)`, and
`removeSavedSymbol(*testing.T,string,string)`; no production fixture API is added.
- [ ] **Step2: Implement plain Java workload/manifests.** Use pinned Temurin25
  image from A, `java -cp /app AgentlessWorkload`, no agent/forced-runtime flags.
  Each of two pods runs Java as PID1, four named platform workers and two route
  methods,400 unique marked tokens/20ms pacing. Source-defined gate files are test
  synchronization only. Keep targets alive until capture completion. Record actual
  default JVM flags/compilation evidence; do not infer compiled-frame coverage
  merely because no -Xint option was present.
- [ ] **Step3: Implement owned Kubernetes orchestration.** Use an approved
  dedicated test node/namespace and run-unique resource names/host evidence root.
  Node-agent has hostPID and the same explicitly approved BPF/proc permissions as
  qualified A, read-only host visibility/assets and writable owned evidence.
  No Docker socket. Verify storage schema before launching. Wait for each target's
  actual collector_ready receipt before releasing its marked gate; queue timing
  must respect C's one active capture and targets remain gated until their turn.

  Discover immutable container/image/host PID/birth/cgroup/mount IDs from the node
  and control plane; no pod-name/PID-only matching. After400 marked operations,
  request/wait for normal collector retirement before releasing JVM exit gate.
  Fetch ContainerProfile through the actual storage API using the generated client
  in both JSON/protobuf negotiations; save responses before verification.
- [ ] **Step4: Implement independent bundle checks.** Recompute CallIDs with
  the shared canonical algorithm; verify actual profile contains those exact IDs
  **and complete typed frame order/content/false policy evidence**, not only syscall
  presence or an importer ACK. Join every raw event to its original terminal and
  expected workload token/TID/container. Reconcile counts, selected loss, explicit
  unavailable/unknown lanes, retained/discarded data and all-owner receipts.
  Parent/child IDs/maps and actual profile API response hashes are in manifest.

  Bundle outputs are `sbob.yaml` (saved ContainerProfile), `stack-evidence.json`,
  bounded `syscall-events.jsonl`, `run-manifest.json`, `report.md` and original
  failure/cleanup receipts. A report with failed native capture is diagnostic,
  never a synthesized successful result. No target method/frame is reconstructed
  from source expectations to fill a missing observation.
- [ ] **Step5: GREEN host checks and commit.**

```bash
go test ./examples/agentless-hotspot-sbob -run 'TestVerifier|TestRunner' -count=1
go test ./examples/agentless-hotspot-sbob/cmd/...
git add examples/agentless-hotspot-sbob
git diff --cached --check
git commit -m "test(node-agent): verify persisted agentless HotSpot evidence end to end"
```

Runner host tests use a private fake command/API boundary for ordering/ownership,
not a second native profiler model; actual kernel/Kubernetes proof is Task3.

### Task 3: Execute positive/failure acceptance and make a release decision

**Files:** New example/readme/workflow/durable evidence and exact discovered fixes.
**Interfaces:** Actual acceptance bundle + final verdict `qualified` or `rejected`;
no warning, skip or partial-prefix artifact can be renamed a qualified deployment.

- [ ] **Step1: Explicit environment admission.** Record context/node/kernel/
  boot ID, CPU count, API/server versions, actual image digests, source/module locks,
  permissions/mounts and A/B/C handoffs. Stop on mismatch; do not install/change
  host crypto/sysctl/security settings to pass. External cluster operations need
  operator approval, and only run-owned resources may be removed.

```bash
cd "$N"
test "$(kubectl config current-context)" = "kind-agentless-hotspot"
go run ./examples/agentless-hotspot-sbob/cmd/run \
  --context kind-agentless-hotspot --namespace agentless-hotspot-test \
  --node "$(jq -r '.node' build/hotspot-cluster-inputs.json)" \
  --node-agent-image "$(jq -r '.qualified_node_image_digest' build/hotspot-node-handoff.json)" \
  --storage-image "$(jq -r '.server_image_digest' "$S/build/hotspot-schema-handoff.json")" \
  --manifest-sha256 "$(jq -r '.manifest_sha256' "$M/build/kubescape-handoff/provider.json")" \
  --output build/agentless-hotspot-sbob/result
```

`hotspot-cluster-inputs.json` is operator-owned test selection, not a guessed node.
Task1 records qualified_node_image_digest after the derived image is built. Context
string is a named test setup; selecting a different approved context requires an
explicit option/receipt, not removing the check.
- [ ] **Step2: Run distinct acceptance cases, preserve first failures.** Two
  containers with overlapping namespace PID1 and different host identities; two
  routes/workers; process/container restart; same-syscall new-route second capture;
  feature-disabled baseline; old storage; bad asset; native permission denial;
  target early exit/exec; queue/quota pressure; hung child/read/callback cleanup.
  Reuse host fault controls where no new real-native fault is justified; label
  synthetic versus actual native evidence. A15s job may not become continuous
  collection; omitted/failed jobs remain visible.
- [ ] **Step3: Verify actual outputs.**

```bash
go run ./examples/agentless-hotspot-sbob/cmd/verify --bundle build/agentless-hotspot-sbob/result
```

The positive gate includes all400 raw marked occurrences/terminals per target,
at least360 correctly bound useful stacks, zero wrong/swapped routes, actual saved
typed metadata and checked retirement. Unavailable outcomes/global uncertainty
stay explicit; no dense600/global/lifetime promotion follows.
- [ ] **Step4: Add opt-in CI on an actually configured native runner.** Keep
  normal unit/build CI independent. Manual native job requires exact A/B/C/image/
  cluster inputs and uploads originals on failure. An unavailable runner/prereq is
  unexecuted, not passing. Do not expose production credentials or print env dumps.
- [ ] **Step5: Final review, owning full gates and handoff.** Run N/S required
  build/tests and M's full gate once at their actual completion revisions; reuse
  valid prior gates where source is unchanged. One cross-project review, one
  consolidated correction wave, one scoped re-review. Record failures/limits in
  `docs/agentless-hotspot-acceptance.md` and keep backlog items open until their
  actual conditions are met. Integrate via operator-approved review paths, never
  direct default-branch push or deleting operator evidence/worktrees.

## Actual completion declaration

Only after Task3: state which plain-JVM build/profile/architecture/kernel and
workload rate passed, exact saved-profile source and artifact hashes, unavailable/
loss dimensions and owner retirement. The result is **event-correlated profiling
evidence**, not a sandbox boundary, universal Java lifetime proof or complete
behavior/policy inventory. Existing Tier E and historical native failures remain
separate evidence; this plan alone implements none of the acceptance results.
