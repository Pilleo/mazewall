---
document_type: execution_plan
base_revision: 8da8b4efcc0b72d9c4f06cf798e6daee0d9b1120
status: proposed_native_contract_requires_approval
---

# Production Collector Loss Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Native map/schema/pin changes require the explicit review gate in Task2.

**Goal:** Bind selected-event conservation to the actual collector/map/target owner and make missing all-source evidence explicit without promoting policy completeness.

**Architecture:** Preserve Aggregate/Evaluate, add immutable identity/finality binding around them, then collect actual counters only inside the existing detach/flush/join/sample/retire owner. Keep the optional diagnostic distinct from global loss and physical drain; replace caller-supplied inventory booleans with lane evidence that must validate its identity and equations.

**Tech Stack:** Existing Go1.26/Cilium eBPF, Gadget patch additions, BPF C, Kotlin raw-preserving parser/JUnit, pinned native build images; no dependencies.

**Spec:** `docs/plans/2026-10-17-hotspot-remaining-work-spec.md` R4 and `docs/plans/2026-10-17-hotspot-upstream-alignment-spec.md` B; `docs/plans/2026-10-05-hotspot-loss-accounting-evidence.md`; issue20261005-193140-042.

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

---

## File structure

Task1 is already committed and reviewed at `0bf71521`; do not reimplement it.
Task2 Step1's contract exists and is independently reviewed, but native source/
map/schema/pin edits remain unapproved. The corrected Step4 below replaces the
invalid prepare-time map binding; operator approval still precedes implementation.

|Action/file|Responsibility|
|---|---|
|Create canonical `profiler/hotspot-agentless/internal/accounting/binding.go` and `binding_test.go`|Exact session/PID/birth/map/CPU/finality proof around existing math|
|Modify canonical `gadgets/openat-stack/program.bpf.c:42-72,132`|32byte per-CPU selected producer counters, no event/ring layout change|
|Modify canonical `patches/gadget-additions/pkg/operators/event_finalization.go.txt`|Optional reviewed accounting target/diagnostic contract|
|Modify canonical `patches/gadget-additions/pkg/operators/ebpf/event_finalization_owner.go.txt`|Publish/copy the selected diagnostic under the existing owner mutex|
|Modify canonical `patches/gadget-additions/pkg/operators/ebpf/event_finalization_backend.go.txt:67-89` and `event_finalization_native.go.txt`|Validate/capture actual map ID and sample after real joins|
|Create canonical `patches/gadget-additions/pkg/operators/ebpf/selected_accounting_native.go.txt`|Immutable acquired-map receipt and exact final snapshot implementation|
|Create canonical `patches/gadget-additions/pkg/operators/ebpf/selected_accounting_native_kernel_test.go.txt`; modify `event_finalization_owner_test.go.txt`|Actual acquired-map replacement/pre-join refusal and immutable snapshot regression|
|Modify `patches/gadget-finalization.patch`|Add only needed ebpfInstance storage for immutable map binding|
|Modify `internal/capture/collector.go`, `internal/model/record.go`|Carry optional diagnostic through existing finalization snapshot, preserving errors|
|Create `internal/capture/selected_accounting.go` and `internal/accounting/binding_kernel_test.go`|Typed collector conversion and actual map-replacement regression|
|Create `internal/accounting/lanes.go` and `lanes_test.go`|Structured lane evidence; cannot construct global truth from flags|
|Modify canonical accounting kernel tests and corresponding Gadget addition tests|Actual map/cardinality/held-callback/pressure/overflow controls|
|Update exact mirrored native files under experiments/hotspot-agentless, accepted patch constants/tests/docs together|Explicit source-graph and version compatibility|
|Modify both native `internal/tooling/assets.go` and `internal/tooling/prepare_test.go` copies|Accept and assert the actual newly computed patch identity|
|Modify `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt`, `HotSpotProtocol.kt` and test `HotSpotAssetsTest.kt`, `HotSpotProtocolTest.kt`, `HotSpotChildFixture.kt`|Keep asset admission, live ready protocol and exact test provenance on the same pin|
|Modify `profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotPublicIntegrationTest.kt`|Require the real owned collector's selected accounting diagnostic and unchanged global refusal|

All native relative paths in the table are under `profiler/hotspot-agentless`.
Read profiler/AGENTS.md, canonical Makefile, accounting/{conservation,inventory}.go,
the four event-finalization addition files and collector.go's AfterReadout handoff.
Do not edit generated build/deps or widen existing event counters by guessing.

### Task 1: Identity-bound immutable counter proof

**Files:** Create `profiler/hotspot-agentless/internal/accounting/binding.go` and
`profiler/hotspot-agentless/internal/accounting/binding_test.go`.

**Interfaces:** Existing Aggregate/Evaluate stay unchanged. New
`BindAndEvaluate(expected Binding, snapshot Snapshot) (Counts, Proof, error)`;
Binding contains session, PID/birth, actual kernel map ID and possible CPU count.
Snapshot owns a copied CPUCounts slice, flags from actual owner and delivered counts.

- [ ] **Step1: Add `binding_test.go`:**

```go
package accounting
import "testing"
func TestWrongMapOrUnjoinedSnapshotCannotProveConservation(t *testing.T) {
    b:=Binding{Session:"s",PID:123,Birth:456,MapID:7,PossibleCPUs:2}
    s:=Snapshot{Binding:b,Rows:[]CPUCounts{{Attempted:3,Submitted:2,ReserveFailed:1},{}},Quiesced:true,Joined:true,Received:2,Terminals:2}
    _,proof,err:=BindAndEvaluate(b,s)
    if err!=nil||!proof.Complete||proof.Lost!=1 {t.Fatal(proof,err)}
    for _,kind:=range []string{"map","session","birth","cpu","join"} {
        bad:=s
        switch kind {case "map":bad.MapID++;case "session":bad.Session="other";case "birth":bad.Birth++;case "cpu":bad.Rows=bad.Rows[:1];case "join":bad.Joined=false}
        _,proof,err:=BindAndEvaluate(b,bad)
        if err==nil&&proof.Complete {t.Fatal("foreign/non-final snapshot accepted",kind)}
    }
}
```

- [ ] **Step2:** In canonical engine run `go test -race ./internal/accounting`;
expect missing Binding/Snapshot before implementation, not a capability skip.
- [ ] **Step3: Implement `binding.go`:**

```go
package accounting
import "fmt"
type Binding struct { Session string; PID uint32; Birth uint64; MapID uint32; PossibleCPUs int }
type Snapshot struct {
    Binding
    Rows []CPUCounts
    Quiesced,Joined bool
    Received,Terminals uint64
}
func BindAndEvaluate(expected Binding,s Snapshot)(Counts,Proof,error) {
    if expected.Session==""||expected.PID==0||expected.Birth==0||expected.MapID==0||expected.PossibleCPUs<=0||expected!=s.Binding {
        return Counts{},Proof{},fmt.Errorf("counter owner identity unknown or changed")
    }
    rows:=append([]CPUCounts(nil),s.Rows...)
    counts,err:=Aggregate(rows,expected.PossibleCPUs,true)
    if err!=nil {return Counts{},Proof{},err}
    counts.Quiesced,counts.Joined=s.Quiesced,s.Joined
    counts.Received,counts.Terminals=s.Received,s.Terminals
    return counts,Evaluate(counts),nil
}
```

Binding is internal Go proof identity, not a client-supplied authority. Production
code must obtain MapID from the actual acquired Cilium map and compare it again
at final read. PID/birth/session come from the admitted target and owner.
- [ ] **Step4:** Run canonical host/race accounting tests including existing
per-CPU contradictions/overflow. Commit exact two new files with
`feat(profiler): bind selected counters to owner identity`.

### Task 2: Actual owner map and reviewed optional diagnostic

**Files:** Modify the canonical BPF program, finalization operator/backend/owner
additions, gadget-finalization.patch, collector.go and model/record.go listed above;
create selected_accounting_native.go.txt, selected_accounting_native_kernel_test.go.txt
and capture/selected_accounting.go. Modify event_finalization_owner_test.go.txt and
the public integration test in the file map. Update exactly the mirrored files and nine pin-bearing source/test files
listed in the file map, after native-contract approval.

**Interfaces:** New optional `operators.SelectedAccountingTarget{Session string,
PID uint32, Birth uint64}` in the existing finalization addition. The actual owner
produces `SelectedAccountingObservation{Target, MapID uint32, PossibleCPUs int,
Rows []SelectedCPUCounts, Quiesced,Joined bool}` where
`SelectedCPUCounts{Attempted,Submitted,ReserveFailed,Overflow uint64}`. Collector
converts it to Task1 Snapshot, never trusting target-supplied map/flags.

- [ ] **Step1: Write and review the native contract** before edits: map name
selected_counts, PerCPUArray/key4/value32/entries1; no event ABI change; optional
diagnostic schema1; a missing selected map rejects the explicitly negotiated
accounting capability. Ordinary non-accounting capture keeps its partial semantics;
production bundles still follow exact updated provenance admission, including
rejection of the old patch pin. Selected proof never sets global completeness.
Record current/next accepted patch and image derivation via existing Go tools.
Require explicit operator approval for this map/schema/provenance change.
- [ ] **Step2: Add negative native controls** to the existing addition tests:
capture map Info.ID after acquisition/before publication, substitute another actual map at final sampling,
hold the event callback past its unchanged stop deadline, and omit one possible
CPU slot. Assert error/pending owner, no complete accounting and retained native
cause. The actual test code for identity substitution uses Task1's public contract:

```go
expected:=accounting.Binding{Session:"s",PID:123,Birth:456,MapID:originalID,PossibleCPUs:cpus}
snapshot:=accounting.Snapshot{Binding:expected,Rows:rows,Quiesced:true,Joined:true}
snapshot.MapID=replacementID
if _,proof,err:=accounting.BindAndEvaluate(expected,snapshot);err==nil&&proof.Complete {
    t.Fatal("replacement map certified the original producer")
}
```

Use this complete actual-map setup around that assertion in a new canonical
`internal/accounting/binding_kernel_test.go` (build tag mw_kernel, package accounting;
imports testing and github.com/cilium/ebpf). In this same package omit the
`accounting.` prefixes in the assertion above:

```go
func TestActualMapReplacementDoesNotCertifyOriginalOwner(t *testing.T) {
    makeMap:=func()*ebpf.Map {
        value,err:=ebpf.NewMap(&ebpf.MapSpec{Type:ebpf.PerCPUArray,KeySize:4,ValueSize:32,MaxEntries:1})
        if err!=nil {t.Fatal(err)}
        t.Cleanup(func(){if err:=value.Close();err!=nil {t.Error(err)}})
        return value
    }
    original,replacement:=makeMap(),makeMap()
    originalInfo,err:=original.Info();if err!=nil {t.Fatal(err)}
    replacementInfo,err:=replacement.Info();if err!=nil {t.Fatal(err)}
    originalID,ok:=originalInfo.ID();if !ok {t.Fatal("actual original map ID missing")}
    replacementID,ok:=replacementInfo.ID();if !ok {t.Fatal("actual replacement map ID missing")}
    cpus,err:=ebpf.PossibleCPU();if err!=nil {t.Fatal(err)}
    rows:=make([]CPUCounts,cpus);zero:=uint32(0)
    if err:=original.Lookup(&zero,&rows);err!=nil {t.Fatal(err)}
    expected:=Binding{Session:"s",PID:123,Birth:456,MapID:uint32(originalID),PossibleCPUs:cpus}
    snapshot:=Snapshot{Binding:expected,Rows:rows,Quiesced:true,Joined:true}
    snapshot.MapID=uint32(replacementID)
    if _,proof,err:=BindAndEvaluate(expected,snapshot);err==nil&&proof.Complete {t.Fatal("foreign actual map certified")}
}
```

These are actual acquired map IDs; literals remain target metadata controls, not
invented closeable receipts. The owning Gadget integration controls must also
substitute its collection map before final Sample and hold a real callback;
the pure binding test alone does not establish real owner ordering.

Create the new selected_accounting_native_kernel_test.go.txt in package ebpfoperator
with build tag mw_kernel and imports testing, Cilium ebpf and Gadget operators:

```go
func TestKernelSelectedAccountingKeepsAcquiredMapOwner(t *testing.T) {
    spec:=&ebpf.MapSpec{Type:ebpf.PerCPUArray,KeySize:4,ValueSize:32,MaxEntries:1}
    makeMap:=func()*ebpf.Map {
        value,err:=ebpf.NewMap(spec);if err!=nil {t.Fatal(err)}
        t.Cleanup(func(){if err:=value.Close();err!=nil {t.Error(err)}})
        return value
    }
    original,replacement:=makeMap(),makeMap()
    i:=eventFinalizationScopeFixture()
    i.collectionSpec.Maps["selected_counts"]=spec
    i.collection=&ebpf.Collection{Maps:map[string]*ebpf.Map{"selected_counts":original}}
    binding,err:=bindSelectedAccounting(i,&operators.SelectedAccountingTarget{Session:"s",PID:123,Birth:456})
    if err!=nil {t.Fatal(err)}
    i.selectedAccounting=binding
    i.eventFinalizer=newEventStopOwner(nativeEventStopBackend{instance:i})
    if _,err:=(nativeEventStopBackend{instance:i}).Sample();err==nil {
        t.Fatal("sample accepted before actual owner joins")
    }
    snapshot,_:=i.EventFinalization()
    if snapshot.SelectedAccounting!=nil {t.Fatal("pre-join diagnostic published")}
    i.collection.Maps["selected_counts"]=replacement
    if _,err:=binding.sampleAfterJoin();err==nil {t.Fatal("collection map substitution accepted")}
}
```

This control never asserts producer conservation from its fixture target metadata;
it proves only acquired-map identity/refusal. Add this independent host immutability
test to event_finalization_owner_test.go.txt; add the existing Gadget operators
import `github.com/inspektor-gadget/inspektor-gadget/pkg/operators`:

```go
func TestEventFinalizationSelectedRowsAreSnapshotOwned(t *testing.T) {
    owner:=newEventStopOwner(nil)
    owner.mu.Lock()
    owner.observation.SelectedAccounting=&operators.SelectedAccountingObservation{
        Rows:[]operators.SelectedCPUCounts{{Attempted:1,Submitted:1}},
    }
    owner.mu.Unlock()
    first:=owner.snapshot()
    first.SelectedAccounting.Rows[0].Attempted=99
    first.SelectedAccounting.MapID=99
    second:=owner.snapshot()
    if second.SelectedAccounting.Rows[0].Attempted!=1||second.SelectedAccounting.MapID!=0 {
        t.Fatal("caller mutated owned observation")
    }
}
```

Run these new controls red before Step3/4. Reuse the existing actual held-callback
test TestKernelEventFinalizationDetachesDrainsAndSamplesWithoutLaterEvent and host
TestEventFinalizationDeadlineNeverRetiresActiveCallback; do not duplicate their
native receiver lifecycle implementation.
- [ ] **Step3: Implement the map and three exact producer sites** in canonical BPF:

```c
struct selected_cpu_counts { __u64 attempted, submitted, reserve_failed, overflow; };
struct {
    __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
    __uint(max_entries, 1);
    __type(key, __u32);
    __type(value, struct selected_cpu_counts);
} selected_counts SEC(".maps");
static __always_inline void selected_inc(struct selected_cpu_counts *counts, __u64 *field)
{
    if (__sync_fetch_and_add(field, 1) == ~0ULL)
        __sync_fetch_and_or(&counts->overflow, 1);
}
```

After PID/birth selection and before sequence lookup, obtain key0's selected map
entry and increment attempted. Its fixed PerCPUArray/key0/entries1 layout is
validated by the loader; missing capability never yields a final proof. On reserve
failure increment reserve_failed; after successful submit increment submitted:

```c
struct selected_cpu_counts *counts = bpf_map_lookup_elem(&selected_counts, &zero);
if (!counts) return 0;
selected_inc(counts, &counts->attempted);
/* retain original sequence and stack/event capture code */
if (!event) { selected_inc(counts, &counts->reserve_failed); return 0; }
/* retain original event data and helper code */
gadget_submit_buf(ctx, &events, event, sizeof(*event));
selected_inc(counts, &counts->submitted);
```

Place `zero` declaration before this lookup; do not add a second declaration.
The comment regions denote unchanged existing code, not replacement implementations.
No sampling/early-return/ring-size change is allowed. Retain Linux UAPI field widths.

- [ ] **Step4: Add the exact optional operator contract** to the existing operators
addition and a pointer field on EventFinalizationOptions/Observation:

```go
type SelectedAccountingTarget struct { Session string; PID uint32; Birth uint64 }
type SelectedCPUCounts struct { Attempted,Submitted,ReserveFailed,Overflow uint64 }
type SelectedAccountingObservation struct {
    Target SelectedAccountingTarget
    MapID uint32
    PossibleCPUs int
    Rows []SelectedCPUCounts
    Quiesced,Joined bool
}
// EventFinalizationOptions: Accounting *SelectedAccountingTarget
// EventFinalizationObservation: SelectedAccounting *SelectedAccountingObservation `json:"selected_event_accounting,omitempty"`
```

In prepareEventFinalization, when Accounting is nonnil, validate/copy target
metadata only. Immediately after actual collection acquisition, validate map spec,
actual map metadata/Info.ID and ebpf.PossibleCPU; store the immutable binding
on ebpfInstance before publication. In nativeEventStopBackend.Sample, after existing Quiesce,
Flush and Join succeed and before Retire closes maps, read the same map again:

The complete binding/sample implementation below uses the actual owned fields;
`prepared` denotes the post-acquisition/pre-publication receipt, not the early
prepareEventFinalization call. Do not discover a new map by name after retirement.
Populate observation only after successful actual joins/read and copy Rows;
preserve the existing reserve-sum Sample return. Expose this stored diagnostic
through EventFinalization without changing the stop backend interface. Collector
translates rows and calls Task1 only after delivered terminal counts are final.

Use one explicit binding struct in the new selected_accounting_native.go.txt
instead of three unrelated instance fields (same ebpfoperator package, imports fmt, strconv,
Cilium ebpf and Gadget operators):

```go
type selectedAccountingBinding struct {
    owner *ebpfInstance
    target operators.SelectedAccountingTarget
    selectedMap *ebpf.Map
    preparedMapID uint32
    preparedPossibleCPUs int
}
func validateSelectedAccountingTarget(target *operators.SelectedAccountingTarget,pidText,birthText string)error {
    if target==nil||target.Session==""||len(target.Session)>128||target.PID==0||target.Birth==0 {
        return fmt.Errorf("invalid selected accounting target")
    }
    pid,err:=strconv.ParseUint(pidText,10,32)
    if err!=nil {return fmt.Errorf("parse selected target PID: %w",err)}
    birth,err:=strconv.ParseUint(birthText,10,64)
    if err!=nil {return fmt.Errorf("parse selected target birth: %w",err)}
    if pid!=uint64(target.PID)||birth!=target.Birth {return fmt.Errorf("selected target differs from producer parameters")}
    return nil
}
func validSelectedCounterInfo(info *ebpf.MapInfo)bool {
    return info!=nil&&info.Type==ebpf.PerCPUArray&&info.KeySize==4&&info.ValueSize==32&&info.MaxEntries==1
}
func bindSelectedAccounting(i *ebpfInstance,target *operators.SelectedAccountingTarget)(*selectedAccountingBinding,error) {
    if target==nil {return nil,nil}
    if target.Session==""||target.PID==0||target.Birth==0||i.collectionSpec==nil||i.collection==nil {return nil,fmt.Errorf("invalid selected accounting target/owner")}
    spec:=i.collectionSpec.Maps["selected_counts"]
    if spec==nil||spec.Type!=ebpf.PerCPUArray||spec.KeySize!=4||spec.ValueSize!=32||spec.MaxEntries!=1 {return nil,fmt.Errorf("selected counter layout unsupported")}
    m:=i.collection.Maps["selected_counts"];if m==nil {return nil,fmt.Errorf("selected map not acquired")}
    info,err:=m.Info();if err!=nil {return nil,err}
    if !validSelectedCounterInfo(info) {return nil,fmt.Errorf("actual selected map layout unsupported")}
    id,ok:=info.ID();if !ok||id==0 {return nil,fmt.Errorf("selected kernel map ID unavailable")}
    cpus,err:=ebpf.PossibleCPU();if err!=nil {return nil,err}
    if cpus<=0 {return nil,fmt.Errorf("possible CPU count unavailable")}
    return &selectedAccountingBinding{owner:i,target:*target,selectedMap:m,preparedMapID:uint32(id),preparedPossibleCPUs:cpus},nil
}
func (b *selectedAccountingBinding) identity()operators.SelectedAccountingObservation {
    return operators.SelectedAccountingObservation{Target:b.target,MapID:b.preparedMapID,PossibleCPUs:b.preparedPossibleCPUs}
}
func (b *selectedAccountingBinding) sampleAfterJoin()(*operators.SelectedAccountingObservation,error) {
    if b.owner.collection==nil||b.owner.collection.Maps["selected_counts"]!=b.selectedMap {
        return nil,fmt.Errorf("selected collection map ownership changed")
    }
    info,err:=b.selectedMap.Info();if err!=nil {return nil,err}
    if !validSelectedCounterInfo(info) {return nil,fmt.Errorf("actual selected map layout changed")}
    id,ok:=info.ID();if !ok||uint32(id)!=b.preparedMapID {return nil,fmt.Errorf("selected map changed")}
    cpus,err:=ebpf.PossibleCPU();if err!=nil {return nil,err}
    if cpus!=b.preparedPossibleCPUs {return nil,fmt.Errorf("possible CPU cardinality changed")}
    rows:=make([]operators.SelectedCPUCounts,cpus);zero:=uint32(0)
    if err:=b.selectedMap.Lookup(&zero,&rows);err!=nil {return nil,err}
    value:=b.identity();value.Rows=rows;value.Quiesced=true;value.Joined=true
    return &value,nil
}
func (i *ebpfInstance) SelectedAccountingBinding()(operators.SelectedAccountingObservation,bool) {
    if i.selectedAccounting==nil {return operators.SelectedAccountingObservation{},false}
    return i.selectedAccounting.identity(),true
}
func (i *ebpfInstance) bindPreparedSelectedAccounting()error {
    if i.eventStopOptions==nil||i.eventStopOptions.Accounting==nil {return nil}
    binding,err:=bindSelectedAccounting(i,i.eventStopOptions.Accounting)
    if err!=nil {return err}
    i.selectedAccounting=binding
    return nil
}
```

Add exactly `selectedAccounting *selectedAccountingBinding` to ebpfInstance in the
existing patch. At early prepare, strictly parse/validate target parameters and
copy the target options by value; no collection/map receipt exists then. Immediately
after `i.collection = collection` call bindPreparedSelectedAccounting and retain
only its real successful acquisition. At the start of Sample (which the
existing owner calls only after Join), invoke sampleAfterJoin and retain its
observation under the existing eventStopOwner mutex, returning the original error
on failure. Its copySnapshot method owns all diagnostic copying; leave the existing
EventFinalization getter delegating to that snapshot rather than adding an unlocked
instance-level publication channel.

The collector obtains expected map identity from SelectedAccountingBinding on the
actual `ebpfInstance`, not from the final snapshot's own claimed MapID. Compare
that target to its immutable cfg PID/birth/session, then feed expected prepared
identity and final observation separately to BindAndEvaluate. Missing getter,
binding or parent resources_retired phase remains unknown and returns its cause.

In canonical collector.go's cfg.DrainFull EventFinalizationOptions literal add
`Accounting: &operators.SelectedAccountingTarget{Session: sessionID, PID: uint32(cfg.PID), Birth: ticks}`.
This activates the reviewed capability only for the existing explicitly owned
full-drain profile; the new accepted asset pin requires that graph to contain the
map. Add `expectedAccounting *accounting.Binding` local to Run's owned lifetime.
At the beginning of its simple.OnStart callback obtain the prepared identity
before publishing ready/attached:

```go
if cfg.DrainFull {
    expected,err:=selectedExpected(gc,sessionID,uint32(cfg.PID),ticks)
    if err!=nil {return err}
    expectedAccounting=&expected
}
```

Define this exact helper in the new capture/selected_accounting.go:

```go
type selectedBindingProvider interface {
    SelectedAccountingBinding()(operators.SelectedAccountingObservation,bool)
}
func selectedExpected(gc operators.GadgetContext,session string,pid uint32,birth uint64)(accounting.Binding,error) {
    raw,ok:=gc.GetVar("ebpfInstance");if !ok {return accounting.Binding{},fmt.Errorf("selected owner missing")}
    owner,ok:=raw.(selectedBindingProvider);if !ok {return accounting.Binding{},fmt.Errorf("selected binding capability missing")}
    value,ok:=owner.SelectedAccountingBinding()
    if !ok||value.Target.Session!=session||value.Target.PID!=pid||value.Target.Birth!=birth||value.MapID==0||value.PossibleCPUs<=0 {
        return accounting.Binding{},fmt.Errorf("selected prepared owner identity mismatch")
    }
    return accounting.Binding{Session:session,PID:pid,Birth:birth,MapID:value.MapID,PossibleCPUs:value.PossibleCPUs},nil
}
```

The reference is written once before work starts and read only after the owned
runtime returns. It is session-local, never a process-global map-generation cache.

The concrete early options-copy replacement, post-acquisition insertion and Sample
insertion are below. Replace the existing `copy := *opts` / eventStopOptions /
newEventStopOwner block at the end of prepareEventFinalization, preserving all
preceding profile/deadline/StrictFull checks:

```go
copy:=*opts
if opts.Accounting!=nil {
    target:=*opts.Accounting
    copy.Accounting=&target
    pid,hasPID:=parameters["target_pid"]
    birth,hasBirth:=parameters["target_start_ticks"]
    if !hasPID||!hasBirth||pid==nil||birth==nil {return fmt.Errorf("selected target parameters missing")}
    if err:=validateSelectedAccountingTarget(copy.Accounting,pid.AsString(),birth.AsString());err!=nil {
        return err
    }
}
i.eventStopOptions=&copy
i.eventFinalizer=newEventStopOwner(nativeEventStopBackend{instance:i})
```

Add this hunk to the authored gadget-finalization.patch, not generated ebpf.go.
The upstream file already imports errors. The anchor is the exact assignment
after successful NewCollectionWithOptions; it precedes tail-call setup, map-context
publication, reader startup and attachment:

```go
i.collection=collection
if err:=i.bindPreparedSelectedAccounting();err!=nil {
    return errors.Join(err,closeEventCollection(i))
}
```

Do not call bindSelectedAccounting in prepareEventFinalization or move map ownership
into a background worker. Existing closeEventCollection owns the acquired handles
and preserves independent errors. Bind failure must leave no published getter,
reader or attached link; the actual native negative/live gates verify this.

```go
// nativeEventStopBackend.Sample, before the existing lost-sample lookup:
if b.instance.selectedAccounting!=nil {
    if b.instance.eventFinalizer.snapshot().Phase!="receiver_joined" {
        return 0,fmt.Errorf("selected counters sampled before owned joins")
    }
    value,err:=b.instance.selectedAccounting.sampleAfterJoin()
    if err!=nil {return 0,err}
    owner:=b.instance.eventFinalizer
    owner.mu.Lock()
    owner.observation.SelectedAccounting=value
    owner.mu.Unlock()
}
```

Add these host regressions to existing event_finalization_native_test.go.txt;
its operators import exists. They test parsing/layout boundaries, not kernel
receipts. Tests are red before the new functions, not a source-string scan:

```go
func TestSelectedTargetMetadataNeedsNoCollection(t *testing.T) {
    target:=&operators.SelectedAccountingTarget{Session:"s",PID:123,Birth:456}
    if err:=validateSelectedAccountingTarget(target,"123","456");err!=nil {t.Fatal(err)}
    for _,pair:=range [][2]string{{"","456"},{"4294967296","456"},{"-1","456"},{"123","18446744073709551616"},{"123","457"}} {
        if err:=validateSelectedAccountingTarget(target,pair[0],pair[1]);err==nil {t.Fatal("invalid target parsed",pair)}
    }
    i:=eventFinalizationScopeFixture()
    i.eventStopOptions=&operators.EventFinalizationOptions{Accounting:target}
    if err:=i.bindPreparedSelectedAccounting();err==nil {t.Fatal("receipt invented before actual collection acquisition")}
    if _,ok:=i.SelectedAccountingBinding();ok {t.Fatal("failed binding published")}
}
func TestSelectedActualMapLayoutIsIndependentOfSpec(t *testing.T) {
    good:=ebpf.MapInfo{Type:ebpf.PerCPUArray,KeySize:4,ValueSize:32,MaxEntries:1}
    if !validSelectedCounterInfo(&good)||validSelectedCounterInfo(nil) {t.Fatal("layout admission")}
    bad:=good;bad.ValueSize=8
    if validSelectedCounterInfo(&bad) {t.Fatal("actual incompatible map accepted")}
}
```

The actual acquired-map tests must additionally use a real wrong-layout map and
assert successful bind precedes ready/reader/link publication. A modeled MapInfo
or lexical ordering assertion cannot replace that native proof.

In eventStopOwner.copySnapshot, after its existing NativeReserveFailures copy,
insert this complete deep copy while its existing caller holds o.mu:

```go
if s.SelectedAccounting!=nil {
    value:=*s.SelectedAccounting
    value.Rows=append([]operators.SelectedCPUCounts(nil),value.Rows...)
    s.SelectedAccounting=&value
}
```

The prepared binding getter above never reads a retired map; it returns captured
acquisition identity only. Mutating a returned diagnostic must not mutate the owner.

- [ ] **Step5: Wire the optional model diagnostic and test unchanged refusal:**
add `SelectedAccounting *SelectedAccountingDiagnostic` with
`json:"selected_event_accounting,omitempty"` to model.Summary. Define the exact
diagnostic in model/record.go:

```go
type SelectedAccountingDiagnostic struct {
    SchemaVersion uint32 `json:"schema_version"`
    Scope string `json:"scope"`
    SessionID string `json:"session_id"`
    PID uint32 `json:"host_pid"`
    Birth uint64 `json:"process_start_ticks"`
    MapID uint32 `json:"map_id"`
    PossibleCPUs int `json:"possible_cpus"`
    Attempted uint64 `json:"attempted"`
    Submitted uint64 `json:"submitted"`
    ReserveFailed uint64 `json:"reserve_failed"`
    Received uint64 `json:"received"`
    Terminals uint64 `json:"terminals"`
    Complete bool `json:"complete"`
    Reason string `json:"reason,omitempty"`
}
```

Implement selected_accounting.go in package capture, importing fmt, math/bits,
operators, accounting and model:

```go
func selectedDiagnostic(expected accounting.Binding,value operators.SelectedAccountingObservation,parent *model.EventFinalizationObservation,stats model.Stats)(*model.SelectedAccountingDiagnostic,error) {
    if parent==nil||parent.Phase!="resources_retired"||!parent.ResourcesRetired||!parent.ProducersQuiesced||!parent.ReceiverJoined {
        return nil,fmt.Errorf("selected source owner not final")
    }
    terminals,carry:=bits.Add64(stats.Resolved,stats.Unavailable,0)
    terminals,last:=bits.Add64(terminals,stats.Skipped,0)
    if carry!=0||last!=0 {return nil,fmt.Errorf("terminal count overflow")}
    rows:=make([]accounting.CPUCounts,len(value.Rows))
    for n,row:=range value.Rows {rows[n]=accounting.CPUCounts{Attempted:row.Attempted,Submitted:row.Submitted,ReserveFailed:row.ReserveFailed,Overflow:row.Overflow}}
    actual:=accounting.Binding{Session:value.Target.Session,PID:value.Target.PID,Birth:value.Target.Birth,MapID:value.MapID,PossibleCPUs:value.PossibleCPUs}
    counts,proof,err:=accounting.BindAndEvaluate(expected,accounting.Snapshot{Binding:actual,Rows:rows,Quiesced:value.Quiesced,Joined:value.Joined,Received:stats.Received,Terminals:terminals})
    if err!=nil {return nil,err}
    return &model.SelectedAccountingDiagnostic{
        SchemaVersion:1,Scope:"owned_openat_handler_entry",SessionID:expected.Session,
        PID:expected.PID,Birth:expected.Birth,MapID:expected.MapID,PossibleCPUs:expected.PossibleCPUs,
        Attempted:counts.Attempted,Submitted:counts.Submitted,ReserveFailed:counts.ReserveFailed,
        Received:counts.Received,Terminals:counts.Terminals,Complete:proof.Complete,Reason:proof.Reason,
    },nil
}
func selectedFinal(gc operators.GadgetContext)(*operators.SelectedAccountingObservation,error) {
    raw,ok:=gc.GetVar("ebpfInstance");if !ok {return nil,fmt.Errorf("selected final owner missing")}
    owner,ok:=raw.(eventFinalizationObserver);if !ok {return nil,fmt.Errorf("selected final capability missing")}
    value,active:=owner.EventFinalization()
    if !active||value.SelectedAccounting==nil {return nil,fmt.Errorf("selected final counters missing")}
    return value.SelectedAccounting,nil
}
```

Call this converter only with the prepared getter identity, final native snapshot,
existing finalization observation and actual final enrichment stats; attach the
result to the existing summary literal and propagate its error through the
collector's existing error path. Never turn a read/permission/binding error into
zero counters. Global CollectionHealth/EventLossObservation and public policy
refusal retain their current semantics.

After assigning summary.Stats, and before its existing `if err != nil` block, insert:

```go
if cfg.DrainFull {
    value,selectedErr:=selectedFinal(gc)
    if selectedErr==nil&&expectedAccounting==nil {selectedErr=fmt.Errorf("selected prepared identity missing")}
    if selectedErr==nil {
        summary.SelectedAccounting,selectedErr=selectedDiagnostic(*expectedAccounting,*value,eventFinalization,summary.Stats)
    }
    err=errors.Join(err,selectedErr)
    if selectedErr!=nil {summary.TerminalAccountingComplete=false}
}
```

For non-owned/non-accounting captures leave the optional pointer nil. The original
eventFinalizationSnapshot error remains in the existing errors.Join result. Retain
the native owner on a nonretired result; a selected read failure must not authorize
closing resources under a still-active callback or mark cleanup complete.

- [ ] **Step6: Compute pins, mirror, then rebuild and verify the native graph:**

After the approved native changes, compute the patch identity before packing. A
bundle cannot pass admission against the old accepted pin. From the checkout root:

```bash
PATCH_ID=$(cd profiler/hotspot-agentless && go run ./cmd/mw-collector-tools patch-id) || exit
export PATCH_ID
python3 - <<'PY'
import os, re
from pathlib import Path
new = os.environ["PATCH_ID"].strip()
assert re.fullmatch(r"[a-f0-9]{64}", new)
old = "618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c"
files = [
 "profiler/hotspot-agentless/internal/tooling/assets.go",
 "profiler/hotspot-agentless/internal/tooling/prepare_test.go",
 "experiments/hotspot-agentless/internal/tooling/assets.go",
 "experiments/hotspot-agentless/internal/tooling/prepare_test.go",
 "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt",
 "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotProtocol.kt",
 "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotAssetsTest.kt",
 "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotProtocolTest.kt",
 "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotChildFixture.kt",
]
for name in files:
    path = Path(name)
    text = path.read_text()
    assert old in text, (name, "reconcile a changed pin before editing")
    path.write_text(text.replace(old, new))
print(new)
PY
```

If the upstream-alignment audit has been implemented before this native change,
its reviewed source key is intentionally still tied to the old patch. Before
admitting candidate reports for the new graph, re-run its actual-source overlay
characterization against the new prepared tree, inspect the capture/decoder delta
and update `capture_audit.go` plus `capture_audit_test.go` in both canonical and
experimental `internal/qualification/jitgc/` copies, plus the valid reviewed-pin
fixture in `experiments/hotspot-agentless/internal/candidate/capture_audit_test.go`,
with that reviewed digest. These are five additional conditional consumers, not an automatic allowlist
expansion. Until that separate source review passes, source-audit reports reject
the new overlay; never bypass it or claim the old reviewed key certifies new bytes.

Copy only this task's changed native/BPF/addition/model/test files to their matching
experimental snapshot paths and record path/SHA256 pairs; do not copy driver
sources or an entire generated directory. Require its computed patch-id to equal
PATCH_ID. Regenerate both owned graphs with prepare, then preserve any existing
build/assets destination before packing (pack refuses an existing destination):

```bash
(cd profiler/hotspot-agentless && go run ./cmd/mw-collector-tools prepare)
(cd experiments/hotspot-agentless && go run ./cmd/mw-collector-tools prepare)
(cd profiler/hotspot-agentless && DOCKER_HOST=unix:///var/run/docker.sock make bundle DOCKER_HOST_ARG='--host unix:///var/run/docker.sock')
(cd profiler/hotspot-agentless && go run ./cmd/mw-collector-tools verify --bundle build/assets)
```

Record the computed image digest from actual output. Reproduce helper bytes twice
on the pinned Go image, preserving the first build/hash rather than overwriting
its evidence. Do not rewrite historical evidence documents to the new digest.
- [ ] **Step7: Native/host gates and commit:** run the existing actual
TestKernelOpenatCountersPressureAndOverflow (512attempt denominator) and the owning
Gadget event-finalization kernel tests with mw_kernel/pinned image/host-PID. Require
pressure>0, per-CPU equations, sticky overflow rejection and no unconditional skip.
Run Go host/race plus Kotlin HotSpotAssets/Protocol/WireReader/Coverage/Compatibility
tests and ABI. Commit only the exact table-listed changed files plus computed pins
as `feat(profiler): sample selected counters from the owned collector`.

In HotSpotPublicIntegrationTest, add this helper and call it immediately after
verifyCorpus in the existing installed-public-strategy test. Existing imports
already include Json/jsonObject/jsonPrimitive/assertEquals/assertTrue/assertFalse:

```kotlin
private fun verifySelectedAccounting(evidence: StructuredProfileEvidence, pid: Int) {
    val selected = Json.parseToJsonElement(evidence.summary.rawJson).jsonObject
        .getValue("selected_event_accounting").jsonObject
    fun number(key: String): ULong {
        val value = selected.getValue(key).jsonPrimitive
        assertFalse(value.isString)
        return value.content.toULong()
    }
    assertEquals(1uL, number("schema_version"))
    assertEquals("owned_openat_handler_entry", selected.getValue("scope").jsonPrimitive.content)
    assertEquals(evidence.sessionId, selected.getValue("session_id").jsonPrimitive.content)
    assertEquals(pid.toULong(), number("host_pid"))
    assertEquals(evidence.records.first().event.processStartTicks, number("process_start_ticks"))
    assertTrue(number("map_id") > 0uL && number("possible_cpus") > 0uL)
    val attempted = number("attempted")
    val submitted = number("submitted")
    assertTrue(submitted <= attempted)
    assertEquals(attempted - submitted, number("reserve_failed"))
    assertTrue(number("received") <= submitted)
    assertEquals(evidence.summary.received, number("received"))
    assertEquals(evidence.summary.received, number("terminals"))
    assertEquals("true", selected.getValue("complete").jsonPrimitive.content)
    assertFalse(evidence.collectionLossComplete)
}
```

Focused exact commands from the checkout root (the kernel binary uses the existing
pinned Go image and actual rootful endpoint; absence/denial fails, not skips):

```bash
(cd profiler/hotspot-agentless && go test -race ./internal/accounting)
(cd profiler/hotspot-agentless && go test -race -modfile build/experiment.go.mod ./internal/capture github.com/inspektor-gadget/inspektor-gadget/pkg/operators/ebpf)
(cd profiler/hotspot-agentless && go test -c -race -tags mw_kernel -o build/selected-accounting-kernel.test ./internal/accounting)
GO_IMAGE=$(cd profiler/hotspot-agentless && go run ./cmd/mw-collector-tools image --key go) || exit
docker --host unix:///var/run/docker.sock run --rm --pull=never --privileged --pid=host --mount type=bind,src=/sys,dst=/sys,readonly --mount "type=bind,src=$PWD/profiler/hotspot-agentless,dst=/work,readonly" --entrypoint /work/build/selected-accounting-kernel.test "$GO_IMAGE" -test.run 'TestKernelOpenatCountersPressureAndOverflow|TestActualMapReplacementDoesNotCertifyOriginalOwner' -test.v
(cd experiments/hotspot-agentless && DOCKER_HOST=unix:///var/run/docker.sock make event-finalization-kernel-test DOCKER_HOST_ARG='--host unix:///var/run/docker.sock')
(cd profiler/hotspot-agentless && go test -c -race -modfile build/experiment.go.mod -tags mw_kernel -o build/selected-map-binding-kernel.test github.com/inspektor-gadget/inspektor-gadget/pkg/operators/ebpf)
docker --host unix:///var/run/docker.sock run --rm --pull=never --privileged --pid=host --mount type=bind,src=/sys,dst=/sys,readonly --mount "type=bind,src=$PWD/profiler/hotspot-agentless,dst=/work,readonly" --entrypoint /work/build/selected-map-binding-kernel.test "$GO_IMAGE" -test.run TestKernelSelectedAccountingKeepsAcquiredMapOwner -test.v
DOCKER_HOST=unix:///var/run/docker.sock MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock MW_HOTSPOT_NATIVE_ACCEPTANCE=true MW_HOTSPOT_ASSET_TEST_DIR="$PWD/profiler/hotspot-agentless/build/assets" ./gradlew :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest' --rerun
```

The public test is the actual compiled producer/collector/map coupling proof; the
isolated512attempt asm control and test-only owner model cannot substitute for it.

### Task 3: Structured all-source inventory, no global promotion

**Files:** Create `profiler/hotspot-agentless/internal/accounting/lanes.go` and
`lanes_test.go`; mirror those exact files into the experiment; create the evidence
document and update the loss issue/public guide listed below.

**Interfaces:** New `Lane` finite constants; `LaneEvidence{Binding, Final, Counts}`;
`MissingEvidence(expected Binding, lanes map[Lane]LaneEvidence) []Lane` returns every
missing/non-final/mismatched lane. Existing Inventory/Missing remain for old tests;
production diagnostics use the structured variant.

- [ ] **Step1: Write failing lane test:**

```go
func TestFinalReaderDoesNotProveMissingDefinitionAdmission(t *testing.T) {
    b:=Binding{Session:"s",PID:123,Birth:456,MapID:7,PossibleCPUs:1}
    lanes:=map[Lane]LaneEvidence{}
    for _,lane:=range []Lane{SelectedEvents,Enrichment,Wire,Retention} {
        lanes[lane]=LaneEvidence{Binding:b,Final:true,Counts:Counts{Known:true,Quiesced:true,Joined:true}}
    }
    missing:=MissingEvidence(b,lanes)
    if len(missing)!=1||missing[0]!=Definitions {t.Fatal(missing)}
    lanes[Definitions]=LaneEvidence{Binding:b,Final:false}
    if len(MissingEvidence(b,lanes))!=1 {t.Fatal("non-final definitions became complete")}
}
```

- [ ] **Step2: Implement complete `lanes.go`:**

```go
package accounting
type Lane string
const(SelectedEvents Lane="selected_events";Definitions Lane="definitions";Enrichment Lane="enrichment";Wire Lane="wire";Retention Lane="retention")
type LaneEvidence struct { Binding Binding; Final bool; Counts Counts }
func MissingEvidence(expected Binding,lanes map[Lane]LaneEvidence)[]Lane {
    required:=[]Lane{SelectedEvents,Definitions,Enrichment,Wire,Retention}
    missing:=[]Lane{}
    for _,lane:=range required {
        evidence,ok:=lanes[lane]
        if !ok||evidence.Binding!=expected||!evidence.Final||!Evaluate(evidence.Counts).Complete {missing=append(missing,lane)}
    }
    for lane:=range lanes {
        switch lane {case SelectedEvents,Definitions,Enrichment,Wire,Retention:default:missing=append(missing,lane)}
    }
    return missing
}
```

Zero rows are final only with actual known/quiesced/joined receipts; a false/missing
flag never becomes true by omission. The map ID binds the selected collector session;
other lane-specific native source IDs must be recorded in the evidence document,
not forged as that event map's own counters.
- [ ] **Step3:** Run canonical accounting host/race, then actual definition-drop,
held-reader/auxiliary callback, wire write/truncate, retention0 and target-exec
controls. Record each missing lane explicitly in
`docs/plans/2026-10-17-hotspot-production-loss-evidence.md`. Do not set a lane Final
without its actual native admission/delivery counter and physical end.
- [ ] **Step4:** Run Kotlin coverage/copy/policy refusal and Go inventory tests;
update the loss issue/public guide with selected scope only. Stage exact lane
files/evidence/issue changes; commit `feat(profiler): retain structured missing loss evidence`.
The full commit build is mandatory. Global completeness remains false after this plan.
