---
document_type: execution_plan
base_revision: eacd0081
status: approved_inline_qualification
---

# HotSpot JIT and GC Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegate only after operator selection.

**Goal:** Produce independently qualified pinned C1/Serial, C2/Serial and interpreter/G1 candidates with a fail-closed lifetime gate before any production whitelist expansion.

**Architecture:** Keep existing ValidateProfile/Discover and schema2 production refusal intact. Add an offline qualification model and tokenized JVM fixtures, then use explicitly experimental schema1 capture to expose failures. Promote only a tuple whose source/binary proof and useful adversarial corpus establish both metadata and compiled-code lifetime; this plan's initial deployable software is the qualification harness, not an unproved JIT switch.

**Tech Stack:** Existing Go collector/overlays, pinned Temurin25/JDK sources, Java fixtures and Python report verification; no new dependencies.

**Spec:** `docs/plans/2026-10-05-hotspot-performance-capability-design.md`, capability section; `docs/plans/2026-10-01-agentless-hotspot-epoch-design.md`.

## Global Constraints

- JDK25/library floor22/Linux-amd64. Production remains pinned25.0.4.1+1-LTS `-Xint -XX:+UseSerialGC` synchronous openat until separately approved promotion.
- Readiness<=10s/session<=60s/stop<=2 remaining seconds/enrichment deadline1s; native budgets and Kotlin4096/33,554,432byte retention unchanged.
- No dependency/breaking API change, no silent EPERM/EACCES fallback, no altered public completeness/policy eligibility.
- Same process birth/JVM mapping/exec barrier; capture identity immutable, rejected caches rolled back, no later same-thread stack substitution.
- Stable safepoint alone does not prove compiled-code or concurrent-GC lifetime. Unavailable/unknown never counts as qualified.
- Agentless experiments never change a running target's flags. No GITHUB_TOKEN handling, seccomp installation or JVM-floor change.
- Rebuild/pin changed experimental overlays separately; never reuse production fingerprints for modified native code.
- Preserve user work/scoped commits; production API/protocol/whitelist expansion requires separate explicit approval.

---

## File Structure

|File|Responsibility|
|---|---|
|Create `profiler/hotspot-agentless/internal/qualification/jitgc/proof.go`|Offline conservative lifetime/coverage gate|
|Create `profiler/hotspot-agentless/internal/qualification/jitgc/proof_test.go`|Missing/stale/reused-code negative controls|
|Create `experiments/hotspot-agentless/fixtures/java/RuntimeCandidateFixture.java`|Unique leaf/router operations after explicit warmup/gate|
|Create `experiments/hotspot-agentless/scripts/verify-runtime-candidate.py`|Token/frame/order/generation report verification|
|Create `experiments/hotspot-agentless/tests/unit/test-runtime-candidate.py`|Wrong route/duplicate token/generation and all-unavailable oracle tests|
|Create `docs/plans/2026-10-05-hotspot-jit-gc-evidence.md`|Exact source/binary/lifetime matrix and per-tuple decision|
|Read `internal/identity/target.go`, `observe_linux.go`, `patches/profiler-additions/interpreter/hotspot/epoch*.go.txt`|Current refusal/cache/metadata ownership; unchanged until separate promotion approval|

### Task1: Conservative candidate proof model

**Interfaces:** New Go package jitgc exposes `Facts`, `Evaluate(Facts) error`. This is an offline report gate, not a production admission authority.

Use `package jitgc`; proof.go imports fmt and proof_test.go imports testing.

- [ ] Write red tests:

```go
func TestCompiledCaptureNeedsLifetimeLease(t *testing.T) {
 f := Facts{PinnedBuild:true, Useful:10, Marked:10, Compiler:true,
  CaptureGeneration:7, DecodeGeneration:7, MetadataLease:true}
 if Evaluate(f)==nil { t.Fatal("safepoint/generation alone admitted compiled code") }
 f.CodeLease=true
 if err:=Evaluate(f); err!=nil { t.Fatal(err) }
 f.DecodeGeneration=8
 if Evaluate(f)==nil { t.Fatal("reused nmethod admitted") }
}
func TestNoUsefulFramesIsNotQualification(t *testing.T) {
 if Evaluate(Facts{PinnedBuild:true,Marked:10,MetadataLease:true})==nil {
  t.Fatal("all unavailable falsely qualified")
 }
}
```

- [ ] Run `go test -modfile build/experiment.go.mod ./internal/qualification/jitgc`; expect undefined Facts/Evaluate.
- [ ] Implement model:

```go
type Facts struct {
 PinnedBuild, Compiler, ConcurrentGC bool
 MetadataLease, CodeLease, ConcurrentReclamationLease bool
 CaptureGeneration, DecodeGeneration uint64
 Marked, Useful, Wrong, Mixed, OutOfOrder uint64
}
func Evaluate(f Facts) error {
 if !f.PinnedBuild || f.Marked==0 || f.Useful==0 || f.Useful>f.Marked ||
  f.Wrong!=0 || f.Mixed!=0 || f.OutOfOrder!=0 || !f.MetadataLease {
  return fmt.Errorf("runtime candidate lacks useful lifetime-correct evidence")
 }
 if f.Compiler && (!f.CodeLease || f.CaptureGeneration==0 ||
  f.CaptureGeneration!=f.DecodeGeneration) {
  return fmt.Errorf("compiled generation lease absent or changed")
 }
 if f.ConcurrentGC && !f.ConcurrentReclamationLease {
  return fmt.Errorf("concurrent metadata reclamation not protected")
 }
 return nil
}
```

CodeLease proves compiled-code lifetime; ConcurrentReclamationLease independently
proves concurrent metadata lifetime, not reuse of the Serial safepoint boolean. Facts are derived
from proof/corpus documents; fabricated test booleans do not authorize live support.
Add changed build, zero generation, wrong/mixed/order failures and missing lease tests.
- [ ] Run host/race tests plus existing `./internal/identity` refusal tests; commit model/tests.

### Task2: Useful exact-token JVM oracle

**Interfaces:** Fixture args `<directory> <count>`, count1..400; writes ready marker
after warmup, waits for measure marker, writes ordered tokens through A/B paths,
signals done and remains alive until release. Verifier consumes token manifest and
captured JSONL; never uses an oracle stack as production frames.

- [ ] Write verifier test where an event for A has B's frame list and assert nonzero
exit; also test correct ordered frames and all-unavailable unqualified output.
Use the existing measured schema1projection as a seed, copying per-occurrence IDs;
do not call these synthetic packets live evidence.

Test API is `verify(manifest: dict, rows: list[dict]) -> dict`; load the script with
importlib. Manifest contains host `pid`, `start_ticks`, and full filename `tokens`
mapping to ordered method substrings. Wrong route/generation/duplicates must raise;
all-unavailable with empty frames must return useful0/unqualified.

Actual wrong-route test body (unittest and importlib-load the planned script):

```python
def test_wrong_route(self):
    manifest={"pid":123,"start_ticks":456,"tokens":{"/fixture/A":["leaf","routeA"]}}
    rows=[{"kind":"event","event_id":"s:0:1","event":{"filename":"/fixture/A","host_pid":123,"process_start_ticks":456}},
          {"kind":"enrichment","event_id":"s:0:1","evidence":{"attribution":"event_correlated","frames":[{"function_name":"leaf"},{"function_name":"routeB"}]}}]
    with self.assertRaises(ValueError): module.verify(manifest,rows)
```
- [ ] Implement fixture skeleton:

```java
import java.io.FileInputStream;
import java.nio.file.*;
public class RuntimeCandidateFixture {
 static void leaf(Path p) throws Exception { try(var in=new FileInputStream(p.toFile())) { if(in.read()!=1) throw new AssertionError(); } }
 static void routeA(Path p) throws Exception { leaf(p); }
 static void routeB(Path p) throws Exception { bridge(p); }
 static void bridge(Path p) throws Exception { leaf(p); }
 static void waitFor(Path p) throws Exception { while(!Files.exists(p)) Thread.sleep(10); }
 public static void main(String[] a) throws Exception {
  Path dir=Path.of(a[0]); int count=Integer.parseInt(a[1]);
  if(count<1||count>400) throw new IllegalArgumentException();
  Path warm=dir.resolve("warm"); Files.write(warm,new byte[]{1});
  for(int i=0;i<20000;i++) { if((i&1)==0) routeA(warm); else routeB(warm); }
  for(int i=0;i<count;i++) Files.write(dir.resolve("token-"+i+"-"+((i&1)==0?"A":"B")),new byte[]{1});
  Files.writeString(dir.resolve("ready"),Long.toString(ProcessHandle.current().pid()));
  waitFor(dir.resolve("measure"));
  for(int i=0;i<count;i++) { Path p=dir.resolve("token-"+i+"-"+((i&1)==0?"A":"B")); if((i&1)==0) routeA(p); else routeB(p); Thread.sleep(10); }
  Files.writeString(dir.resolve("done"),"done"); waitFor(dir.resolve("release"));
 }
}
```

Write token manifest before ready with expected method subsequences and process run
nonce. Extend fixture for separate disposable-loader generations using the existing
`experiments/hotspot-agentless/fixtures/java/UnloadDriver.java` and
`UnloadG00000.java`; compile fixture classes before starting observation.

Implement verifier core. CLI argparse defines manifest/capture2args, reads manifest
JSON/native JSONL and prints the returned dictionary:

```python
def verify(manifest, rows):
    events, terminals = {}, {}
    for row in rows:
        if row["kind"] == "event" and row["event"]["filename"] in manifest["tokens"]:
            event, key = row["event"], row["event_id"]
            if key in events: raise ValueError("duplicate event")
            if event["host_pid"] != manifest["pid"] or event["process_start_ticks"] != manifest["start_ticks"]:
                raise ValueError("process generation mismatch")
            events[key] = event
        elif row["kind"] == "enrichment":
            key = row["event_id"]
            if key in terminals: raise ValueError("duplicate terminal")
            terminals[key] = row["evidence"]
    useful, seen = 0, set()
    for key, event in events.items():
        token = event["filename"]
        if token in seen: raise ValueError("duplicate token")
        seen.add(token)
        evidence = terminals[key]
        frames = [f["function_name"] for f in evidence["frames"]]
        if evidence["attribution"] != "event_correlated":
            if frames: raise ValueError("unavailable frames are not empty")
            continue
        expected = manifest["tokens"][token]
        if len(frames) < len(expected) or any(name not in frames[i] for i, name in enumerate(expected)):
            raise ValueError("wrong or reordered route")
        useful += 1
    if seen != set(manifest["tokens"]): raise ValueError("missing marked token")
    return {"marked":len(seen), "useful":useful, "qualified_for_review":useful>0}
```

Write manifest before ready using fixture-prefixed leaf/routeA or leaf/bridge/routeB
sequences and actual host PID/start_ticks. This application prefix is not a complete
stack/inline/source-domain claim. Run
`python3 experiments/hotspot-agentless/tests/unit/test-runtime-candidate.py`
before/after implementation with correct/swapped/unavailable/missing/duplicate tests.
- [ ] Run pinned candidates independently: `-XX:+UseSerialGC -XX:TieredStopAtLevel=1`
(C1), `-XX:+UseSerialGC -XX:-TieredCompilation` (C2), and
`-Xint -XX:+UseG1GC` (interpreter/G1). Read live flags/logs; command-line intent is
not admission evidence. Keep target through finalization. Capture only via existing
explicit experimental schema1 unknown-identity mode, preserving its unvalidated label.

Observe UseCompiler/UseSerialGC/UseG1GC and compiler-tier logs independently;
compiler=true alone does not distinguish C1/C2. Current Discover/ValidateProfile
must not admit candidates. A separate read-only observer, if needed, keeps mapped
build/PID/permission validation and is not called by the production collector.
- [ ] Verify exact event's token/generation/frame order, useful counts, unavailable
empty frames, code-cache pressure/deoptimization, loader unloading and PID/exec
refusal controls. Record initial wrong/unavailable outcomes, not just passing runs.
- [ ] Commit independently testable fixture/verifier and raw-artifact provenance.

### Task3: Lifetime proof and promotion decision

**Files:** Evidence document; production files remain unchanged unless a new detailed
native implementation plan is explicitly approved. **Interfaces:** Task1 Facts and
Task2 corpus feed a per-tuple `qualified_for_review`/`unqualified` decision.

- [ ] Audit exact JDK source `520406d87195` and mapped libjvm SHA/build ID. Record
nmethod/codeblob allocation, compile IDs, deopt/unlink/purge, code-cache slot reuse,
class metadata unloading and each GC's reclamation outside safepoints. Cite exact
source paths/lines and binary symbol sizes; record absent counters/leases as missing.
- [ ] Specify capture-time code generation and immutable definition lease; the
same generation must span decode or frames become unavailable and affected LRUs
roll back. Include a deterministic reused-code-address/method replacement test in
the future overlay plan. Permission and exec failures remain session-fatal.
- [ ] Evaluate each tuple separately with the model and real corpus. A tuple with
no source-proved lifetime mechanism is unqualified even if the workload happened
to produce zero wrong frames. Serial interpreter production remains supported.
- [ ] Only if qualified, draft the native capture/decoder changes, layout bounds,
patch/ABI/wire revision and public capability negotiation for explicit approval.
Do not edit ValidateProfile to allow UseCompiler or G1 as a shortcut. Require all
existing recovery/unloading/exec/held-callback tests plus fresh candidate matrix.
- [ ] Save evidence and decision, run Go host/race/refusal tests, backlog/links/
whitespace/root build; commit `docs(profiler): record pinned JIT and GC qualification`.

## Completion gate

Working qualification software and actual per-tuple evidence exist. Wider production
support requires a proved native lifetime mechanism and separately approved detailed
promotion, not successful fixture flags or an all-unavailable result.
