---
document_type: execution_plan
base_revision: d963c7941f5bd493751109324e6eba41f7d2b5af
status: p0_approved_blocked_on_writable_storage_provisioning
---

# Kubescape Typed Stack Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. This subproject is independent of A's runtime work; do not edit a Go module cache or assume authority to publish a storage release.

**Goal:** Preserve typed Java/native symbol information and observational provenance through real Kubescape API, protobuf, aggregation and file storage.

**Architecture:** Add optional symbol/evidence fields to existing frame/stack types, retaining legacy fields and nil behavior. Regenerate every actual serving format and fix aggregation to retain typed frame values. Coordinate server/client rollout before enabling the collector feature.

**Tech Stack:** Existing storage v0.0.303 API, Go>=1.25.8, Kubernetes code-generator0.35, existing gogo protobuf/OpenAPI/file-storage test infrastructure.

**Spec:** [Governing design, B](../specs/2026-10-18-kubescape-agentless-hotspot-design.md#b-additive-storage-contract).

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

## Setup and file responsibilities

S is `/home/leanid/Documents/code/java/stack-attribution/storage`; it does not exist
at planning time. After operator provisioning approval, create a normal checkout
there at d963c7941f5bd493751109324e6eba41f7d2b5af. Do not create/use a worktree
without separate authorization. Read its actual instructions before editing.

|Path relative to S|Responsibility|
|---|---|
|`pkg/apis/softwarecomposition/{types.go,v1beta1/types.go}`|Internal and served optional types|
|`pkg/apis/softwarecomposition/v1beta1/stack_evidence_test.go`|JSON/protobuf/deep-copy/legacy round trips|
|`pkg/apis/softwarecomposition/*generated*`, `v1beta1/*generated*`|Actual generated conversions/deepcopies/protobuf/model names|
|`pkg/generated/{openapi,applyconfiguration}`|Published schema and apply builders|
|`pkg/registry/file/callstack/{callstack.go,callstack_test.go}`|Typed value-preserving aggregation, no key-to-frame reconstruction|
|`pkg/registry/file/containerprofile_stack_evidence_test.go`|Real storage/conversion/SMD regression|
|`hack/update-codegen.sh`|Only necessary repeatable generation changes; preserve existing headers fix-up|
|`docs/agentless-stack-evidence.md`|Version/rollout/observational-claim contract|
|`tests/agentless_stack_roundtrip_test.go`|Explicit real API JSON/protobuf Create/Get, no silent prerequisite skip|

Generated files are outputs, not the editing authority. The current storage
aggregator `parsePath` reconstructs only FileID/Lineno and drops FrameType; a new
Symbol field would also disappear. This must be fixed as part of the preservation
contract, not hidden by a JSON-only schema test.

### Task 1: Add the optional API types with genuine serialization regressions

**Files:** Both API types files and new versioned test.
**Interfaces:** `FrameSymbol`, `CallStackEvidence`, `StackFrame.Symbol` field4,
`IdentifiedCallStack.Evidence` field3. Internal structs match served semantics.

- [ ] **Step1: Add failing JSON/protobuf tests** using the exact new fields. Keep
  nil legacy objects and optional source-location pointer mutation cases:

```go
func TestStackEvidenceSurvivesProtobuf(t *testing.T) {
    line, bci, addr := uint32(19), uint32(7), "18446744073709551615"
    want := &ContainerProfile{Spec: ContainerProfileSpec{
        IdentifiedCallStacks: []IdentifiedCallStack{{CallID: "observed-stack-a",
            Evidence: &CallStackEvidence{SchemaVersion: 1, Provider: "otel-ebpf-profiler-via-gadget",
                RuntimeProfile: "otel_hotspot25_observed_v1", EventScope: "openat_handler_entry",
                IdentityScope: "otel-observed-provider-frames/v1", PolicyEligibility: false,
                GlobalCollectionComplete: false},
            CallStack: CallStack{Root: CallStackNode{Frame: StackFrame{FrameType: 6,
                Symbol: &FrameSymbol{SchemaVersion: 1, Provider: "otel-ebpf-profiler-via-gadget",
                    FunctionName: "void Fixture.routeA(java.lang.String)", SourceFile: "Fixture.java",
                    SourceLine: &line, BytecodeIndex: &bci, NativeAddress: &addr,
                    RawPosition: "9007199254740993", PositionDomain: "provider_hotspot_bci_or_fallback"}}}},
        }},
    }}
    raw, err := proto.Marshal(want)
    if err != nil { t.Fatal(err) }
    got := &ContainerProfile{}
    if err := proto.Unmarshal(raw, got); err != nil { t.Fatal(err) }
    if !reflect.DeepEqual(want, got) { t.Fatalf("protobuf lost typed evidence: %#v", got) }
    clone := got.DeepCopy()
    *clone.Spec.IdentifiedCallStacks[0].CallStack.Root.Frame.Symbol.SourceLine = 22
    if *got.Spec.IdentifiedCallStacks[0].CallStack.Root.Frame.Symbol.SourceLine != 19 {
        t.Fatal("symbol pointer aliases after DeepCopy")
    }
}
```

Use existing gogo `proto` and standard reflect/testing imports; no new framework.
FrameType6 is fixture data here, not an unverified statement about a future provider
enum. Consumer code preserves the provider's actual numeric type.

- [ ] **Step2: Run RED before types/generation.**

```bash
cd "$S"
go test ./pkg/apis/softwarecomposition/v1beta1 -run '^TestStackEvidence' -count=1
```

Undefined fields are compile RED. After adding types, a protobuf-loss assertion
must still fail until marshallers are regenerated; retain that behavioral RED.

- [ ] **Step3: Add concrete versioned fields** and equivalent internal fields.

```go
type FrameSymbol struct {
    SchemaVersion uint32 `json:"schemaVersion" protobuf:"varint,1,opt,name=schemaVersion"`
    Provider string `json:"provider" protobuf:"bytes,2,opt,name=provider"`
    FunctionName string `json:"functionName,omitempty" protobuf:"bytes,3,opt,name=functionName"`
    SourceFile string `json:"sourceFile,omitempty" protobuf:"bytes,4,opt,name=sourceFile"`
    SourceLine *uint32 `json:"sourceLine,omitempty" protobuf:"varint,5,opt,name=sourceLine"`
    SourceColumn *uint32 `json:"sourceColumn,omitempty" protobuf:"varint,6,opt,name=sourceColumn"`
    NativeAddress *string `json:"nativeAddress,omitempty" protobuf:"bytes,7,opt,name=nativeAddress"`
    BytecodeIndex *uint32 `json:"bytecodeIndex,omitempty" protobuf:"varint,8,opt,name=bytecodeIndex"`
    RawPosition string `json:"rawPosition,omitempty" protobuf:"bytes,9,opt,name=rawPosition"`
    PositionDomain string `json:"positionDomain,omitempty" protobuf:"bytes,10,opt,name=positionDomain"`
    ScopeLookup string `json:"scopeLookup,omitempty" protobuf:"bytes,11,opt,name=scopeLookup"`
    ScopeWalk string `json:"scopeWalk,omitempty" protobuf:"bytes,12,opt,name=scopeWalk"`
    BCIState string `json:"bciState,omitempty" protobuf:"bytes,13,opt,name=bciState"`
    QualityDiagnostic string `json:"qualityDiagnostic,omitempty" protobuf:"bytes,14,opt,name=qualityDiagnostic"`
}
type CallStackEvidence struct {
    SchemaVersion uint32 `json:"schemaVersion" protobuf:"varint,1,opt,name=schemaVersion"`
    Provider string `json:"provider" protobuf:"bytes,2,opt,name=provider"`
    RuntimeProfile string `json:"runtimeProfile" protobuf:"bytes,3,opt,name=runtimeProfile"`
    EventScope string `json:"eventScope" protobuf:"bytes,4,opt,name=eventScope"`
    IdentityScope string `json:"identityScope" protobuf:"bytes,5,opt,name=identityScope"`
    PolicyEligibility bool `json:"policyEligibility" protobuf:"varint,6,opt,name=policyEligibility"`
    GlobalCollectionComplete bool `json:"globalCollectionComplete" protobuf:"varint,7,opt,name=globalCollectionComplete"`
    JVMBuildID string `json:"jvmBuildID,omitempty" protobuf:"bytes,8,opt,name=jvmBuildID"`
    JVMSHA256 string `json:"jvmSHA256,omitempty" protobuf:"bytes,9,opt,name=jvmSHA256"`
    GadgetRevision string `json:"gadgetRevision,omitempty" protobuf:"bytes,10,opt,name=gadgetRevision"`
    ProfilerRevision string `json:"profilerRevision,omitempty" protobuf:"bytes,11,opt,name=profilerRevision"`
    PatchSHA256 string `json:"patchSHA256,omitempty" protobuf:"bytes,12,opt,name=patchSHA256"`
}
```

Add pointer fields with `omitempty`: `Symbol *FrameSymbol` bytes4 to StackFrame,
`Evidence *CallStackEvidence` bytes3 to IdentifiedCallStack. Preserve old names/
numbers, no required new legacy fields. Keep both API representations in sync.

- [ ] **Step4: Regenerate real serving code and run GREEN.**

```bash
cd "$S"
CODEGEN_PKG="$(go list -m -f '{{.Dir}}' k8s.io/code-generator)" bash hack/update-codegen.sh
go test ./pkg/apis/softwarecomposition/... ./pkg/generated/openapi -count=1
```

The current script warns that protobuf is not generated. Do not accept this as
complete: with reviewed tools `protoc`, `go-to-protobuf` from code-generator0.35
and existing `protoc-gen-gogo`, create an owned GOPATH/include tree. Symlink S at
`$GOPATH/src/github.com/kubescape/storage`; link dependency inputs read-only at
their unversioned module names. Run the script's documented invocation exactly:

```bash
go-to-protobuf \
  --packages="-k8s.io/api/core/v1,github.com/kubescape/storage/pkg/apis/softwarecomposition/v1beta1" \
  --apimachinery-packages="-k8s.io/apimachinery/pkg/util/intstr,-k8s.io/apimachinery/pkg/api/resource,-k8s.io/apimachinery/pkg/runtime/schema,-k8s.io/apimachinery/pkg/runtime,-k8s.io/apimachinery/pkg/apis/meta/v1,-k8s.io/apimachinery/pkg/apis/meta/v1beta1" \
  --output-dir="$GOPATH/src" \
  --go-header-file="$S/hack/boilerplate.go.txt" \
  --proto-import="$GOPATH/src"
```

GOPATH is an owned `mktemp -d "$S/build/hotspot-codegen.XXXXXX"` directory;
dependencies resolve via `go list -m -f '{{.Dir}}'`, never edits to their caches.
Record tool versions/commands. Missing generators block, not skip, this step.
Review `generated.proto`, `generated.pb.go`, deepcopy/conversion/apply/OpenAPI
outputs; preserve the existing HTTP-header schema fix-up.

- [ ] **Step5: Commit API/generation only after focused tests pass.**

```bash
git diff --check
git add pkg/apis/softwarecomposition pkg/generated
git commit -m "feat(storage): preserve typed stack symbols and observational evidence"
```

Inspect staged paths to exclude unrelated generation/work; follow S's own gates.

### Task 2: Preserve typed data through aggregation and real storage

**Files:** Named callstack files and new file-storage test. Also inspect actual
containerprofile processors/converters for metadata-copy constructors.
**Interfaces:** Existing `UnifyIdentifiedCallStacks` signature stays; private
frame/path/group keys become symbol/evidence-aware. All pointer data is owned.

- [ ] **Step1: Add a regression that currently loses FrameType/new symbols.**

```go
func TestUnifyPreservesDistinctObservedFrames(t *testing.T) {
    a := types.StackFrame{FrameType: 6, Symbol: &types.FrameSymbol{SchemaVersion: 1, FunctionName: "routeA"}}
    b := types.StackFrame{FrameType: 6, Symbol: &types.FrameSymbol{SchemaVersion: 1, FunctionName: "routeB"}}
    input := []types.IdentifiedCallStack{{CallID: "observed", Evidence: &types.CallStackEvidence{SchemaVersion: 1},
        CallStack: types.CallStack{Root: types.CallStackNode{Children: []types.CallStackNode{
            {Frame: a}, {Frame: b},
        }}}}}
    got := UnifyIdentifiedCallStacks(input)
    if len(got) != 1 || got[0].Evidence == nil || len(got[0].CallStack.Root.Children) != 2 {
        t.Fatalf("typed paths collapsed: %#v", got)
    }
    names := map[string]bool{}
    for _, n := range got[0].CallStack.Root.Children {
        if n.Frame.Symbol == nil || n.Frame.FrameType != 6 { t.Fatal("typed frame lost") }
        names[n.Frame.Symbol.FunctionName] = true
    }
    if !names["routeA"] || !names["routeB"] { t.Fatal(names) }
}
```

Add same-CallID/different-evidence and caller-mutation cases. Conflicting evidence
must not be arbitrarily selected; keep distinct evidence groups without merging
them into a false shared claim. Legacy nil-evidence grouping remains unchanged.

- [ ] **Step2: Run behavioral RED.**

```bash
go test ./pkg/registry/file/callstack -run '^TestUnifyPreserves' -count=1
```

- [ ] **Step3: Reconstruct from owned values, not split string keys.** Replace
  the private trie/key-parser round trip with a typed map of canonical path key
  to owned frame slice; current code uses no prefix-query property. Delete
  parsePath's lossy reconstruction. Preserve FrameType/Symbol and Evidence;
  treat only FileID/Lineno-empty **and Symbol-nil** as a synthetic root.

```go
func frameKey(f types.StackFrame) string {
    if f.Symbol == nil { return f.FileID + ":" + f.Lineno }
    b, err := json.Marshal(f)
    if err != nil { panic(err) } // Closed scalar/pointer API type; marshaling has no failure input.
    h := sha256.Sum256(b)
    return "symbol-v1:" + hex.EncodeToString(h[:])
}
```

Use the generated DeepCopy of FrameSymbol/Evidence when building output; never
use pointer identity as a semantic key. Keep legacy sort/key behavior for nil
symbols and preserve all existing benchmark/callstack tests. Add no public merge
API or generalized graph layer.

- [ ] **Step4: Add real storage/conversion/OpenAPI tests.** In
  `containerprofile_stack_evidence_test.go`, reuse `newGuardTestStorage` from the
  existing subtype round-trip tests: Create/Get an internal ContainerProfile with
  the two symbol paths; convert internal→v1beta1→internal through the real Scheme;
  compare all fields/deepcopies. Drive published OpenAPI through the existing
  schemaconv/SMD pattern, including false policy/global flags and absent locations.
  Assert both `StackFrame.symbol` and `IdentifiedCallStack.evidence` schema refs.

```bash
go test ./pkg/registry/file/callstack ./pkg/registry/file \
  -run 'TestUnify|TestStackEvidence|TestContainerProfile.*Stack|TestHTTPEndpointHeaders_SMDConversion' -count=1
```

- [ ] **Step5: Full owning gate, commit and review.**

```bash
make test
git diff --check
git add pkg/registry/file/callstack pkg/registry/file/containerprofile_stack_evidence_test.go
git commit -m "fix(storage): retain typed stack values during profile aggregation"
```

Do not claim actual deployed server acceptance from these in-process tests; D
will query the API. Avoid running destructive `_tmp` codegen verification over
operator files: inspect/preserve `_tmp` first if using that script.

### Task 3: Produce a coordinated schema/version handoff

**Files:** Storage documentation, build receipt; generated changes only if a
focused test reveals a real omission. **Interfaces:** master-plan schema handoff.

- [ ] **Step1:** Write the optional field/version/false-policy contract and old
  reader limitations in `docs/agentless-stack-evidence.md`. Include exact JSON and
  protobuf examples produced by tests, not manually relabeled legacy fields.
- [ ] **Step2:** Execute a real API JSON and protobuf Create/Get with a locally
  built reviewed server on the approved integration environment; save both request
  and response bytes and verify typed metadata persists. Fail on field pruning.

Implement `TestStackEvidenceAPIRoundTrip` in the named new tests file. Require
explicit KUBECONFIG and namespace `agentless-hotspot-test` already provisioned by
the approved test setup; missing prerequisites fail this opt-in gate, not skip it.
Use generated clientset/scheme, rest.CopyConfig and actual negotiation:

```go
for _, content := range []string{runtime.ContentTypeJSON, runtime.ContentTypeProtobuf} {
    rc := rest.CopyConfig(baseConfig)
    rc.ContentType, rc.AcceptContentTypes = content, content
    api, err := versioned.NewForConfig(rc)
    if err != nil { t.Fatal(err) }
    profiles := api.SpdxV1beta1().ContainerProfiles("agentless-hotspot-test")
    want := &v1beta1.ContainerProfile{ObjectMeta: metav1.ObjectMeta{
        GenerateName: "typed-stack-", Namespace: "agentless-hotspot-test",
        Annotations: map[string]string{"kubescape.io/managed-by": "User"},
    }, Spec: v1beta1.ContainerProfileSpec{Architectures: []string{"amd64"},
        IdentifiedCallStacks: []v1beta1.IdentifiedCallStack{{CallID: "typed-a",
            Evidence: &v1beta1.CallStackEvidence{SchemaVersion: 1, PolicyEligibility: false},
            CallStack: v1beta1.CallStack{Root: v1beta1.CallStackNode{Frame: v1beta1.StackFrame{
                Symbol: &v1beta1.FrameSymbol{SchemaVersion: 1, FunctionName: "routeA"},
            }}},
        }},
    }}
    created, err := profiles.Create(ctx, want, metav1.CreateOptions{})
    if err != nil { t.Fatal(err) }
    t.Cleanup(func() {
        cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
        defer cancel()
        if err := profiles.Delete(cleanup, created.Name, metav1.DeleteOptions{
            Preconditions: &metav1.Preconditions{UID: &created.UID},
        }); err != nil { t.Error(err) }
    })
    got, err := profiles.Get(ctx, created.Name, metav1.GetOptions{})
    if err != nil { t.Fatal(err) }
    if !reflect.DeepEqual(want.Spec.IdentifiedCallStacks, got.Spec.IdentifiedCallStacks) {
        t.Fatalf("%s API pruned typed symbols/evidence: %#v", content, got.Spec.IdentifiedCallStacks)
    }
}
```

`baseConfig` is clientcmd.BuildConfigFromFlags with the explicit KUBECONFIG and
approved context; `ctx` is an owned30s test context. The surrounding test must save
request/response bytes under its owned output directory and join cleanup. Imports
are existing generated versioned clientset, client-go/rest/clientcmd, metav1,
runtime, context, reflect, time, os/testing; no new dependency is needed.

```bash
go test ./tests -run '^TestStackEvidenceAPIRoundTrip$' -count=1
```
- [ ] **Step3:** Record actual storage commit/module version/image digest and
  check outputs in `build/hotspot-schema-handoff.json`. Module publication and
  deployment are operator-approved actions; do not invent a future pin or release.
- [ ] **Step4:** Self-review, targeted references/format, owning repository gate
  and exact documentation commit. C's capability probe must reject old storage
  before starting the optional collector; legacy node-agent behavior remains valid.
