---
document_type: execution_plan
base_revision: 6e0b34d6
status: draft_requires_operator_approval
---

# HotSpot Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegate only if the operator selects it.

**Goal:** Fix four reproduced qualification integrity/ownership defects and revalidate evidence before extending production providers, runtimes or loss claims.

**Architecture:** Use one bounded schema1/session/process/pair validator and exact method-name extractor for both oracle tools. Give container cleanup actual acquisition receipts, checked remote absence and retained pending owners; validate conservation per CPU before aggregation. Keep production readout and all promotion gates unchanged, then run existing artifacts through the hardened tools.

**Tech Stack:** Python3 standard library/unittest, existing Go accounting/race/kernel tests, existing pinned Docker/JDK/native fixtures; no new dependencies.

**Spec:** `docs/plans/2026-10-05-hotspot-implementation-review.md` (R1–R4) and `docs/plans/2026-10-05-hotspot-performance-capability-design.md`.

## Global Constraints

- Qualification is experimental schema1; unsupported/missing schema/session/identity is rejected, not silently treated as valid.
- Production remains Linux-amd64/pinned Temurin25.0.4.1+1-LTS `-Xint -XX:+UseSerialGC` synchronous openat.
- Readiness<=10s/session<=60s/stop<=2 remaining seconds/enrichment deadline1s; native budgets and4096/33,554,432byte Kotlin retention unchanged.
- No dependencies, breaking APIs, production native provider/runtime whitelist/global completeness change.
- EPERM/EACCES and original failures remain visible; killing/waiting a Docker client cannot certify container retirement.
- Captured event/session/process birth/BOOTTIME remains immutable; no nearest-TID/token-basename or later-stack substitution.
- Native counter contradiction/overflow/unknown source yields incomplete proof. Policy coverage remains false.
- Preserve user edits, stage only owned files and partial index rows. Scoped local commits; no default-branch push or GITHUB_TOKEN handling.

---

## File Structure

|File|Responsibility|
|---|---|
|Create `experiments/hotspot-agentless/scripts/candidate_evidence.py`|Shared bounded capture/oracle identity and method-name validation|
|Modify `verify-runtime-candidate.py`, `verify-loom-candidate.py` in the same directory|Consume validated index; keep corpus qualification separate|
|Create `experiments/hotspot-agentless/tests/unit/test-candidate-evidence.py`|Malformed schema/identity/pairing/number/oracle controls|
|Modify existing runtime/Loom verifier tests|Valid full fixture contract and false-useful name controls|
|Create `experiments/hotspot-agentless/scripts/candidate_ownership.py`|Actual container receipts and checked bounded retirement|
|Create `experiments/hotspot-agentless/tests/unit/test-candidate-ownership.py`|Removal/query/timeout/late-acquisition/wrapper failure controls|
|Modify `experiments/hotspot-agentless/scripts/run-capability-candidates.py`|Identity context, receipt ownership, result after retirement|
|Modify `profiler/hotspot-agentless/internal/accounting/conservation.go` and test|Per-CPU equation before summation|
|Create `docs/plans/2026-10-05-hotspot-review-remediation-evidence.md`|Red/green findings and revalidated artifact decisions|

Tasks1/2 share validation and are serial. Task3 is independent but must pass before
any live rerun. Task4 is independent. Task5 depends on all four. Fold fixture/setup/
docs into their owning task; each commit is independently reviewable.

### Task1: Complete capture/oracle identity and pairing (R1)

**Files:** Shared helper, both verifiers, runner identity metadata, shared/existing tests.
**Interfaces:** `validate_capture(rows, expected_pid:int, expected_ticks:int) -> CaptureIndex`;
`CaptureIndex(session_id:str, events:dict, terminals:dict, summary:dict)`;
`load_oracle(lines) -> dict[str,dict]` rejects duplicate tokens.
Loom becomes `verify(oracle, records, *, expected_pid, expected_ticks, expected_paths)`.
Do not obtain expected identity from the same capture being validated.

- [ ] Add failing shared tests loading helper via importlib; put the scripts directory
on sys.path for normal helper imports. Define valid fixture rows in this test:

```python
def valid_capture():
    event={"session_id":"s","event_id":"s:0:1","host_pid":123,"host_tid":124,
           "process_start_ticks":456,"clock_domain":"CLOCK_BOOTTIME",
           "event_kind":"openat_handler_entry","capture_boot_ns":1,"filename":"/fixture/A"}
    return [
      {"schema_version":1,"kind":"collector_attached","session_id":"s"},
      {"schema_version":1,"kind":"event","session_id":"s","event_id":"s:0:1","event":event},
      {"schema_version":1,"kind":"enrichment","session_id":"s","event_id":"s:0:1",
       "evidence":{"attribution":"unavailable","frames":[]}},
      {"schema_version":1,"kind":"session_summary","session_id":"s",
       "summary":{"enrichment":{"received":1,"resolved":0,"unavailable":1,"skipped":0},
                  "terminal_accounting_complete":True}}]
```

Deep-copy fixtures and change schema99, remove session, foreign outer ID, changed
nested ID, PID/birth/TID0, float/bool uint, omit carrier-only terminal, duplicate
oracle token and replace full filename with another directory's same basename.
Every variant must raise ValueError; valid event-before-attached order must pass.
- [ ] Run `python3 experiments/hotspot-agentless/tests/unit/test-candidate-evidence.py`;
expect missing helper/validators, then current unsafe acceptance when adapting tests.
- [ ] Implement strict primitives/index:

```python
from dataclasses import dataclass

def uint(value, maximum, name, positive=False):
    if type(value) is not int or not (int(positive) <= value <= maximum):
        raise ValueError("invalid uint: " + name)
    return value

@dataclass(frozen=True)
class CaptureIndex:
    session_id: str
    events: dict
    terminals: dict
    summary: dict

def validate_capture(rows, expected_pid, expected_ticks):
    uint(expected_pid, 0x7fffffff, "expected PID", True)
    uint(expected_ticks, 0xffffffffffffffff, "expected birth", True)
    session=None; events={}; terminals={}; summary=None; attached=0
    if not 0 < len(rows) <= 65536: raise ValueError("capture row limit")
    for row in rows:
        if summary is not None: raise ValueError("record after summary")
        if not isinstance(row,dict) or type(row.get("schema_version")) is not int or row["schema_version"]!=1:
            raise ValueError("unsupported qualification schema")
        sid=row.get("session_id")
        if not isinstance(sid,str) or not 0<len(sid)<=128: raise ValueError("invalid session")
        if session is None: session=sid
        if sid!=session: raise ValueError("mixed sessions")
        kind=row.get("kind")
        if kind=="collector_attached":
            attached+=1
            if attached!=1: raise ValueError("duplicate attachment")
        elif kind in ("event","enrichment"):
            key=row.get("event_id")
            if not isinstance(key,str) or len(key)>256 or not key.startswith(sid+":"):
                raise ValueError("foreign occurrence")
            dest=events if kind=="event" else terminals
            if key in dest: raise ValueError("duplicate occurrence side")
            value=row.get(kind if kind=="event" else "evidence")
            if not isinstance(value,dict): raise ValueError("missing payload")
            if kind=="event":
                if value.get("session_id")!=sid or value.get("event_id")!=key: raise ValueError("nested occurrence mismatch")
                if uint(value.get("host_pid"),0x7fffffff,"PID",True)!=expected_pid or uint(value.get("process_start_ticks"),0xffffffffffffffff,"birth",True)!=expected_ticks:
                    raise ValueError("target generation mismatch")
                uint(value.get("host_tid"),0x7fffffff,"TID",True)
                uint(value.get("capture_boot_ns"),0xffffffffffffffff,"capture clock")
                if value.get("clock_domain")!="CLOCK_BOOTTIME" or value.get("event_kind")!="openat_handler_entry": raise ValueError("unsupported clock/event")
                if not isinstance(value.get("filename"),str): raise ValueError("missing path")
            else:
                frames=value.get("frames"); attribution=value.get("attribution")
                if attribution not in ("event_correlated","unavailable","skipped") or not isinstance(frames,list) or len(frames)>4096: raise ValueError("invalid terminal")
                if attribution!="event_correlated" and frames: raise ValueError("unavailable frames")
                if any(not isinstance(f,dict) or not isinstance(f.get("function_name"),str) for f in frames): raise ValueError("invalid frame")
            dest[key]=value
        elif kind=="session_summary":
            summary=row.get("summary")
            if not isinstance(summary,dict): raise ValueError("missing summary")
        elif kind=="recovery":
            if not isinstance(row.get("recovery"),dict): raise ValueError("invalid recovery")
        else: raise ValueError("unsupported record kind")
    if attached!=1 or summary is None or set(events)!=set(terminals): raise ValueError("incomplete capture")
    if summary.get("terminal_accounting_complete") is not True: raise ValueError("unverified terminal accounting")
    counters=summary.get("enrichment")
    if not isinstance(counters,dict): raise ValueError("missing counters")
    observed={"received":len(events),"resolved":0,"unavailable":0,"skipped":0}
    for value in terminals.values():
        observed[{"event_correlated":"resolved","unavailable":"unavailable","skipped":"skipped"}[value["attribution"]]]+=1
    for name,count in observed.items():
        if uint(counters.get(name),0xffffffffffffffff,name)!=count: raise ValueError("counter mismatch")
    return CaptureIndex(session,events,terminals,summary)

def load_oracle(lines):
    result={}
    for row in lines:
        token=row.get("token")
        if not isinstance(token,str) or not token or token in result: raise ValueError("duplicate/invalid oracle token")
        uint(row.get("logical_id"),0xffffffffffffffff,"oracle logical ID",True)
        result[token]=row
    return result
```

Add bounded `read_capture(path)` using binary readline(max1,048,576+1), mandatory
newline, strict UTF8/json.loads (reject NaN/Infinity via parse_constant), row cap and
ValueError wrapping for missing/type failures. No whole-file unbounded input loading.

```python
import json
def read_capture(path):
    rows=[]
    def invalid_constant(value): raise ValueError("non-finite JSON number: "+value)
    with path.open("rb") as stream:
        while True:
            raw=stream.readline(1048578)
            if not raw: break
            if len(raw)>1048577 or not raw.endswith(b"\n"): raise ValueError("capture line limit/truncation")
            try: row=json.loads(raw.decode("utf-8"),parse_constant=invalid_constant)
            except (UnicodeError,json.JSONDecodeError) as error: raise ValueError("malformed capture row") from error
            rows.append(row)
            if len(rows)>65536: raise ValueError("capture row limit")
    return rows
```
- [ ] Both verifiers first validate the whole capture before token filtering or
carrier-only continue. Runtime uses manifest PID/birth/full paths; Loom uses independent
expected identity and full `expected_paths` mapping, not basename alone. Validate
native logical_id/mount uint64s and object_lease/parent_available exact booleans.
Require source candidate_native for mounted candidates; oracle source is refused.
Keep lifetime/production qualified false; this fixes evidence parsing, not proof.
- [ ] Runner stores expected PID/birth read while target is alive in identity.json:

```python
pid=int((root/"ready").read_text())
stat=Path(f"/proc/{pid}/stat").read_text()
ticks=int(stat.rsplit(")",1)[1].split()[19])
identity={"pid":pid,"start_ticks":ticks,"fixture_root":str(root)}
(root/"identity.json").write_text(json.dumps(identity)+"\n")
```

Update valid old minimal synthetic tests to the explicit fixture contract; do not
relax validation to accommodate incomplete tests. Run shared/runtime/Loom/readiness
unit scripts and commit scoped R1 changes with the issue/evidence links.

### Task2: Exact application method names (R2)

**Files:** Shared helper and both verifier tests/callers.
**Interfaces:** `method_identity(display:str) -> tuple[str,str] | None` and
`application_prefix(frames, owner) -> list[tuple[str,str]]`. Current ASCII fixture
classes are the supported oracle naming domain; other displays remain unavailable.

- [ ] Add failing controls: WrongFixture.leafCorrupt, Fixture.leafCorrupt,
Fixture.routeACorrupt, out-of-order exact names. Different owner/native-only produces
no useful application frames; same owner/wrong method rejects. Real renderer display
`void Fixture.leaf(java.nio.file.Path)` passes. Include constructors and malformed
display strings; do not use timestamp/TID or arbitrary method substring matching.
- [ ] Run the exact-name test in test-candidate-evidence.py and both existing oracle
tests; expect the current useful1 false positive before replacement.
- [ ] Implement exact extractor:

```python
import re
METHOD=re.compile(r"^(?:[^\s]+\s+)?(?P<owner>[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.(?P<method>[A-Za-z_$][\w$]*|<init>|<clinit>)\([^)]*\)$")
def method_identity(display):
    if not isinstance(display,str): raise ValueError("invalid method display")
    match=METHOD.fullmatch(display)
    return None if match is None else (match["owner"],match["method"])
def application_prefix(frames,owner):
    names=[method_identity(frame["function_name"]) for frame in frames]
    return [name for name in names if name is not None and name[0]==owner]
```

Parse manifest expected identifiers with rsplit('.',1), require one exact owner,
then compare `application[:len(expected)] == expected`. Native-only/no exact owner
stays application_unavailable; any present wrong/reordered application prefix rejects.
Keep scope application_route_prefix; no full-stack claim or production qualification.
- [ ] Revalidate retained actual C1/C2/G1 packets; record any changed counts rather
than preserving historical numbers by weakening the matcher. Run all Python tests;
commit R2 helper/callers/tests separately from R1.

### Task3: Actual remote ownership and failure-preserving cleanup (R3)

**Files:** New candidate_ownership.py/test; runner acquisition/finally/result flow.
**Interfaces:** Frozen `ContainerOwner(id:str,name:str,label:str)`,
`CleanupReport(pending:tuple[ContainerOwner,...], pending_wrappers:tuple, failures:tuple[BaseException,...])`,
`retire(docker,owners,wrappers,*,run=subprocess.run,clock=time.monotonic) -> CleanupReport`.
`CandidateCleanupError` retains report and original cause. No invented ownership.

- [ ] Write red injected-runner tests: remote ps/removal returncode1, TimeoutExpired,
permission failure, wrapper wait/kill failure, removal success but still-listed ID,
and a primary RuntimeError plus cleanup failure. Assert all wrappers receive a join
attempt, pending IDs are retained, and primary exception object/cause is preserved.
Success requires a successful query confirming actual absence, not just wrapper exit.
- [ ] Implement acquire through checked `docker create --name NAME --label
io.mazewall.qual.session=NONCE ...` stdout64hex receipt, then `docker start --attach ID`.
Validate receipt; store it before starting. Track timed-out/ambiguous create separately
as pending acquisition and recover via matching name+session label, never blindly
remove an unrelated name. The native duration/target gate limits remain unchanged.
- [ ] Implement bounded retirement contract:

```python
from dataclasses import dataclass
@dataclass(frozen=True)
class ContainerOwner:
    id: str
    name: str
    label: str
@dataclass(frozen=True)
class CleanupReport:
    pending: tuple
    pending_wrappers: tuple
    failures: tuple

def retire(docker,owners,wrappers,*,run=subprocess.run,clock=time.monotonic):
    deadline=clock()+20.0
    pending=[]; pending_wrappers=[]; failures=[]
    def call(args):
        left=deadline-clock()
        if left<=0: raise TimeoutError("cleanup deadline")
        return run(docker+args,capture_output=True,text=True,timeout=min(5.0,left))
    def present(owner):
        query=call(["ps","-a","--no-trunc","--filter","id="+owner.id,"--format","{{.ID}}"])
        if query.returncode!=0: raise RuntimeError(query.stderr or "remote state unknown")
        ids=query.stdout.splitlines()
        if any(value!=owner.id for value in ids): raise RuntimeError("ambiguous remote identity")
        return bool(ids)
    for owner in owners:
        try:
            if present(owner):
                removed=call(["rm","-f",owner.id])
                if removed.returncode!=0: raise RuntimeError(removed.stderr or "remote remove failed")
            if present(owner): raise RuntimeError("owned container remains")
        except BaseException as error:
            pending.append(owner); failures.append(error)
    for process in wrappers:
        if process is None: continue
        try:
            process.wait(timeout=max(.001,min(5.0,deadline-clock())))
        except BaseException as error:
            failures.append(error)
            try:
                process.kill(); process.wait(timeout=5)
            except BaseException as late:
                pending_wrappers.append(process)
                failures.append(late)
    return CleanupReport(tuple(pending),tuple(pending_wrappers),tuple(failures))
```

The ownership module imports subprocess/time before these declarations. Its unit
tests supply deterministic run/clock doubles; the default production-tool path
uses the existing stdlib implementations.

Public task/native stop budgets are not changed by this explicit experimental
cleanup deadline. Catching exceptions records failure, not a successful fallback.
CandidateCleanupError must retain actual pending wrapper handles in memory for
retry; cleanup.json records their PID/status plus remote receipt IDs. Retain pending
acquisition name+session-label requests separately when create outcome is unknown.
Serialize error class/message for JSON but retain original exception objects in
memory and chain the first cause in CandidateCleanupError. Permission errors and
cancellation must not be reduced to a discarded string or a successful fallback.
After an rm failure, an additional successful absence query can remove pending
ownership, but must still retain the failure rather than silently downgrade it.
- [ ] Runner collects primary/result, runs retirement regardless of prior failure,
writes cleanup.json with pending receipts, then publishes case result. On primary,
attach report/notes and rethrow the same exception; on clean primary but cleanup
failure raise CandidateCleanupError. Never write success before retirement. A
cleanup API timeout must not skip joins or replace the original failure.
- [ ] Run fault tests without Docker, then one explicit named target/helper lifecycle
on the rootful endpoint. Query absence and preserve pending failure cases. Commit
R3 owner/runner/tests; do not mutate operator endpoints/hooks or change JVM privilege.

### Task4: Per-CPU contradiction refusal (R4)

**Files:** conservation.go and conservation_test.go.
**Interfaces:** Existing `Aggregate([]CPUCounts,int,bool) (Counts,error)` unchanged.

- [ ] Add failing Go test:

```go
func TestCPUTuplesCannotCancelContradictions(t *testing.T) {
 rows:=[]CPUCounts{{Attempted:1,Submitted:2},{Attempted:1,Submitted:0}}
 if _,err:=Aggregate(rows,2,true);err==nil {
  t.Fatal("impossible CPU conservation disappeared into valid totals")
 }
}
```

Also test each single contradiction, MaxUint64 boundary, valid reserve/submit counts
on different CPUs, sticky overflow, unknown generation/cardinality and sum overflow.
- [ ] Run `go test -race -modfile build/experiment.go.mod ./internal/accounting`;
expect red contradictory tuple acceptance.
- [ ] Before the existing sum loop, after sticky-overflow check, add:

```go
if row.Submitted > row.Attempted || row.ReserveFailed != row.Attempted-row.Submitted {
    return Counts{}, fmt.Errorf("per-CPU counter conservation mismatch")
}
```

Use subtraction after the order check, not unchecked Submitted+ReserveFailed.
Keep bits.Add64 and the whole-source Evaluate equations; no invented zero values.
- [ ] Run host/race then the existing explicit rootful `TestKernelOpenatCountersPressureAndOverflow`
with mw_kernel tag/pinned Go image/host PID namespace. Require actual512attempts,
positive pressure and overflow rejection; no unconditional capability skip. Commit
R4 code/test/evidence separately.

### Task5: Revalidation, documentation and next promotion boundary

**Files:** Remediation evidence, four review issues, existing qualification evidence,
public guidance only for corrected factual claims. No new provider/whitelist/map activation.

- [ ] Re-run read-only review repros: each unsafe acceptance now rejects, checked
cleanup retains injected failure/owner, invalid CPU tuple returns error. Record
observed red/green outputs with exact commits and no actual container leak claims.
- [ ] Revalidate retained runtime capture using its manifest expected PID/birth and
exact names. Old virtual oracle lacks independent birth/path context: rerun virtual
cases with new identity.json rather than deriving expected identity from their own
events. Preserve old reports as historical/provisional, append corrected results.
- [ ] Run affected live candidate harness after R3, preserving initial readiness
failure and cold native-only/application-unavailable outcomes. Inspect all exits,
actual absence and summary pairing; zero useful or missing lifetime fields stays
unqualified. No arbitrary stress rerun can substitute for missing capture leases.
- [ ] Run focused and final gates:

```bash
python3 experiments/hotspot-agentless/tests/unit/test-candidate-evidence.py
python3 experiments/hotspot-agentless/tests/unit/test-candidate-ownership.py
python3 experiments/hotspot-agentless/tests/unit/test-runtime-candidate.py
python3 experiments/hotspot-agentless/tests/unit/test-loom-candidate.py
python3 experiments/hotspot-agentless/tests/unit/test-capability-readiness.py
./gradlew :profiler:test --tests '*HotSpotRawJsonTest' --tests '*HotSpotWireReaderTest' \
  --tests '*HotSpotResultCompatibilityTest' :profiler:checkKotlinAbi :profiler:checkLegacyAbi
./scripts/adkw check-backlog
./scripts/lint.sh
git diff --check
./gradlew build
```

Run native tests only for the touched kernel counter/ownership qualification; do
not claim a generic kernel/GC/JIT capability gate passed from host tests.
- [ ] Archive each review issue only after its negative regression and affected
positive/native path pass. Partial-stage index counts/rows from HEAD preserving
the six operator issues. Commit remediation evidence and corrected plan status.

## Next work after this plan

1. Review a production digest bridge/immutable opt-in contract: the existing85ms
AF_ALG qualification is promising, but FD/arena/short-I/O/nonblocking deadline and
old ABI preservation need the separately approved native design.
2. Bind selected counters to the actual Gadget owner and negotiate computed pins/
map/schema compatibility; then instrument definition/enrichment/wire/retention
denominators. Do not set global completeness from the isolated pressure test.
3. Design captured nmethod generation and mount/object leases before any JIT/GC/
Loom whitelist change or adversarial promotion stress. Current proofs are absent.
4. Continue dense parser/serialization work only from new matched profiles; no
budget increase or600case guarantee is justified by current rejection data.

## Completion gate

All four reproduced false-acceptance/ownership/proof defects have negative tests
and affected positive paths pass; historical evidence is revalidated or explicitly
provisional. No production promotion is inferred from hardening these diagnostics.
