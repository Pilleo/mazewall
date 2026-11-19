---
document_type: execution_plan
base_revision: a28cbe293289f2549858aa232d9e6ebd7c4756dd
status: in_progress_operator_selected_storage_fork
---

# HotSpot Persisted-Profile Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended only after delegation is selected) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This document selects neither execution, agents, worktrees nor external publication.

**Goal:** Finish the released storage/consumer graph and prove real node-originated HotSpot evidence survives storage readback and recreation, without repeating completed collector work or promoting observational evidence into policy.

**Architecture:** Reuse bridge v0.2.0, the accepted collector and reviewed storage/node implementations. Close the native evidence review, publish the exact existing storage scope, pin its actual remote revision, correct the opt-in runner's missing SYSLOG request, then qualify fresh images, the selected server and two owned live runs. Treat native, persisted-live, quality and release decisions as independent results.

**Tech Stack:** Linux-amd64, Temurin25.0.4.1+1-LTS/default tiered/G1, Kotlin/JUnit5, Go1.27 node tooling, independently pinned Go1.26.0 collector, Kubernetes/containerd CRI, existing generated storage JSON/protobuf clients, Docker/BuildKit and existing node-local runner.

**Spec:** [Governing A–D design](../specs/2026-10-18-kubescape-agentless-hotspot-design.md), [observed-profile contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md), [accepted source review](../../../.superpowers/sdd/2026-10-23-hotspot-release-and-runtime-qualification/final-review.md), and the **latest attempt4 section** of the [native result](../../internals/reviews/2026-10-23-hotspot-public-native-attempt.md). Earlier blocked paragraphs in dated records remain history, not current instructions to redo completed work. This is one integration-exit plan across already implemented A/B/C/D subprojects, not a second implementation of them.

## Operator amendment: use a storage fork for now

After delegated execution and the approved publication of upstream discussion
[kubescape/storage#455](https://github.com/kubescape/storage/issues/455), the operator
selected: **“we should fork kubescape for now”**. For the current storage dependency
gate this selects a `Pilleo` fork of **kubescape/storage**, not forks of the whole
organization or unrelated projects. This amendment supersedes Tasks2–3's canonical-
only release requirement for this integration; upstream acceptance remains pending.

- Create/reuse the actual `Pilleo` storage fork, verify its parent and identity, and
  publish only the exact already-reviewed33-path source package on a new scoped
  branch from its frozen upstream base `d963c7941f5bd493751109324e6eba41f7d2b5af`.
  Do not modify upstream, overwrite fork main, push old mixed local history, or
  claim the12newer upstream file changes have been integrated.
- A new private **bare object database** may construct that source-only commit
  from the frozen upstream base and reviewed patch; it has no checkout/worktree.
  No existing M/N/S HEAD, branch, index or user work is staged/committed/switched.
  Verify exact changed-path allowlist, source blob hashes, parent and exclusions
  before a new branch push to the selected fork. Preserve original seals.
- Task2's fork gate is the reviewed fork source commit actually available remotely,
  not an upstream merge/tag. Record the real fork URL/branch/commit separately from
  the upstream discussion and pending canonical integration.
- Task3 resolves the real fork commit with `GOWORK=off`, records actual resolver
  Version/Sum/GoModSum/Origin, and uses a **version-pinned remote replacement** of
  `github.com/kubescape/storage` to the actual fork module path. Preserve the original
  module declaration/imports and existing require; no local filesystem replacement,
  guessed pseudo-version, `@latest`, checksum bypass or unrelated dependency upgrade.
  This explicitly replaces the earlier no-storage-Replace condition: require the
  exact selected remote fork replacement and reject local/unselected alternatives.
- Fork-backed builds/server/live results must say **fork-backed**, never upstream
  released/accepted. Pin matching server source to the same fork commit and retain
  existing source checks only where byte/graph identity makes them applicable.
  Source/runtime/security/quality gates and the single-writer discipline remain.

The fork workflow does not authorize an upstream PR/comment/merge, default-branch
push, tag, extra native grant, permission change or cluster mutation. Do not create
another copy of upstream issue455 or wait for its maintainers before doing the
selected fork preparation and downstream checks.

## Global Constraints

Copied verbatim from the governing design:

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

Current execution constraints:

- Ordinary JVMs explicitly select `otel_hotspot25_observed_v1`, collector3/wire3/capture ABI2; strict2 and AUTO are unchanged. Policy eligibility and global collection completeness stay false.
- Companion: one active collector, queue8, one15s window/incarnation,10s discovery,1s reconciliation,256MiB evidence quota,16KiB ready/32MiB capture. Application-startup120s is not a longer collector window.
- Work in current checkouts. No branch switch/worktree, blanket stash, automatic stage/commit or destructive cleanup. The staged `CLAUDE.md` deletion and all unrelated dirty/untracked work are excluded.
- Planning authorizes no tests, downloads, image/cluster/native operations or publication. At execution, discover available inputs and prepare concrete commands first; do not ask the operator to find available paths or calculate hashes.
- Batch routine actions within the selected source/build/test/environment scope. Do not request another approval for each read, copy, hash, already-selected test or owned cleanup. A genuinely new external publication, production-context mutation or privilege/topology change needs a concrete reviewed action, not a vague prompt.
- Preserve four consumed native grants, all original failures, accepted attempt4 and historical unknown owners. Do not query/resume retired clusters/VMs or protected historical resources to supply new live facts.
- No collector rebuild, SDK publication, new runner flags, inspector pod, transport wrapper, startup hook or CI framework is planned. New source is limited to actual pin changes and the runner correction below; a newly discovered defect gets its own narrow regression/fix.

---

## Baseline: completed versus still open

| Component | Reuse, do not redo | Remaining gate |
|---|---|---|
| M source | Btrfs identity, capability-aware host initialization and direct-loopback fixture corrections; owning regression/race/vet checks | Review only these current deltas if their source review is needed for submission |
| M native | Attempt4:1test/0skips/failures/errors;400marked/380useful routes/176compiled marked events; target/helper cleanup and all file grants removed | Independent original-evidence review, not another invocation |
| M full build | Configured dirty checkout build0 in6m12s,228tasks; native opt-ins disabled | Explicit test-lint maintenance/merge prerequisites are separate, not waived by that build |
| Collector | SHA256 `0e84f865047a1f4af20f3298127f4377a192a62792c379bc5b3954e195210c1e`; manifest `c118376f81f1fc8685bb873af17bc1a247e484953f4b7d7e9da6e20bd592d0fa` | Package these exact bytes; do not rebuild merely for a new plan |
| S source |33unique contribution paths and five code/test corrections plus two docs corrections; final source handoff approved | Scoped publication, actual remotely resolvable revision and matching server image |
| N source | Reviewed26-path companion/runner/readback contribution | Real storage pin/standalone graph and explicit SYSLOG Pod request |
| Persisted live | No current node-originated acceptance | Selected node/images/original trust inputs, current API gate, positive/recreation/control evidence |

Observed checkout HEADs at planning: M `a28cbe293289f2549858aa232d9e6ebd7c4756dd`, N `72034696017511e05f44d115e4081f39821cbd4d`, S `4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f`, **each plus its current dirty work**. HEAD alone is not a source identity. Preserve strict-eight FAILED/INCOMPLETE and issue752's real USER_NOTIF timing/ENOSYS investigation; this plan does not repair it by declaring the new test green.

## File responsibilities and command roots

```bash
M=/home/leanid/Documents/code/java/jseccomp
N=/home/leanid/Documents/code/java/stack-attribution/node-agent
S=/home/leanid/Documents/code/java/stack-attribution/storage
GO=/home/leanid/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.27.0.linux-amd64/bin/go
P="$M/.superpowers/sdd/2026-10-24-hotspot-persisted-profile-acceptance"
Q="$P/private"
A="$M/.superpowers/sdd/2026-10-23-hotspot-release-and-runtime-qualification/private/task-8-artifacts"
R="$M/.superpowers/sdd/2026-10-23-hotspot-native-first-qualification"
export GOTOOLCHAIN=local
export GIT_OPTIONAL_LOCKS=0
set -C
```

Use the stated owning working directory; do not run node/storage commands from M. `GO` is the available host tool, not the collector compiler or a required compiler for every image. Long checks remain owned until actual terminal output. Every capture records argv/cwd/start/end/exit and original stdout/stderr; pipe-to-tee status is not the command's status. Use new attempt paths on any justified changed-revision rerun.

| File / artifact | Responsibility |
|---|---|
| `P/progress.md`, `P/task-N-report.md`, `Q/` | New execution ledger, original command/evidence custody and exact review packets; never edit old receipts |
| M existing native result and `R/private/{native-attempt-4,docker-permission-4}/` | Read accepted originals and their limits; add independent-review disposition, no replay |
| S exact paths in `A/storage-source-sha256.txt` | Existing source package; only actual changed/reviewed findings warrant source edits |
| N `go.mod`, `go.sum`, `docs/agentless-hotspot.md` | Actual remote storage require/sums and graph guidance |
| N `examples/agentless-hotspot-sbob/runner/run_linux.go:1073-1109` | Append SYSLOG only to the opt-in node-agent Pod capability set |
| N adjacent `run_linux_test.go` | Behavioral rendered-Pod regression using real `runOwned` and existing synthetic transport fixtures |
| N example README and companion guidance | Explain the explicit seven-capability request and real post-exec qualification requirement |
| N/S existing Dockerfiles, N workload Dockerfile/source | Reuse recipes; temporary copies pin only FROM inputs when necessary |
| S `tests/agentless_stack_roundtrip_test.go` | Existing current-server JSON/protobuf gate; do not replace it with a fake importer |
| N `docs/agentless-hotspot-acceptance.md` | New durable actual node/server/cases/retirement result |
| M `docs/internals/reviews/2026-10-24-hotspot-persisted-profile-acceptance.md` | New final independent lane summary |
| M backlog912/248/528/752 and index | Evidence-bound status; no archive on host checks alone |

Task dependencies: **1 independent review** and **2 storage publication** may advance independently. **2 → 3 released graph → 4 runner correction → 5 images/node → 6 server gate → 7 live cases → 8 quality/release handoff**. Task1 also gates the final combined verdict. Do not redispatch the completed26-path or full33-path audits.

### Task 1: Close the independent native-evidence review without a new run

**Files:** Read M native result, `R/private/docker-permission-4/{outcome.json,reviewed-outcome.json,final-verification.json}`, `R/private/native-attempt-4/` originals and only necessary connected fixture/source ranges. Create `P/native-outcome-review.md` and ledger; add a dated disposition to the public result only after review.

**Interfaces:** Consumes original accepted attempt4 plus preserved failed attempts1–3. Produces an independent PASS/REJECTED/INCOMPLETE review tied to original hashes, not a new native exit or fabricated receipt. If the current worker produced those receipts, obtain a distinct reviewer through the selected workflow; do not call self-review independent.

- [ ] **Step 1: Acquire new private custody and record source boundaries.** Require all parent components to be nonsymlink directories and the new workspace not to exist; create it0700 and preserve current status/HEAD/index metadata in M/N/S without staging. Do not duplicate credentials or kubeconfig contents.

```bash
# From M; after ancestry/ownership checks, exclusive creation:
mkdir -m 0700 "$P"
mkdir -m 0700 "$Q"
git status --short > "$Q/mazewall-status.txt"
git rev-parse HEAD > "$Q/mazewall-head.txt"
git diff --cached --name-status > "$Q/mazewall-staging.txt"
```

- [ ] **Step 2: Review the original acceptance predicates read-only.** Verify all484attempt4 file hashes against its original outcome; preserve68/81/81older hashes. Check400unique marked records,380useful correct routes and176compiled marked IDs/actual frame quality; account for401total records/397resolved/4unavailable. Confirm finalization/selected accounting/drain/pending0 separately from global-loss/policy false, original XML1/0/0/0 and actual target/helper retirement. The generic native_exit_code is null; do not invent a standalone collector exit receipt.
- [ ] **Step 3: Record the verdict and source-change impact.** Tie Btrfs/host-init/fixture changes to accepted source checks and the configured228-task build. An evidence omission is a review finding, not permission to rerun or widen workload/claim. Preserve all historical failures/unknown owners.
- [ ] **Step 4: Seal the review packet.** Hash the new report and add the exact dated result to the public native record. No Go/Gradle/BPF/collector invocation. A distinct reviewer is needed only for the independent gate, not repetitive routine approval.

**Gate:** Independent review complete or an exact remaining finding; the actual successful fixture is not relabeled unexecuted.

### Task 2: Publish the exact already-reviewed storage contribution

**Files:** S exact33paths in `A/storage-source-sha256.txt`; read `A/{publication-scope-selection.json,storage-review-full.diff}` and prior final source review. Create `Q/release/` records and `P/storage-release-report.md`. No default source edit planned.

**Interfaces:** Consumes full33diff SHA256 `b7e1420fcb2035dc493118f94024ff7ab32dc3038faa7631c5763bca2af0aecd` and accepted corrections. Produces a reviewed actual canonical storage commit `STORAGE_REVISION`, publication/review URL and server-source identity. A local commit, branch name or bridge v0.2.0 is not that release.

- [ ] **Step 1: Check exact current bytes, then review only drift.** Run from S:

```bash
mkdir -m 0700 "$Q/release"
sha256sum --check --strict "$A/storage-source-sha256.txt"
sha256sum "$A/storage-review-full.diff" > "$Q/release/package.sha256"
git status --short > "$Q/release/storage-status.txt"
git rev-parse HEAD > "$Q/release/storage-head.txt"
```

Mismatch means capture actual changed paths and their semantic delta, not reset the checkout or repeat the whole schema audit. Existing five correction tests and two-page docs review are completed. The source package contains two explicit topics: IgnoreRule generated synchronization and typed stacks. Recommend submitting the reviewed33unique paths together with both topics disclosed; if the maintainer requires a split, preserve the checked4+32−3=33 composition and test the actual intermediate before claiming it is releasable.

- [ ] **Step 2: Prepare one concrete publication action.** Discover the actual repository/target/base/maintainer route and assemble the exact patch, inventory, existing review/check receipts and exclusions. Never push the current raw branch: its history includes a tracked build handoff and mixed generated topics. No build/private evidence or unrelated work enters the submission. Use the orchestrator-managed scoped review path; do not start another writer/daemon or create a worktree. Obtain only the missing material publication selection once the action is reviewable; no repeated path/hash requests.
- [ ] **Step 3: Complete the selected submission/merge/publication path.** The publisher applies the exact accepted source through its scoped branch/PR, not a default-branch push. Record actual reviewed commit and remote availability; no invented version or broad `git add .`. If the canonical module cannot be published, leave this gate concretely blocked and continue only independent tasks.
- [ ] **Step 4: Reuse same-byte checks; rerun only invalidated source checks.** A changed storage correction first gets a discriminating regression at its existing owner. The owning commands are:

```bash
# From S, only when actual source/graph drift invalidates earlier receipts:
GOWORK=off "$GO" test -mod=readonly -race ./pkg/apis/softwarecomposition/... ./pkg/generated/openapi \
  ./pkg/registry/file/callstack ./pkg/registry/file \
  -run 'TestStackEvidence|TestFrameSymbol|TestContainerProfile(Create|LegacyStackEvidence|StackEvidence)|TestUnify|TestRealWorldCallStackEncoding|TestGobCallStackEncoding|TestIgnoreRule' -count=3 -timeout=10m
GOWORK=off "$GO" test -mod=readonly -race -tags=agentless_hotspot_integration ./tests \
  -run '^TestStackEvidence(Helpers|WireExamples)' -count=1 -v -timeout=5m
GOWORK=off "$GO" test -mod=readonly -tags=kubernetes_protomessage_one_more_release ./pkg/apis/softwarecomposition/v1beta1 \
  -run '^TestStackEvidenceGogoProtobuf$' -count=1 -v
GOWORK=off make test
```

No codegen or module tidy unless the actual correction needs it. Preserve all prior failures. Ordinary `make test` is not a clean full-repository race claim.

**Gate:** Actual reviewed remotely available storage commit with both-topic scope/exclusions accounted for, or a precise external publication blocker.

### Task 3: Pin the actual remote storage graph and build standalone consumers

**Files:** Modify N `go.mod`, `go.sum`, `docs/agentless-hotspot.md`; retain `Q/graph/` resolver/graph/binary/check receipts. Read existing `Makefile:17-18`, example CLIs and current module replacements.

**Interfaces:** Consumes Task2's actual full commit `STORAGE_REVISION`. Produces resolver Version/Sum/GoModSum/Origin, exact standalone graph and source/compiler/build identities for node-agent/run/verify. Bridge stays v0.2.0; node Gadget and collector graphs remain independent.

- [ ] **Step 1: Resolve actual bytes before editing.** Do not repeat the known v0.0.303 missing-schema failure. Module acquisition is part of the selected release/build scope; missing cache without acquisition scope is a concrete input blocker, not silent network/toolchain fallback.

```bash
# From N:
mkdir -m 0700 "$Q/graph"
mkdir -m 0700 "$Q/graph/bin"
: "${STORAGE_REVISION:?actual reviewed canonical storage commit from Task2}"
GOWORK=off "$GO" mod download -json "github.com/kubescape/storage@$STORAGE_REVISION" > "$Q/graph/storage-resolution.json"
STORAGE_VERSION=$(python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); assert "Error" not in r; print(r["Version"])' "$Q/graph/storage-resolution.json")
```

Verify Origin.Hash/source against the reviewed commit, inspect the returned read-only Dir for the typed Symbol/Evidence/full-Create contract, and retain Version/Sum/GoModSum. A downloadable old/pruning graph rejects. Do not change module-cache source or substitute an absolute local storage replacement.

- [ ] **Step 2: Make only the resolved require/sum edit.**

```bash
GOWORK=off "$GO" mod edit -require="github.com/kubescape/storage@$STORAGE_VERSION"
GOWORK=off "$GO" mod download "github.com/kubescape/storage@$STORAGE_VERSION"
git diff -- go.mod go.sum
GOWORK=off "$GO" list -m -mod=readonly -json all > "$Q/graph/modules.json"
```

Preserve existing unrelated replacements and bridge/Gadget pins; require no storage Replace in the released graph. Do not run broad `go mod tidy`.

- [ ] **Step 3: Build normal binaries to new owned outputs.**

```bash
PATH="$(dirname "$GO"):$PATH" GOWORK=off GOFLAGS=-mod=readonly make binary BINARY_NAME="$Q/graph/bin/node-agent"
GOWORK=off "$GO" build -mod=readonly -o "$Q/graph/bin/hotspot-run" ./examples/agentless-hotspot-sbob/cmd/run
GOWORK=off "$GO" build -mod=readonly -o "$Q/graph/bin/hotspot-verify" ./examples/agentless-hotspot-sbob/cmd/verify
"$GO" version > "$Q/graph/go-version.txt"
"$GO" version -m "$Q/graph/bin/node-agent" > "$Q/graph/node-buildinfo.txt"
sha256sum "$GO" "$Q/graph/bin/"* > "$Q/graph/binaries.sha256"
```

- [ ] **Step 4: Check the changed graph and review its delta.**

```bash
GOWORK=off "$GO" test -mod=readonly -race ./examples/agentless-hotspot-sbob/... -count=1 -timeout=30m
GOWORK=off "$GO" test -mod=readonly -race ./pkg/stackattribution/hotspot ./pkg/containerprofilemanager/v1 \
  ./pkg/objectcache/containerprofilecache ./pkg/config ./cmd \
  -run 'TestHotSpot|TestRuntimeController|TestAdmission|TestSupervisor|TestReady|TestAgentlessHotspot|TestNamespaceFilter|TestCalculateSHA256CallStackHash|TestLegacyPackagesDeleted' -count=1 -timeout=15m
GOWORK=off "$GO" vet ./examples/agentless-hotspot-sbob/...
git diff --check
```

Record real whole-command exits, including dependency/compile failures. Review only pin/sum/guidance changes; no redesign of the accepted source contribution. Submit scoped deltas through the review path, with commits only where actually selected.

**Gate:** Standalone released graph and callers build/check, not merely development go.work compilation.

### Task 4: Carry the proven kernel-symbol permission into the opt-in Pod

**Files:** N `examples/agentless-hotspot-sbob/runner/{run_linux.go,run_linux_test.go}`, example README and `docs/agentless-hotspot.md`. M [issue248](../../internals/backlog/issue-20261023-234809-248-carry-kernel-symbol-permission-into-the-opt-in-hot.md). No default deployment or CLI flag change.

**Interfaces:** Existing `runOwned(context.Context, RunOptions, runIO) (Result, error)`, `syntheticOwnedRun(t, false, "")` and `renderedAgent(t, f)` remain private/unchanged. Produces the same opt-in node-agent Pod with exact Add set BPF/PERFMON/SYS_ADMIN/SYS_PTRACE/SYS_RESOURCE/DAC_READ_SEARCH/**SYSLOG**, Drop ALL, privileged false, Pod UID/GID1000 and unchanged mounts/archive/config/baseline behavior. This request does not prove effective post-exec credentials.

- [ ] **Step 1: Add this behavioral test to the existing test file.** Its imports already include context/json/os/filepath/slices and corev1. It runs the real runner/rendering with the existing synthetic leaf transports, not a source-text scan or live Kubernetes call.

```go
func TestRunnerRequestsKernelSymbolCapabilityWithoutPrivilegedPod(t *testing.T) {
    f := syntheticOwnedRun(t, false, "")
    if _, err := runOwned(context.Background(), f.o, f.files); err != nil {
        t.Fatal(err)
    }
    c, _ := renderedAgent(t, f)
    sc := c.SecurityContext
    if sc == nil || sc.Capabilities == nil || sc.Privileged == nil || *sc.Privileged {
        t.Fatal("require explicit non-privileged opt-in container")
    }
    got := slices.Clone(sc.Capabilities.Add)
    want := []corev1.Capability{"BPF", "PERFMON", "SYS_ADMIN", "SYS_PTRACE", "SYS_RESOURCE", "DAC_READ_SEARCH", "SYSLOG"}
    slices.Sort(got)
    slices.Sort(want)
    if !slices.Equal(got, want) || !slices.Equal(sc.Capabilities.Drop, []corev1.Capability{"ALL"}) {
        t.Fatalf("unexpected capability grant: add=%v drop=%v", sc.Capabilities.Add, sc.Capabilities.Drop)
    }
    if sc.RunAsUser != nil && *sc.RunAsUser != 1000 || sc.RunAsGroup != nil && *sc.RunAsGroup != 1000 {
        t.Fatal("container overrides non-root identity")
    }
    data, err := os.ReadFile(filepath.Join(f.o.Output, "receipts/rendered-node-agent.json"))
    if err != nil {
        t.Fatal(err)
    }
    var pod corev1.Pod
    if err := json.Unmarshal(data, &pod); err != nil {
        t.Fatal(err)
    }
    psc := pod.Spec.SecurityContext
    if !pod.Spec.HostPID || psc == nil || psc.RunAsUser == nil || *psc.RunAsUser != 1000 || psc.RunAsGroup == nil || *psc.RunAsGroup != 1000 {
        t.Fatal("selected host-PID/non-root topology changed")
    }
}
```

- [ ] **Step 2: Run behavioral RED on the released graph.**

```bash
# From N:
GOWORK=off "$GO" test -mod=readonly ./examples/agentless-hotspot-sbob/runner \
  -run '^TestRunnerRequestsKernelSymbolCapabilityWithoutPrivilegedPod$' -count=1 -v -timeout=15m
```

Expected FAIL: actual rendered Add list has six capabilities and lacks SYSLOG. A compile/module failure is not behavioral RED; Task3 must be corrected first, not bypassed with a workspace.

- [ ] **Step 3: Append only SYSLOG at startAgent's existing capability literal.**

```go
Capabilities: &corev1.Capabilities{
    Drop: []corev1.Capability{"ALL"},
    Add: []corev1.Capability{"BPF", "PERFMON", "SYS_ADMIN", "SYS_PTRACE", "SYS_RESOURCE", "DAC_READ_SEARCH", "SYSLOG"},
},
```

Keep existing AllowPrivilegeEscalation, hostPID, UID/GID, `/root/tracers.tar`, baseline startup and volumes unchanged. No helper abstraction or splitting the large runner merely to add a capability. Explain SYSLOG's kernel-log/address access/control and kptr_restrict1 requirement in both existing guidance files; kptr_restrict2 or ineffective exec credentials still reject.

- [ ] **Step 4: GREEN and connected regression checks.**

```bash
gofmt -w examples/agentless-hotspot-sbob/runner/run_linux.go examples/agentless-hotspot-sbob/runner/run_linux_test.go
GOWORK=off "$GO" test -mod=readonly -race ./examples/agentless-hotspot-sbob/runner \
  -run 'TestRunnerRequestsKernelSymbolCapabilityWithoutPrivilegedPod|TestFixRound1ImageArchiveAndSelectedConfig|TestRunnerRejectsInputsBeforeClientConstruction' -count=1 -v -timeout=15m
GOWORK=off "$GO" test -mod=readonly -race ./examples/agentless-hotspot-sbob/runner ./examples/agentless-hotspot-sbob/cmd/run -count=1 -timeout=30m
GOWORK=off "$GO" vet ./examples/agentless-hotspot-sbob/runner ./examples/agentless-hotspot-sbob/cmd/run
mkdir -m 0700 "$Q/final-bin"
GOWORK=off "$GO" build -mod=readonly -o "$Q/final-bin/hotspot-run" ./examples/agentless-hotspot-sbob/cmd/run
git diff --check
```

Keep the unchanged Task3 node-agent/verifier binaries with their valid source-impact explanation, or rebuild them only if their compiled inputs changed. Select the final runner hash, not the pre-correction runner. Get scoped review of these files/issue248; no native run or capability grant is caused by these host tests.

**Gate:** Tested exact Pod request and final standalone runner. Real UID1000 post-exec capability/BPF/memory permission remains Task7's live fact, not a mock assertion.

### Task 5: Prepare fresh immutable images and the actual node/trust contract

**Files:** Reuse N `build/{Dockerfile,Dockerfile.hotspot}`, `configuration/ig-config.yaml`, workload recipe/source, `cmd/prepare`; S `build/Dockerfile`; N existing `verify/{bundle.go,profile.go}` and `runner/identity_linux.go`. Create only `Q/images/`, `P/environment-scope.md`, and actual original trust receipts. No new launcher/provisioning framework.

**Interfaces:** Consumes Tasks2–4 final source/graph/binaries and admitted asset4. Produces actual immutable node/storage/workload references, source/compiler/module/image custody, independent `verify.NodeOrigin` and `verify.BuildReceipt` originals. Existing `verify.ParseTrustInputs(originJSON, buildJSON []byte) (NodeOrigin, BuildReceipt, error)` stays unchanged.

- [ ] **Step 1: Discover usable normal inputs and prepare one concrete environment batch.** Inventory available cached pinned images, tools, module caches and ordinary owned test-node options; do not touch protected old resources. Prefer matching existing images. Select a dedicated direct-node Linux/containerd environment, not the retired kind/VM or an ambient production context. Record exact provisioning/build/load/node inspection/native/API/UID-cleanup actions, namespaces, ownership and limits together in environment-scope.md. No repeated approval for the routine substeps once that batch is selected; a missing actual node/image/ref remains a fact to discover, not an invented value.

The runner must execute directly on that selected node as UID1000, with initial-node `/proc`, unified `/sys/fs/cgroup`, local containerd socket, explicit kubeconfig/context and owned0700 evidence parent. A kind container/remote CRI socket cannot silently satisfy direct-node provenance. No current-context switch, remote shell launcher, root test execution or privilege fallback.

- [ ] **Step 2: Prepare only the verified asset members using the existing command.**

```bash
# From N; Q/images is exclusively created first:
mkdir -m 0700 "$Q/images"
ASSETS_DIR="$R/private/verified-observed-assets-4"
MANIFEST_SHA256=c118376f81f1fc8685bb873af17bc1a247e484953f4b7d7e9da6e20bd592d0fa
ASSET_CONTEXT="$Q/images/hotspot-context"
GOWORK=off "$GO" run -mod=readonly ./examples/agentless-hotspot-sbob/cmd/prepare \
  --assets="$ASSETS_DIR" --manifest-sha256="$MANIFEST_SHA256" --output="$ASSET_CONTEXT"
```

Verify all members and destination graph; never copy the private execution workspace as image context. A failed/partial preparation directory is not build input. The collector's Go1.26.0 and source-overlay identity remain separate from node/storage compilers.

- [ ] **Step 3: Build only missing exact-source images within that batch.** Prefer already matching images; otherwise copy each existing normal Dockerfile into Q/images and replace only the two FROM lines with actual reviewed digest references. N's exact originals are `FROM --platform=$BUILDPLATFORM golang:1.27-trixie AS builder` and `FROM gcr.io/distroless/static-debian13:latest`; S uses `golang:1.25-trixie` and `gcr.io/distroless/static-debian13:nonroot`. Preserve all RUN/copy/cwd/entrypoint commands. Use this guarded replacement, with each old-line/pin pair supplied from the stated recipe and actual selected immutable image:

```python
from pathlib import Path
import re

def pin_recipe(source: Path, destination: Path, replacements: list[tuple[str, str]]) -> None:
    text = source.read_text()
    for old, new in replacements:
        assert text.splitlines().count(old) == 1, old
        assert "\n" not in new and "\r" not in new
        image = new.split()[2] if new.startswith("FROM --platform=") else new.split()[1]
        assert re.fullmatch(r"[^\s@]+@sha256:[a-f0-9]{64}", image), image
        text = text.replace(old, new, 1)
    with destination.open("x") as output:
        output.write(text)
```

The reviewed environment record specifies actual pin values/tags; they are external inputs, not fabricated hashes. The old release plan's Task4 Step2 documents these same normal build recipes and provenance checks, not another source implementation. With cached inputs already provisioned:

```bash
# From N, only if normal node base is missing:
: "${NODE_BASE_LOCAL_TAG:?fresh owned local build label}"
docker buildx build --platform linux/amd64 --load --pull=false --network=none \
  --metadata-file "$Q/images/node-base-build.json" -f "$Q/images/node-base.Dockerfile" -t "$NODE_BASE_LOCAL_TAG" .
# From S, only if the matching released server image is missing:
: "${STORAGE_LOCAL_TAG:?fresh owned local build label}"
docker buildx build --platform linux/amd64 --load --pull=false --network=none \
  --metadata-file "$Q/images/storage-build.json" -f "$Q/images/storage.Dockerfile" -t "$STORAGE_LOCAL_TAG" .
# From N, after actual NODE_BASE_IMAGE/JVM_IMAGE manifest references are bound:
: "${NODE_BASE_IMAGE:?reviewed immutable built node base}"
: "${JVM_IMAGE:?reviewed immutable matching plain Temurin image}"
: "${NODE_LOCAL_TAG:?fresh owned local build label}"
: "${WORKLOAD_LOCAL_TAG:?fresh owned local build label}"
docker buildx build --platform linux/amd64 --load --pull=false --network=none \
  --metadata-file "$Q/images/node-build.json" --build-context "hotspot_assets=$ASSET_CONTEXT" \
  --build-arg "NODE_AGENT_IMAGE=$NODE_BASE_IMAGE" -f build/Dockerfile.hotspot -t "$NODE_LOCAL_TAG" .
docker buildx build --platform linux/amd64 --load --pull=false --network=none \
  --metadata-file "$Q/images/workload-build.json" --build-arg "JVM_IMAGE=$JVM_IMAGE" \
  -f examples/agentless-hotspot-sbob/workload/Dockerfile -t "$WORKLOAD_LOCAL_TAG" examples/agentless-hotspot-sbob/workload
```

Do not run `make gadgets`, auto-pull/install or enable network to hide a cache failure. Preserve actual build exits. Bind qualified manifest/index/config chains and actual node-runtime import IDs; a local tag, Docker config ID or tag-shaped inspect value is not a manifest identity. A builder unable to supply immutable runtime custody leaves this gate blocked, not permission to publish. Normal node `/root/tracers.tar` and baseline sensors remain prerequisites.

- [ ] **Step 4: Acquire original node/build inputs and bind them to actual images.** NodeOrigin schemaVersion1 has receiptID/context/nodeName/nodeUID/bootID/procfsRoot/cgroupRoot/initialPIDNamespace/procfsPIDNamespace/procfsMountID; roots are `/proc` and `/sys/fs/cgroup`. BuildReceipt schemaVersion1 has sources nodeAgent/storage/bridge/collector/workload (revision/sourceSHA256/diffSHA256/moduleSHA256), compilers nodeAgent/storage/collector/workload (version/sha256), nodeAgentImage/storageImage/workloadImages/assetsManifestSHA256/collectorSHA256/jvmBuildID/jvmSHA256/provider. Workload moduleSHA256 alone is optional. Retain <=64KiB originals and independent acquisition/build custody, not synthetic fixture JSON or self-declared equal hashes. Existing Run parses and binds them before effects; do not add a validator CLI.

**Gate:** Final images/runtime import provenance and real direct-node/input custody. Requested seven capabilities are not proof they survive UID1000 exec; no extra uncounted BPF/kallsyms probe is hidden in preparation.

### Task 6: Qualify the selected server's JSON/protobuf API before enablement

**Files:** Reuse S `tests/agentless_stack_roundtrip_test.go`; create actual API originals under the selected private receipt parent and `P/storage-api-report.md`. No schema/importer/test rewrite.

**Interfaces:** Consumes Task2 server-source release and Task5 actual selected server image/context. Produces full original JSON/protobuf Create/Get bodies, media/status proof, acquired object UIDs and checked UID-preconditioned Delete; this is not yet node-originated acceptance.

- [ ] **Step 1: Deploy only the selected matching server in the owned test environment.** Its served resource is `spdx.softwarecomposition.kubescape.io/v1beta1`; provision the gate's exact namespace `agentless-hotspot-test` under the environment batch. Preserve CA verification and APIService UID/custody; no TLS bypass, broad RBAC or ambient context. Old retired API success does not qualify this server.
- [ ] **Step 2: Invoke the existing tagged gate once with actual selected inputs.**

```bash
# From S, selected kubeconfig already on its intended context:
: "${KUBECONFIG:?explicit selected kubeconfig}"
: "${AGENTLESS_HOTSPOT_CONTEXT:?explicit selected context}"
: "${AGENTLESS_HOTSPOT_OUTPUT_DIR:?fresh owned receipt parent}"
KUBECONFIG="$KUBECONFIG" AGENTLESS_HOTSPOT_CONTEXT="$AGENTLESS_HOTSPOT_CONTEXT" \
AGENTLESS_HOTSPOT_OUTPUT_DIR="$AGENTLESS_HOTSPOT_OUTPUT_DIR" \
GOWORK=off "$GO" test -mod=readonly -tags=agentless_hotspot_integration ./tests \
  -run '^TestStackEvidenceAPIRoundTrip$' -count=1 -v
```

- [ ] **Step 3: Inspect originals and ownership, then review the server gate.** Require requested media, full persisted values in both Create/Get, nil/zero/max and unsigned precision, actual object identity, explicit false qualifiers and checked Delete. Keep original `*-request.body`, `*-response.body`, `*-media.json` and all failures. Missing Create UID/ambiguous acquisition or failed cleanup remains rejected/unknown; never delete by name or validate a remarshal of pruned input. No feature enablement before this gate passes.

**Gate:** Current selected server actually preserves both formats and retires known resources; historical API receipts are not substituted.

### Task 7: Run two-target persisted acceptance, recreation and classified controls

**Files:** Reuse final N runner/verifier, workload and real trust schemas. Create N `docs/agentless-hotspot-acceptance.md`, original node-local outputs and `P/live-acceptance-report.md`. No new fault flags, per-target image flags or remote inspector.

**Interfaces:** Existing `hotspotrunner.Run(ctx context.Context, options RunOptions) (Result, error)` consumes RunOptions: Kubeconfig/Context/Namespace/Node/NodeUID/NodeBootID/NodeImageDigest/StorageImageDigest/WorkloadImageDigest/AssetsDir/ProviderManifestSHA/RuntimeEndpoint/Output/NodeOriginFile/BuildReceiptFile. Produces Result ManifestPath/ProfilePath/EvidencePath and an original bundle accepted by `verify.VerifyBundle(root string) error`. Offline verification cannot override failed live execution.

- [ ] **Step 1: Admit the selected positive/recreation case batch.** Two invocations, each with a new workload namespace/output and two15s windows: four windows total, no implicit retry. Actual origin/build originals, source/images, non-root filesystem/archive access and case cleanup must be bound. Import the final binaries/assets/receipts to the selected node with checked hashes. Use its local0700 evidence parent, not a workstation path that merely has the same spelling. Both gated workloads must be acquired before agent startup; preserve baseline startup and current-membership replay. Qualify real post-exec agent/collector capabilities from original status/native behavior; if SYSLOG or another required permission is ineffective, retain the exact failure rather than adding root/privileged topology.
- [ ] **Step 2: Run the existing CLI directly on that node as UID1000.** Every variable below is an actual Task5/6 selection, not a default guessed by the plan. `HOTSPOT_RUN` is Task4's final binary and `HOTSPOT_VERIFY` Task3's unchanged verifier, both verified after import.

```bash
: "${HOTSPOT_RUN:?verified final node-local runner}"
: "${HOTSPOT_VERIFY:?verified node-local verifier}"
: "${ACCEPTANCE_KUBECONFIG:?selected node-local kubeconfig}"
: "${ACCEPTANCE_CONTEXT:?selected explicit context}"
: "${ACCEPTANCE_NAMESPACE:?fresh workload namespace}"
: "${ACCEPTANCE_NODE:?selected dedicated node}"
: "${ACCEPTANCE_NODE_UID:?actual Node UID}"
: "${ACCEPTANCE_BOOT_ID:?actual boot ID}"
: "${ACCEPTANCE_NODE_IMAGE:?qualified immutable node image}"
: "${ACCEPTANCE_STORAGE_IMAGE:?same-selected-server immutable image}"
: "${ACCEPTANCE_WORKLOAD_IMAGE:?qualified plain JVM image}"
: "${ACCEPTANCE_ASSETS:?verified read-only node-local asset directory}"
: "${ACCEPTANCE_MANIFEST_SHA256:?trusted manifest digest}"
: "${ACCEPTANCE_RUNTIME_ENDPOINT:?selected local containerd socket path}"
: "${ACCEPTANCE_NODE_ORIGIN:?original independent origin receipt}"
: "${ACCEPTANCE_BUILD_RECEIPT:?original independent build receipt}"
: "${ACCEPTANCE_OUTPUT:?new absolute output under owned0700 node parent}"
"$HOTSPOT_RUN" --kubeconfig="$ACCEPTANCE_KUBECONFIG" --context="$ACCEPTANCE_CONTEXT" \
  --namespace="$ACCEPTANCE_NAMESPACE" --node="$ACCEPTANCE_NODE" --node-uid="$ACCEPTANCE_NODE_UID" \
  --node-boot-id="$ACCEPTANCE_BOOT_ID" --node-agent-image="$ACCEPTANCE_NODE_IMAGE" \
  --storage-image="$ACCEPTANCE_STORAGE_IMAGE" --workload-image="$ACCEPTANCE_WORKLOAD_IMAGE" \
  --assets="$ACCEPTANCE_ASSETS" --manifest-sha256="$ACCEPTANCE_MANIFEST_SHA256" \
  --runtime-endpoint="$ACCEPTANCE_RUNTIME_ENDPOINT" --node-origin="$ACCEPTANCE_NODE_ORIGIN" \
  --build-receipt="$ACCEPTANCE_BUILD_RECEIPT" --output="$ACCEPTANCE_OUTPUT"
"$HOTSPOT_VERIFY" --bundle "$ACCEPTANCE_OUTPUT"
```

Capture the runner's terminal status independently; if nonzero, keep the directory/error/owners and do not treat the subsequent offline verifier as acceptance. The runner exclusively creates its output; do not precreate that child directory.

- [ ] **Step 3: Inspect persisted positive evidence and retire owned resources.** Per container:400marked pairs, >=360useful correct routes, zero wrong/swapped TIDs/routes/order, full typed frame order/locations/BCI/unsigned values/CallIDs and explicit false qualifiers. Require raw JSON/protobuf same-revision readback, original desired/API/CRI digest custody, overlapping namespace PID1 with distinct host/container/profile identities, both windows before slow readback, and independent source TSV/worker snapshots. Preserve raw/capture/ready/status/API/CRI/media/retirement and every first failure. Actual known Pod/profile/Namespace/control/RBAC/config UIDs and all native owners must retire; pending/unknown is not accepted. Do not delete by name or mutate shared resources.
- [ ] **Step 4: Run recreation only after first-run owners are checked retired.** Use the same complete command above with a new actual namespace/output and newly acquired identities, no reused ready/session/capture/CallID authority. Compare final bundles: different Pod/container/profile UIDs and process births, legitimate overlap in namespace PIDs, no stale evidence crossing incarnations. This is explicit recreation, not a new automatic restart feature.
- [ ] **Step 5: Classify failure/control proof honestly on the final released graph.** Rerun invalidated host/disposable-child controls, not historical native matrices. Exact existing controls include:

```bash
# From N; host/disposable-child evidence only:
GOWORK=off "$GO" test -mod=readonly -race ./cmd ./pkg/stackattribution/hotspot \
  -run 'TestAgentlessHotspotDisabledHasNoConstructionOrEffects|TestRuntimeControllerDisabledDoesNoIOOrProbe|TestRuntimeControllerStorageDenialPrecedesProbesAndChildren|TestAdmissionReportsStorageRejectionWithoutEffects|TestSupervisorRejectsUnverifiedAssetsWithoutStartingChild|TestRuntimeControllerPermissionFailureRetainsCauseAndStartsNoChild|TestRuntimeControllerRemovalCancelsActualChildAndNeverPublishes|TestRuntimeControllerQueueOverflowRetiresItsOnlyActiveChild|TestSupervisorRawBudgetRequestsStopAndDoesNotPublishDiscardedCapture|TestSupervisorRetainsRealHeldPipeOwnerUntilRetry|TestReadyCallbackRetainsPendingReaderUntilExplicitCleanup' \
  -count=1 -timeout=15m
```

The result matrix has separate rows for disabled/baseline, unsupported/pruning storage, corrupt assets, read denial, target exit/exec, queue/quota pressure, held reader/child/callback, compiled churn/unload and unknown cleanup. Each row records exact source/binary, original receipt, evidence level (host/disposable child/native/live) and accepted/rejected/blocked outcome. Reuse applicable prior provider controls only after source-impact review; host fakes are never live Kubernetes proof. A required live destructive case without an existing exact procedure/owned resource scope remains BLOCKED, not a made-up CLI mode. Do not expand this plan into a fault framework.

Optional source-new-route proof is **not** part of the four-window batch: only if explicitly selected, make a copied workload context changing exactly `routeA` to call new `routeAAdded`, whose body calls `leaf`, preserve400/4/20/ordinary JVM flags, build a distinct immutable image/original receipt and run a third new invocation (two additional15s windows). Require original raw **and persisted** routeA frames in leaf→routeAAdded→routeA order. No source change/inlining suppression or fabricated expected frame merely to pass.

**Gate:** Positive and recreation persisted results plus required controls/actual retirement independently reviewed, or exact rejected/blocked lanes. Do not call partial positive proof whole-D acceptance.

### Task 8: Close quality/review exits and publish the final lane-specific handoff

**Files:** Create M `docs/internals/reviews/2026-10-24-hotspot-persisted-profile-acceptance.md`; finish N acceptance guidance and only affected backlog912/248/index. Read separate maintenance issues528/752 and original source review's one nonblocking coordinator Minor. No arbitrary lifecycle refactor or CI source change.

**Interfaces:** Consumes Task1 independent native review, actual storage publication/pins, Task4 source review, Task6 server gate and Task7 outcomes. Produces separate source/native/persisted-live/quality/release/custody decisions, not one boolean erasing a failed lane.

- [ ] **Step 1: Run only missing current-revision quality gates.** Preserve original attempt4 XML/scratch first. Existing M228-task build remains valid while no M executable/build input changes; N/storage changes do not justify rerunning it. Separately reproduce issue528's exact test-lint gate once if final quality is selected:

```bash
# From M; native observed/refusal opt-ins must be absent/false:
./gradlew --offline :profiler:detektTest
```

Retain actual current diagnostics alongside the historical16 findings; do not assume the count, suppress rules or alter baselines. If still failing, quality/release stays blocked with the actual owners and a separately scoped maintenance work package; unrelated10fixture corrections are not secretly bundled into HotSpot integration. Issue752's original retirement failures likewise remain open unless their real contract is independently repaired. If a selected executable/build correction invalidates M's gate, run `./gradlew --offline build` once after its focused checks with HotSpot opt-ins disabled; never consume another native grant accidentally.

N's existing `.github/workflows/go-basic-tests.yaml` includes Gadget preparation and privileged Go testing. Host race/vet is not that entire gate. Use the actual selected compatible CI environment with preprovisioned immutable inputs/approved authority, or record its missing prerequisite; do not run workflow download/`make gadgets`/sudo actions merely by copying YAML. Reuse S same-source `make test`; rerun only if its code/graph changed. No speculative new native CI workflow.

- [ ] **Step 2: Review only new deltas/results.** Pin/sum/guidance changes, runner/test correction, actual image/API/live outcomes and any separate maintenance package each get their owning review. Completed collector/bridge/storage/26-path implementations are connected evidence, not new broad audit targets. Keep required finding/control/unknown-owner blockers explicit.
- [ ] **Step 3: Validate docs/backlog and assemble exact submission scope.**

```bash
# From M:
git diff --check
./scripts/adkw check-backlog
# Independently from N and S for their task-owned deltas:
git diff --check
```

Check new local references and source/image/runtime identities; preserve old receipts and unrelated work. Submit scoped path inventories/diffs through the orchestrator review path. Do not stage whole dirty checkouts, push the default branch, auto-merge or discard artifacts. Source commits are appropriate review checkpoints only when that task's commit/submission action is selected; they are not an instruction to commit user work on every step.

- [ ] **Step 4: Write the final decision table and close issues only on their conditions.** Rows: native fixture/review; storage source/publication; standalone released consumer graph; opt-in security request/effective runtime contract; current-server API; persisted positive; recreation; controls with evidence levels; N/M/S quality; production-release decision; historical owners/strict-eight. Include actual exit codes, original receipt hashes, sampled/unavailable/loss counts and every remaining owner. Mark NOT_RUN with null exit, not success. Issue248 needs the tested source request and live effective contract; issue912 needs the real integrated release/persistence conditions. Never close issue528/752 from unrelated green tests.

**Gate:** Honest independently reviewed persisted-profile integration result and exact release readiness/blockers. No production rollout, policy admission, historical strict repair or unpublished change is implied.

## Self-review / requirement coverage

| Requirement | Plan coverage |
|---|---|
| Preserve accepted native run; independent review without replay | Task1, baseline, all consumed-grant constraints |
| Reuse completed source reviews, corrected33storage scope and Topic composition | Task2; final source review resolves earlier pending-doc wording |
| Actual remote storage graph, no local replace/guessed release | Tasks2–3 |
| Carry kernel-symbol permission into actual opt-in deployment | Task4 behavioral RED/GREEN, Task5/7 effective-credential qualification |
| Independent source/compiler/manifest/CRI/node/procfs origin | Task5 existing receipt/parser contracts and exact runtime input guards |
| Matching server JSON/protobuf, explicit false/numeric fidelity and acquired UID cleanup | Task6 |
| Two containers, namespace overlap, recreation, real saved profile and control evidence | Task7; no false host-to-live promotion |
| Separate selected accounting/drain/global completeness/policy | Global constraints, native baseline and Tasks1/6–8 |
| Maintenance, reviewed commits, quality/CI and external publication honesty | Tasks2/8; no default-branch pushes or implicit permission fallback |

Planning self-review checked the governing design, named interfaces/CLIs and existing fixtures. Validation passed: syntax checks for17shell blocks, twoGo fragments and onePython block;32test names/prefixes against current source;260local links across this plan, issue248 and the backlog index; verbatim design constraints; guarded recipe transformation, unpinned-input rejection and exclusive destination creation; `git diff --check`; and `./scripts/adkw check-backlog`. These checks did not execute any planned build, source test, image, native, cluster or publication action.

Unknown future release/image/node values above are explicit runtime facts with named acquisition gates, not sample identifiers that may be substituted. Context7 documentation lookup was quota-limited; the pinned existing Go/Kubernetes types and CLI source were inspected instead. No current-version API claim or real execution result is invented by the plan.

## Execution handoff

Plan-only deliverable: this document plus the scoped issue248/index entry discovered during planning. No source fix, dependency edit, test, image/native/cluster action, staging or publication has run under this plan.

Choose **Inline Execution** using `executing-plans` for one current-checkout writer with task checkpoints, or **Subagent-Driven** using `subagent-driven-development` for fresh per-task implementers/reviewers after explicit delegation selection. Neither choice selects worktrees or unknown external publication/production actions. Within the chosen execution scope, perform routine preparation/tests/cleanup without repeatedly asking to continue; report a concrete new boundary only when it materially changes scope, risk or an irreversible action.
