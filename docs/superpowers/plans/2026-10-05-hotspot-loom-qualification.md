---
document_type: execution_plan
base_revision: eacd0081
status: qualification_harness_delivered_mount_promotion_blocked
---

# HotSpot Loom Identity Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegate only after operator selection.

**Goal:** Distinguish physical carrier observations from provable mounted virtual-thread identity, and explicitly qualify logical stack/parent availability under mount/unmount and GC races.

**Architecture:** Add an offline identity-proof model and an oracle-only virtual-thread fixture. Capture remains explicitly experimental until a source/binary-proved mount and object-lifetime mechanism exists. Logical parent history is a separate causal property: absence of an authoritative parent source yields unavailable, never a reconstructed parent guessed from host TID or timing.

**Tech Stack:** Pinned JDK25 virtual threads, existing agentless Go/BPF experimental graph, Go host tests and JSONL oracle tools.

**Spec:** `docs/plans/2026-10-05-hotspot-performance-capability-design.md`, Loom section; existing safepoint epoch design and runtime qualification plan.

## Global Constraints

- JDK25/library floor22/Linux-amd64; production pinned25.0.4.1+1-LTS interpreter/Serial synchronous openat remains unchanged.
- Readiness<=10s/session<=60s/stop<=2 remaining seconds/event deadline1s;4096/33,554,432byte retention and native budgets unchanged.
- Never install seccomp on a virtual thread; preserve process-wide containment/JVM coordination floor.
- Host TID is carrier identity, not virtual-thread/task/async-parent identity. Capture evidence immutable; no nearest-time joins.
- No production agent/JVMTI/application oracle data injection; oracle logs validate experiments only.
- Object address alone is not logical identity; mount generation and GC/object lifetime proof required. Unknown logical identity/parent is unavailable.
- No dependency/breaking API change or automatic permission fallback; production schema/API negotiation requires separate approval.
- Whole-session/policy coverage stays incomplete; preserve exact occurrence/process generation, checked cleanup and user work; no GITHUB_TOKEN handling.

---

## File Structure

|File|Responsibility|
|---|---|
|Create `profiler/hotspot-agentless/internal/qualification/loom/identity.go`|Offline identity scope decision|
|Create `profiler/hotspot-agentless/internal/qualification/loom/identity_test.go`|Carrier reuse, mount ABA, object GC and absent-parent negatives|
|Create `experiments/hotspot-agentless/fixtures/java/VirtualThreadOracle.java`|Unique-token logical thread oracle and migration pressure|
|Create `experiments/hotspot-agentless/scripts/verify-loom-candidate.py`|Event-own-token comparisons; reject oracle substitution|
|Create `experiments/hotspot-agentless/tests/unit/test-loom-candidate.py`|Carrier reuse/swapped token and unavailable-parent verifier tests|
|Create `docs/plans/2026-10-05-hotspot-loom-evidence.md`|Exact mount/object lifetime findings and promotion decision|
|Read `gadgets/openat-stack/program.bpf.c`, `internal/model/event.go` and identity/epoch overlays|Current physical identity and lifetime boundary|

### Task1: Conservative identity scope model

**Interfaces:** New Go `Facts`, `Scope(Facts) string`; only offline verifier consumes it. Result strings `carrier_only`, `mounted_virtual`, `logical_unavailable`.

Use `package loom`; identity_test.go imports testing. The model needs no imports.

- [ ] Write failing tests:

```go
func TestCarrierReuseCannotCreateVirtualIdentity(t *testing.T) {
 if Scope(Facts{CarrierTID:123,VirtualID:7})!="logical_unavailable" {
  t.Fatal("host TID guessed a virtual identity")
 }
}
func TestMountGenerationAndObjectLeaseRequired(t *testing.T) {
 f:=Facts{CarrierTID:123,VirtualID:7,AtCapture:4,AtDecode:4,ObjectLease:true}
 if Scope(f)!="mounted_virtual" { t.Fatal("qualified mounted identity lost") }
 f.AtDecode=5
 if Scope(f)!="logical_unavailable" { t.Fatal("remount admitted") }
}
```

- [ ] Run `go test -modfile build/experiment.go.mod ./internal/qualification/loom`; observe undefined interface.
- [ ] Implement:

```go
type Facts struct {
 CarrierTID uint32
 VirtualID, AtCapture, AtDecode uint64
 ObjectLease bool
}
func Scope(f Facts) string {
 if f.CarrierTID==0 { return "logical_unavailable" }
 if f.VirtualID==0 { return "carrier_only" }
 if !f.ObjectLease || f.AtCapture==0 || f.AtCapture!=f.AtDecode {
  return "logical_unavailable"
 }
 return "mounted_virtual"
}
```

Add tests for same carrier/different VirtualID, reused heap address with changed
logical ID, zero/changed mount token and absent object lease. Logical parent is
not produced by this model. Synthetic facts never establish production support.
- [ ] Run host/race tests; commit the independently usable verifier model.

### Task2: Real virtual-thread token oracle and physical observations

**Interfaces:** Fixture `<directory>`, writes oracle records after native operation
with unique path/token, Java logical threadId, isVirtual and expected route. Native
records retain their own event IDs/BOOTTIME/host TID; no fixture value is fed back.

- [ ] Write verifier red test using one carrier for two different logical IDs and
swap their occurrence tokens. Require rejection rather than nearest-TID attribution.
Expected oracle shape:

```json
{"token":"vt-7-3-A","logical_id":7,"is_virtual":true,"route":["leaf","routeA"],"parent_available":false}
```

Actual wrong-logical-ID red test body (unittest/importlib-load the planned script):

```python
def test_carrier_reuse_cannot_swap_logical_ids(self):
    oracle={"vt-7-3-A":{"logical_id":7}}
    rows=[{"kind":"event","event_id":"s:0:1","event":{"filename":"/fixture/vt-7-3-A","host_tid":42,
      "thread_identity":{"scope":"mounted_virtual","logical_id":8,"object_lease":True,
                         "mount_capture":1,"mount_decode":1,"parent_available":False}}}]
    with self.assertRaises(ValueError): module.verify(oracle,rows)
```

Verifier CLI consumes oracle JSONL and captured native JSONL, matches exact filename
token first, checks logical ID only if candidate evidence actually supplies it with
mount/object proof, and reports absent identity/parent as unavailable.

Define `verify(oracle: dict[str,dict], records: list[dict]) -> dict`; CLI reads
oracle JSONL keyed by token and captured JSONL, then prints the result:

```python
def verify(oracle, records):
    terminals = {}
    for row in records:
        if row["kind"] == "enrichment":
            if row["event_id"] in terminals: raise ValueError("duplicate terminal")
            terminals[row["event_id"]] = row["evidence"]
    seen, logical = set(), 0
    for row in records:
        if row["kind"] != "event": continue
        event = row["event"]
        token = event["filename"].rsplit("/",1)[-1]
        if token not in oracle: continue
        if token in seen: raise ValueError("duplicate logical operation")
        seen.add(token)
        identity = event.get("thread_identity", {})
        if identity.get("source") == "oracle": raise ValueError("oracle substituted for capture")
        if identity.get("scope") != "mounted_virtual": continue
        if identity.get("logical_id") != oracle[token]["logical_id"]:
            raise ValueError("wrong virtual thread for occurrence")
        if not identity.get("object_lease") or identity.get("mount_capture",0)==0 or identity.get("mount_capture")!=identity.get("mount_decode"):
            raise ValueError("unproved mount/object identity")
        if identity.get("parent_available",False): raise ValueError("parent source unqualified")
        evidence = terminals.get(row["event_id"])
        if evidence is None: raise ValueError("missing identity terminal")
        frames = [f["function_name"] for f in evidence["frames"]]
        if evidence["attribution"] != "event_correlated":
            if frames: raise ValueError("unavailable logical frames are not empty")
            continue
        expected = oracle[token]["route"]
        if len(frames)<len(expected) or any(name not in frames[i] for i,name in enumerate(expected)):
            raise ValueError("wrong or mixed logical route")
        logical += 1
    if seen != set(oracle): raise ValueError("missing virtual operations")
    return {"marked":len(seen), "logical_observed":logical, "parent_available":False}
```

These thread_identity fields are proposed experimental fields, not current public
production fields. Absent fields correctly yield logical_observed0. Tests include
correct captured identity, swapped ID, unknown/changed mount, oracle source and
unproved parent. Run
`python3 experiments/hotspot-agentless/tests/unit/test-loom-candidate.py`
before/after implementation; synthetic booleans cannot authorize promotion.
- [ ] Implement fixture operation code:

```java
static void leaf(Path path) throws Exception {
 try(var in=new FileInputStream(path.toFile())) { if(in.read()!=1) throw new AssertionError(); }
}
static void routeA(Path p) throws Exception { leaf(p); }
static void routeB(Path p) throws Exception { bridge(p); }
static void bridge(Path p) throws Exception { leaf(p); }
// Pre-create all token files and warm up BEFORE native attachment.
try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
 var jobs=new ArrayList<Future<?>>();
 for(int task=0;task<32;task++) {
  final int index=task;
  jobs.add(executor.submit(() -> {
   for(int sequence=0;sequence<8;sequence++) {
    String token="vt-"+index+"-"+sequence+"-"+((sequence&1)==0?"A":"B");
    Path p=directory.resolve(token);
    if((sequence&1)==0) routeA(p); else routeB(p);
    oracle.add(new Oracle(token,Thread.currentThread().threadId(),Thread.currentThread().isVirtual()));
    Thread.sleep(10);
   }
   return null;
  }));
 }
 for(var job:jobs) job.get();
}
```

Define `record Oracle(String token,long logicalId,boolean virtual)` in that class,
use `ConcurrentLinkedQueue<Oracle>`, serialize only after the observed window and
keep the target alive through drain. Ready/measure/release markers follow the JIT
fixture protocol; marker writes are not mistaken for marked operations.
- [ ] Run carrier parallelism1/2/4, park/unpark, repeated remount, nested calls,
GC pressure and ordinary platform-thread controls on the pinned JVM. Use existing
explicit experimental graph; production public facade must still refuse unproved
virtual logical profiling. Retain useful/unavailable/wrong/mixed counts.
- [ ] Commit fixture/verifier tests and provenance; no native offsets are guessed.

### Task3: Mount/object/parent proof and promotion boundary

**Files:** Evidence. **Interfaces:** Actual source/binary facts and corpus feed
Task1 identity scope; a future native schema can represent separate carrier/logical/
parent availability only after review.

- [ ] Inspect pinned JDK source520406d87195 JavaThread virtual-thread handle/current
Thread paths, Continuation mount/unmount, compressed oops/handles, Thread.threadId
and GC barriers. Record exact fields/sizes/ordering and whether a native openat call
pins a mount/object lease. Missing atomic capture or lifetime proof is a failed gate.
- [ ] Specify event-time virtual logical ID plus mount generation/object lifetime
stamp. A decode-time-only read cannot identify an earlier event. Require mismatch
to produce empty-frame logical unavailability and rollback any affected cache.
- [ ] Specify logical ancestry domains: mounted continuation stack versus causal
async parent history. If parent links are not authoritatively present at capture,
`parent_available=false` is the correct contract. Do not synthesize ancestry from
token oracle, scheduling order, carrier TID or a later parent stack.
- [ ] Produce per-tuple evidence and a candidate promotion document only if useful
exact-token mounted identity has no wrong/mixed generations under stress. New BPF
layout/schema/public fields require approval, ABI/pin updates and fresh kernel tests.
If proof is missing, deliver the working qualification harness and explicit failure
result; current carrier/process-wide scope remains unchanged.
- [ ] Run Go host/race, existing exec/unloading/refusal tests, backlog/link/whitespace
checks and root build; commit `docs(profiler): record Loom identity qualification`.

## Completion gate

Execution: model/oracle/verifier and actual256token corpus delivered for1/2/4
carriers. Logical identity/parent remain unavailable because native event-time
mount/object proof is absent; production capture/schema are unchanged. See
[evidence](../../plans/2026-10-05-hotspot-loom-evidence.md).

Oracle-independent native identity can be distinguished from carrier-only evidence;
unproved logical identity/parent is explicitly unavailable. Production support is
gated on event-time mount/object proof and separately reviewed protocol/ABI changes.
