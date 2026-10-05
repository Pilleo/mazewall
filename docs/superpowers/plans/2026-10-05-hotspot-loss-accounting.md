---
document_type: execution_plan
base_revision: eacd0081
status: approved_inline_execution_with_promotion_gate
---

# HotSpot Loss Accounting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegate only after operator selection.

**Goal:** Establish authoritative selected-openat conservation accounting and an explicit all-source proof gate for complete collection-loss reporting.

**Architecture:** First implement a pure conservative conservation evaluator, then capture quiescent per-CPU attempt/submit/reserve-failure counters from the actual BPF producer. Inventory definition/enrichment/wire/retention boundaries separately and keep whole-session completeness unknown until every source has a final proof. Selected-event accounting never authorizes policy conversion.

**Tech Stack:** Existing Go/Cilium/BPF collector and overlay ownership, Go host/race/kernel tests, Kotlin schema2 diagnostic mapping.

**Spec:** `docs/plans/2026-10-05-hotspot-performance-capability-design.md`, loss section; profiler design and current native finalization/health contracts.

## Global Constraints

- JDK25/library floor22/Linux-amd64; production pinned25.0.4.1+1-LTS interpreter/Serial synchronous openat unchanged.
- Readiness<=10s/session<=60s/stop<=2 remaining seconds/event deadline1s; no increased ring/perf/worker/pending budgets.
- Kotlin4096 occurrences/33,554,432bytes; physical joins/retirement, delivery accounting, retention and whole collection are distinct.
- Unknown/failed/overflowing/reset counters never become zero; final snapshots only after producer quiescence, reader/callback joins and checked ownership retirement.
- All possible CPU slots, including offline/hotplug cases, are sampled; sequence gaps require final-tail evidence.
- No swallowed EPERM/EACCES, new dependency/breaking API, GITHUB_TOKEN handling or seccomp/JVM-floor changes.
- Changed native maps/overlays get new computed patch fingerprint and explicit asset/protocol compatibility decision; never relabel old artifacts.
- Policy coverage remains incomplete even if selected-stream loss is exactly known; preserve user work/scoped commits.

---

## File Structure

|File|Responsibility|
|---|---|
|Create `profiler/hotspot-agentless/internal/accounting/conservation.go`|Pure overflow-safe selected-source proof|
|Create `profiler/hotspot-agentless/internal/accounting/conservation_test.go`|Gaps, unknown counts, overflow, held-source and late-tail controls|
|Modify `profiler/hotspot-agentless/gadgets/openat-stack/program.bpf.c`|Per-CPU counters at target-selected reserve/submit boundary|
|Modify `profiler/hotspot-agentless/internal/capture/event_finalization.go`, `health.go`|Final snapshot/conservation integration without premature global completeness|
|Modify `profiler/hotspot-agentless/internal/model/record.go`|Explicit selected-accounting diagnostic record|
|Create `profiler/hotspot-agentless/internal/accounting/inventory.go`, `inventory_test.go`|All-source missing-proof gate|
|Create `docs/plans/2026-10-05-hotspot-loss-accounting-evidence.md`|Native counter denominator/source inventory/promotion decision|
|Read Gadget event-finalization owner/backend and profiler definition-drain overlay files|Existing actual ownership barriers; do not replace them with counter equality|

### Task1: Overflow-safe conservative conservation

**Interfaces:** `Counts`, `Proof`, `Evaluate(Counts) Proof` in accounting package.
Unknown or inconsistent input produces `Complete=false`; zero lost is only a known
successful equation, never a default missing value.

Use `package accounting`; conservation_test.go imports testing, inventory.go
imports sort, and the per-CPU aggregation helper imports math/bits.

- [ ] Write red tests:

```go
func TestKnownReserveAndTransportLoss(t *testing.T) {
 p:=Evaluate(Counts{Known:true,Quiesced:true,Joined:true,Attempted:10,Submitted:8,ReserveFailed:2,Received:7,Terminals:7})
 if !p.Complete || p.Lost!=3 { t.Fatal(p) }
}
func TestMissingTailAndUnknownCountersNeverComplete(t *testing.T) {
 for _,c:=range []Counts{
  {Known:false,Quiesced:true,Joined:true},
  {Known:true,Quiesced:false,Joined:true},
  {Known:true,Quiesced:true,Joined:true,Attempted:10,Submitted:8,ReserveFailed:1},
 } { if Evaluate(c).Complete { t.Fatal(c) } }
}
```

- [ ] Run `go test -modfile build/experiment.go.mod ./internal/accounting`; expect missing types.
- [ ] Implement:

```go
type Counts struct {
 Known, Quiesced, Joined bool
 Attempted, Submitted, ReserveFailed, Received, Terminals uint64
}
type Proof struct { Complete bool; Lost uint64; Reason string }
func Evaluate(c Counts) Proof {
 if !c.Known || !c.Quiesced || !c.Joined { return Proof{Reason:"source not final"} }
 if c.Submitted>c.Attempted || c.ReserveFailed!=c.Attempted-c.Submitted ||
  c.Received>c.Submitted || c.Terminals!=c.Received {
  return Proof{Reason:"conservation mismatch"}
 }
 return Proof{Complete:true,Lost:c.Attempted-c.Received}
}
```

Sum per-CPU uint64 fields using `math/bits.Add64`; any carry invalidates Known.
Add MaxUint64, missingCPU, changed counter generation, terminal pending and duplicate
delivery tests. This proof covers selected event delivery, not all definitions.
- [ ] Run host/race tests and existing health tests; commit pure accounting.

### Task2: Actual BPF denominators and quiescent tail

**Interfaces:** New per-CPU map entry4uint64fields `attempted`, `submitted`,
`reserve_failed`, sticky `overflow`; snapshot associates session/process birth/map generation and all
possible CPU slots. Native event layout stays unchanged if only a map is added.

The added map is reviewed fixed accounting state:32bytes per possible CPU, not a
larger event/perf/pending buffer. Record possibleCPU cardinality and allocation in
the artifact evidence; an unsupported map/cardinality is an explicit refusal.

- [ ] Add real-kernel pressure test using existing event-finalization test owner:
known selected tokens, small isolated test ring, genuine reserve failures, held
callback and final producer detach. Assert conservation only after joins; failure
to read a CPU slot or held callback yields incomplete proof with retained owner.
Observe red missing counter/map capability before production producer changes.
- [ ] Add map/counter code at the existing selected PID/start-ticks boundary:

```c
struct accounting_counts { __u64 attempted, submitted, reserve_failed, overflow; };
struct {
 __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
 __uint(max_entries, 1);
 __type(key, __u32);
 __type(value, struct accounting_counts);
} selected_counts SEC(".maps");
static __always_inline void account_inc(struct accounting_counts *counts, __u64 *field)
{
 if (__sync_fetch_and_add(field,1)==~0ULL)
  __sync_fetch_and_or(&counts->overflow,1);
}
// After target PID/birth match, before reserve:
struct accounting_counts *counts=bpf_map_lookup_elem(&selected_counts,&zero);
if (!counts) return 0; /* final missing-map/read proof remains incomplete */
account_inc(counts,&counts->attempted);
// On gadget_reserve_buf failure:
account_inc(counts,&counts->reserve_failed);
// Immediately after the existing successful gadget_submit_buf:
account_inc(counts,&counts->submitted);
```

Count attempted immediately after PID/birth selection, before sequence-map lookup
or any later early return. Unexplained early returns cause conservation mismatch,
not completed proof. Do not alter ring size, stack helpers, event sampling or deadline.
Sticky overflow makes Known=false; sum overflow/changed map generation also reject
proof. Record window as the attached selected
producer lifetime, not merely the user lambda or ready-to-finish wall time.
- [ ] Extend existing finalization backend to read the map non-destructively only
after actual producer detach and all receiver callbacks have joined. Use Cilium's
possible-CPU cardinality; do not use runtime.NumCPU as the map slot denominator.
Feed final Counts to Task1 evaluator; preserve errors through the existing owner.
- [ ] Expose `selected_event_accounting` diagnostic with scope/window/counts/reason.
Keep existing `CollectionHealth` and whole-session loss completeness conservative.
Regenerate experimental overlay fingerprint/assets; run actual pressure/late-tail/
event-held-callback/definition8and5000callback/exec/unloading/recovery tests.
- [ ] Run existing Kotlin parser/coverage/copy/policy refusal tests on updated
fixtures/pins; unknown diagnostic fields remain raw-preserved. Commit native
accounting/pin changes only after explicit ABI/schema review.

### Task3: All-source inventory and completeness promotion gate

**Interfaces:** `Inventory map[string]bool`, `Missing(Inventory) []string`; required
lanes selected_events, definitions, enrichment, wire, retention. A true flag means
that lane's real conservation/ownership proof is final, not merely its reader exited.

- [ ] Add red test with all lanes except definitions and require it in Missing;
an absent lane must not default true. Add all-known case and a false lane case.

```go
func TestMissingDefinitionProofBlocksCompleteness(t *testing.T) {
 i:=Inventory{"selected_events":true,"enrichment":true,"wire":true,"retention":true}
 missing:=Missing(i)
 if len(missing)!=1 || missing[0]!="definitions" { t.Fatal(missing) }
 i["definitions"]=true
 if len(Missing(i))!=0 { t.Fatal("known proof rejected") }
 i["extra_producer"]=true
 if len(Missing(i))==0 { t.Fatal("new producer bypassed inventory") }
}
```
- [ ] Implement fixed ordered requirements:

```go
type Inventory map[string]bool
func Missing(i Inventory) []string {
 var missing []string
 required:=map[string]bool{}
 for _,lane:=range []string{"selected_events","definitions","enrichment","wire","retention"} {
  required[lane]=true
  if !i[lane] { missing=append(missing,lane) }
 }
 for lane:=range i { if !required[lane] { missing=append(missing,"unrecognized:"+lane) } }
 sort.Strings(missing)
 return missing
}
```

- [ ] Inventory each actual source: event reserve/submit/transport; definition
producer admission/submit/perf loss/native task rejection/dictionary delivery;
enrichment accepted/resolved/unavailable/skipped/deadline terminals; wire successful
write/read/parse/EOF; Kotlin retained/discarded/pending pairs. Include auxiliary
perf/netlink/task callbacks in definition source finality. Attach concrete counters
and ownership barriers to every lane; missing instrumentation leaves the lane false.
- [ ] Run fault controls for each lane: drop first definition/recover, real perf/ring
pressure, queue rejection, write failure, short/truncated JSONL, retention0, target
exec and held callbacks. Require no invented zero counter or completed inventory.
- [ ] Only if no missing lane, propose precise `collection_loss_complete` scope
and updated diagnostic version for review. Successful selected event accounting
alone leaves this flag false. Policy coverage remains false regardless. Do not
weaken `HotSpotEvidenceDocument.decode` or public result-copy refusal to accept an
unproved complete flag. A separate approved public model/schema task is required
for a stronger collection attestation.
- [ ] Save denominator/inventory/fault evidence, run Go host/race/native gates,
Kotlin ABI/policy regressions/backlog/links/lint/rootbuild; commit evidence.

## Completion gate

Authoritative selected-stream loss is known after real quiescence/joins, and every
unknown whole-source boundary is visible. Global completeness promotion is blocked
until all-source instrumentation and versioned attestation are actually proved.
