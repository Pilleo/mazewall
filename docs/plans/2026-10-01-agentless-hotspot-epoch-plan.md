# Agentless HotSpot Safepoint-Epoch Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task, inline in the existing workspace. Use checkbox steps to track progress. Do not delegate or create worktrees unless the operator changes those constraints.

**Goal:** Eliminate reproduced wrong-generation attribution in the pinned interpreter-only Serial-GC unloading corpus while preserving useful later-generation resolution.

**Architecture:** Capture a stable safepoint epoch, carry it through the existing definition/reference protocol, and guard all HotSpot frame/method/Symbol cache use. Reject ambiguous deferred decoding, preserve validated immutable historical definitions, and reuse existing bounded enrichment and recovery machinery.

**Tech Stack:** Go 1.26; pinned Gadget/OTel fork; LLVM 18 in the existing digest-pinned builder; Linux x86-64; ordinary-user pinned Temurin JDK 25 and privileged collector.

**Spec:** [Approved epoch design](2026-10-01-agentless-hotspot-epoch-design.md).

**Execution status:** Implemented inline on 2026-10-01 with the reviewed
[execution corrections](2026-10-01-agentless-hotspot-epoch-design.md#authorized-execution-corrections).
The historical task recipe below is superseded by the executed contract record;
its rejected shared-reader/mixed-state snippets are not instructions to reuse.
See [actual artifacts and verification](../../experiments/hotspot-agentless/results/identity-investigation.md#implemented-bounded-remedy-2026-10-01).

## Executed contract record

- [x] Exact ELF SHA/build-ID, live compiler/GC flags and start ticks are verified;
  exec attachment precedes discovery, and strict sessions are capped at 60 seconds.
- [x] C/Go/BPF ABI v2 is regenerated with pinned tooling and checked against BTF.
  Producer keys include captured identity, retain 256 entries, and reuse within epochs.
- [x] Per-trace RemoteMemory is immutable relative to the shared instance. The
  bounded ledger preserves the first failure and later EPERM/EACCES separately.
  Failed decoding clears all four existing metadata LRUs; frame-cache admission
  waits for finish. Counter regressions permanently poison the decoder.
- [x] `BeginHotSpotEpoch` returns a private decoder capability; `Symbolize` and
  `Finish(error) HotSpotValidation` operate on its copied memory. Existing public
  `Instance.Symbolize` remains unchanged.
- [x] `libpf.Trace.HotSpotIdentity` is captured evidence;
  `libpf.Trace.HotSpotValidation` is the separate outcome/cause. The required
  session fatal owner is independent of lookup. Historical checked snapshots are
  immutable, and true captured-identity conflicts remain fatal.
- [x] Recovery compares epoch/PID/TID/reference in ABI-v2 keys. Typed JSON uses
  explicit optional epoch, including observed zero. Unsupported profiles refuse
  startup; default capture carries no epoch guarantee.
- [x] Three fresh useful unloading runs and final audited repeat pass all 1600
  tokens/all 80 unloads/zero wrong labels, with ten correct operations per late
  branch. The genuine non-epoch control remains red. Real exec/LRU/prune,
  legacy/strict recovery, host race/upstream and pinned build checks pass.

Actual patch files are `profiler-epoch-{capture,memory,handler}.patch` and
`gadget-epoch.patch`. Runtime exec ownership is in `internal/identity/exec_linux.go`;
session fatal retention is in `internal/capture/fatal.go`. The original parent
issue and review items remain open pending independent review, and broader
runtime/loss/overhead gates are not claimed complete.

## Global Constraints

- Work in `/home/leanid/Documents/code/java/jseccomp`, branch `fast-master`; no worktrees or delegation. Preserve unrelated user edits.
- Keep MazeWall Tier E, C++ agent, InvocationId protocol, task storage, session APIs and USER_NOTIF production implementation unchanged.
- Gadget base: `7097db144ccd8dab15373f27ec98d2c3d22e4588`; profiler base: `ef6cf29492c8a980f889052903fa2dd0c0765cc0`. Retain dependency and image pins; add no dependencies.
- Author scoped patch files and source additions; apply without fuzz to checksum-verified ignored `build/deps/` sources. Never edit the module cache or vendor a profiler tree.
- Reuse producer LRU, correlation cache, waiters, pruning, process discovery, unwinders and enrichment workers. No replacement cache, retry queue or historical metadata side store.
- Preserve producer LRU capacity 256, frame-cache capacity 16384, frame data bound 3072 words, perf data buffer 524288 bytes/CPU, four workers, 1024 pending events, 16 MiB pending payload budget and one-second BOOTTIME enrichment deadline.
- Preserve exact operation-token, branch, frame-order, wrong-generation, pruning and recovery assertions. Observed zero loss remains partial evidence.
- Strict epoch sessions have a maximum lifetime of 60 seconds. Refuse counter values in the upper half of uint64; detected live regression poisons the session instead of establishing a new baseline.
- No direct push to the default branch or upstream submission. Keep the identity issue open until evidence and review support closure.

All commands below run from `experiments/hotspot-agentless` unless labelled
**repository root**. `make deps` must select `build/experiment.go.mod`; never run
tests against the unpatched root module graph. Existing normal diagnostic
acceptance is red and stays red until the remedy actually reaches the live path.

## File structure and ownership

Repository-relative paths:

| Files | Responsibility |
|---|---|
| `internal/identity/{target,target_test,observe_linux,observe_other}.go` under the experiment | Pure profile/bias validation and read-only live discovery; one immutable target descriptor. |
| `patches/profiler-epoch.patch` | Narrow edits to existing provider C/Go source, frame-cache identity and configuration plumbing. |
| `patches/profiler-additions/libpf/{hotspot_epoch,hotspot_epoch_test}.go.txt` | Shared value contract and typed errors. |
| `patches/profiler-additions/interpreter/hotspot/{epoch,epoch_test}.go.txt` | Checked lifetime guard and existing metadata-LRU invalidation. |
| `patches/profiler-additions/processmanager/{hotspot_epoch,hotspot_epoch_test}.go.txt` | Optional capability dispatch and actual frame-cache-hit/rollback tests. |
| `patches/profiler-additions/tracer/hotspot_epoch_test.go.txt` | Real raw trace decode/ABI tests. |
| `patches/profiler-additions/support/hotspot_epoch_test.go.txt` | C/Go/BTF field and map-layout verification. |
| `patches/gadget-epoch.patch` | Additive options/task/structured identity and reporter forwarding. |
| `patches/gadget-additions/pkg/symbolizer/otel/epoch_test.go.txt` | Existing correlation-map/waiter historical/rejection/duplicate tests. |
| Existing `patches/gadget-additions/pkg/symbolizer/otel/{structured,recovery,recovery_test,lru_kernel_test}.go.txt` | Identity agreement, immutable storage, ABI-aware exact recovery, preserved pruning. |
| `scripts/{prepare-gadget.py,rebuild-profiler-ebpf.sh}` | No-fuzz patch preparation and pinned regeneration with artifact hashes. |
| `gadgets/openat-stack/{program.bpf.c,gadget.yaml}` | Event-side epoch bracket, fields and verified address parameter. |
| `internal/capture/{ig,collector,decode,decode_test,lookup,lookup_test,config_test}.go` | CLI configuration, strict startup, event decoding and reason/error routing. |
| `internal/model/{event,stack,stack_test}.go`, new `internal/model/identity.go` | Additive JSON identity and valid-zero serialization. |
| `cmd/mw-hotspot-demo/main.go`, `Makefile`, `scripts/run-demo.sh` | Strict operator mode, bounded duration, reproducible gates. |
| `internal/unloading/{report,report_test}.go`, `tests/integration/unloading_test.go` | Useful per-generation correctness and epoch/reference coverage. |
| Experiment `README.md`, `patches/README.md`, `results/{README,identity-investigation}.md` | Supported profile, exact commands, actual evidence and remaining limits. |

Generated provider targets changed by `profiler-epoch.patch`:
`support/ebpf/{types.h,tracemgmt.h,hotspot_tracer.ebpf.c,interpreter_dispatcher.ebpf.c}`,
`support/{types_def.go,types.go}`, `libpf/trace.go`,
`interpreter/types.go`, `interpreter/hotspot/instance.go`,
`processmanager/{types.go,manager.go,processinfo.go}`, `tracer/tracer.go`.
Generated Gadget targets changed by `gadget-epoch.patch`:
`pkg/symbolizer/symbolizer.go` (both `Task` and `SymbolizerOptions`) and
`pkg/symbolizer/otel/otel.go`.

## Task 1: Lock the supported runtime and shared identity contract

**Interfaces produced:**

```go
// Provider libpf, value-only protocol record; no change to interned Frame.
type HotSpotEpochState uint32
const (
    HotSpotEpochUnknown HotSpotEpochState = iota
    HotSpotEpochStable
    HotSpotEpochReadError
    HotSpotEpochInProgress
    HotSpotEpochChanged
    HotSpotEpochUnsupported
    HotSpotEpochRegressed
)
type HotSpotIdentity struct {
    Epoch uint64
    State HotSpotEpochState
    ReadError int32
}
type HotSpotEpochTarget struct {
    PID PID
    ProcessStartTicks uint64
    CounterAddress Address // absolute process address, not ELF symbol value
    InitialEpoch uint64
}
type HotSpotEpochError struct {
    Identity HotSpotIdentity
    Cause error
}
func (e *HotSpotEpochError) Error() string
func (e *HotSpotEpochError) Unwrap() error // returns Cause

// Experiment internal/identity: one configuration, never a process cache.
type Target struct {
    PID uint32
    ProcessStartTicks uint64
    CounterAddress uint64
    InitialEpoch uint64
    JVMBuildID string
    JVMSHA256 string
}
type Profile struct { BuildID, SHA256 string; UseSerialGC, UseCompiler bool }
func ValidateProfile(Profile) error
func RuntimeAddress(symbolValue, loadBias uint64) (uint64, error)
func Discover(pid int, startTicks uint64) (Target, error)
```

**Files:** `internal/identity/*`, provider `libpf/hotspot_epoch*.go.txt`, preparer,
backlog issue scope and this design/plan.

- [ ] Read the design and issue. **Repository root:** run
  `./scripts/adkw blast-radius HandleTrace`, `./scripts/adkw blast-radius Task`
  and `./scripts/adkw blast-radius SymbolizerOptions`; directly inspect external
  module callers when the atlas does not index them. Extend the existing issue's
  declared scope to the file map; do not scaffold a duplicate identity issue.
- [ ] Add the shared record/error tests and profile/bias tests. Example red
  assertions (imports: `errors`, `syscall`, `testing`):

```go
func TestEpochErrorPreservesPermissionCause(t *testing.T) {
    err := &HotSpotEpochError{
        Identity: HotSpotIdentity{State: HotSpotEpochReadError},
        Cause: syscall.EACCES,
    }
    if !errors.Is(err, syscall.EACCES) { t.Fatal("permission cause lost") }
    if (HotSpotIdentity{}).State == HotSpotEpochStable {
        t.Fatal("zero-value identity claims success")
    }
}

func TestRuntimeAddressRejectsOverflow(t *testing.T) {
    if _, err := RuntimeAddress(^uint64(0)-7, 8); err == nil {
        t.Fatal("ASLR address overflow accepted")
    }
    got, err := RuntimeAddress(0x17fa938, 0x700000000000)
    if err != nil || got != 0x7000017fa938 { t.Fatal(got, err) }
}
```

- [ ] Prepare test additions and run red:
  `go test -modfile build/experiment.go.mod ./internal/identity go.opentelemetry.io/ebpf-profiler/libpf -run 'TestEpoch|TestRuntime|TestProfile' -count=1`.
  Require missing contract/behavior failure, not unavailable Docker or a wrong graph.
- [ ] Implement the value types and typed error. `ValidateProfile` compares the
  exact locked SHA/build ID and requires true Serial/false compiler flags; reject
  unknown build IDs even if the version string matches. `RuntimeAddress` checks
  unsigned overflow before addition.
- [ ] Implement `Discover` with `debug/elf`, SHA-256 and existing x/sys reads.
  Parse mappings by device/inode/file offset, resolve the target's mapped JVM
  through `/proc/<pid>/root` or `/proc/<pid>/map_files`, and verify the opened
  file's device/inode against the mapping. Derive one consistent bias from
  PT_LOAD segments; do not assume the first maps entry is the ELF base. Extract
  the counter/flag symbols from this verified ELF, check sizes/alignment/ranges,
  and read flags/counter with exact-length `ReadAt`. Do not invoke `jcmd`, attach
  an agent, change ptrace policy or inspect credential environment variables.
- [ ] Add tests for multiple PT_LOAD offsets, ambiguous mappings, mismatched inode,
  deleted mapping, wrong symbol sizes, short reads, EPERM/EACCES, flag rejection,
  changed start ticks, valid epoch zero, odd counter and upper-half refusal.
  `observe_other.go` returns an explicit unsupported-platform error.
- [ ] Run the same packages with `-race`; record read-only discovery against a
  gated ordinary-user pinned Xint/Serial target. Record live flag values, bias and
  actual counter; refuse the tiered/default-GC control in strict discovery.
- [ ] Commit only Task 1's source/patch/issue hunks after passing checks.

## Task 2: Regenerate an atomic C/Go/BPF epoch ABI

**Consumes:** Task 1 identity enums and verified counter address/initial value.
**Produces:** ABI version 2 trace/key/proc-info layouts and reproducibly rebuilt
provider bytecode; the existing generic correlation map is unchanged.

**Files:** provider epoch patch, `support/{types_def.go,types.go}` targets,
`support/hotspot_epoch_test.go.txt`, `tracer/hotspot_epoch_test.go.txt`,
`scripts/{prepare-gadget.py,rebuild-profiler-ebpf.sh}`, Makefile.

- [ ] Add the ABI tests before layout changes. Use a C record with exactly:

```c
typedef struct HotspotEpochIdentity {
  u64 epoch;
  u32 state;
  s32 read_error;
} HotspotEpochIdentity;
_Static_assert(sizeof(HotspotEpochIdentity) == 16, "epoch ABI");
/* Trace: identity follows correlation_id, precedes trailing frame_data. */
/* TraceCache: pid(u32), tid(u32), identity(16 bytes), frame_data[3072]. */
/* HotspotProcInfo: append counter_address(u64), initial_epoch(u64). */
```

- [ ] Assert `unsafe.Sizeof(support.HotspotEpochIdentity{}) == 16`, producer key
  size `24+3072*8`, existing uint64 value size 8 and trailing frame-data offset.
  Encode a real `support.Trace` raw sample and feed `loadBpfTrace`; epoch/state/
  read error must survive. Test too-small headers, truncated frame data, invalid
  state, stable odd/upper-half values, and pooled-record reset to unknown.
- [ ] Run red:
  `go test -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/support go.opentelemetry.io/ebpf-profiler/tracer -run TestHotSpotEpoch -count=1`.
- [ ] Add C declarations/enums and `types_def.go` aliases, regenerate Go layouts
  in ignored build sources, and include the reviewed generated `types.go` delta
  in the source patch. Use `go tool cgo -godefs types_def.go`; upstream
  `support/generate.sh` compares output and deliberately exits 1 when it differs.
  Review/copy the generated output before rerunning that comparison. A difference
  is not an excuse to hand-widen C fields.
- [ ] Add `HotSpotIdentity HotSpotIdentity` to both `libpf.EbpfTrace` and
  `libpf.Trace`; add `HotSpotIdentityError error` to `libpf.Trace`. Decode the C
  record into these named fields in `loadBpfTrace`, rejecting malformed states
  before dispatch. Existing trace-pool reset must clear the new fields. Audit
  unkeyed public struct literals/callers in the selected graph; adding fields
  does not imply universal source compatibility for external embedders.
- [ ] Add the small rebuild script using the already pinned builder and verified
  generated provider path. Build with these observed tools:

```bash
make -C "$provider/support/ebpf" -o errors.h TARGET_ARCH=amd64 \
  BPF_CLANG=clang BPF_LINK=llvm-link-18 LLC=llc-18 STRIP=llvm-strip
make -C "$provider/support/ebpf" -o errors.h TARGET_ARCH=arm64 \
  BPF_CLANG=clang BPF_LINK=llvm-link-18 LLC=llc-18 STRIP=llvm-strip
```

  Run in `ghcr.io/inspektor-gadget/gadget-builder@sha256:dba339d2a2f189ae5fcdc08730e6cac128d4ed30c3af42e5fb93a5c8cf4c77aa`,
  with only the generated provider writable. Keep verified existing `errors.h`
  (`-o errors.h`) because no unwind-error enum changes are needed. Remove copied
  build intermediates before rebuilding so stale objects cannot satisfy Make.
  Both embedded architectures must match the new layout; strict runtime support
  still refuses ARM. Do not use an unpinned host LLVM or download new tooling.
- [ ] Preparation hashes patches/additions/rebuild script and records ABI version,
  builder digest, generated `types.go` and both bytecode SHA-256s. Write readiness
  markers only after regeneration and verification; reject incomplete/hash-mismatched
  trees. Base-module sums and version pins remain unchanged. Keep diagnostic and
  definition-drop build identities separate and regenerate their bytecode too.
- [ ] Compare BTF definitions in both compiled objects against Go fields; load the
  amd64 object through the existing collector/kernel harness and inspect actual
  map key/value sizes. A source-only test cannot certify the embedded ABI.
  Make these same ABI checks a strict `NewTracer` startup gate before PID event
  processing/attachment. Deliberately pairing old embedded bytecode with new Go
  layouts must be refused, not parsed heuristically as an epoch-bearing trace.
- [ ] Run ABI tests with `-race`, preparer twice (same content/hashes), whole
  experiment build and pinned container build. Commit only Task 2 hunks.

## Task 3: Capture stable epochs and isolate actual producer references

**Consumes:** ABI v2 and the immutable target descriptor.
**Produces:** independently bracketed event and definition epoch records;
same-epoch reuse and cross-epoch separation in the existing producer LRU.

**Interfaces/configuration:**

```go
// Additive tracer.Config and symbolizer.SymbolizerOptions fields:
HotSpotEpochTarget libpf.HotSpotEpochTarget // zero PID disables strict mode
// ProcessManager, called by NewTracer before exposing the tracer:
func (pm *ProcessManager) ConfigureHotSpotEpochTarget(libpf.HotSpotEpochTarget) error
// Optional interpreter capability configured before assignment/mapping install:
type HotSpotEpochInstance interface {
    ConfigureHotSpotEpoch(libpf.HotSpotEpochTarget) error
    BeginHotSpotEpoch(libpf.HotSpotIdentity) error
    EndHotSpotEpoch(libpf.HotSpotIdentity) error
}
```

**Files:** C epoch patch, provider config/instance wiring, gadget program/YAML,
collector configuration, CLI and kernel producer tests. Add
`patches/profiler-additions/support/hotspot_epoch_kernel_test.go.txt` with build
tag `mw_kernel`; include it in the kernel test binary.

- [ ] Add tests which inspect the actual compiled producer map and execute the
  producer stop path with controlled raw frames/epoch records through its BPF
  test fixture. Identical PID/TID/raw frames at epochs 2 and 4 must produce two
  resident keys/references and definitions; repeated epoch 2 must reuse its own
  reference. Unknown/odd/changing identity must return no valid reference.
  Require the real producer behavior, not only an expected key encoder.
- [ ] Run the kernel test red in the existing privileged test container. Retain
  the resulting failure and BPF verifier logs. If the test harness cannot execute
  the producer path, implement a live controlled-target probe gate rather than
  substituting a pure Go map simulation; do not count unexecuted BPF as green.
- [ ] Store one selected target on the existing process manager. Validate PID,
  start ticks, aligned address and supported initial counter; configure only
  matching HotSpot instances in `handleNewInterpreter` before `assignInterpreter`.
  Copy absolute counter address/initial value into `HotspotProcInfo`; no second
  per-process metadata dictionary. Reject reconfiguration after PID processing
  starts. Keep existing `Instance` and `Symbolize` signatures intact.
- [ ] In `tracemgmt.h` reset identity fields for every capture.
  In `hotspot_tracer.ebpf.c` read the configured counter before Java metadata;
  in `interpreter_dispatcher.ebpf.c` recheck before cache lookup/publication.
  Use this ordering (helper read failures preserve their negative result):

```c
/* before: checked read; reject odd, upper-half, or below initial value */
/* after: checked read, then require after == before and even */
trace->hotspot_identity.epoch = before;
trace->hotspot_identity.state = HOTSPOT_EPOCH_STABLE;
trace->hotspot_identity.read_error = 0;
traceCache->hotspot_identity = trace->hotspot_identity;
/* Existing exact lookup/insert/send_trace follows only after validation. */
```

  Unknown process discovery and native-only stacks cannot manufacture supported
  Java identity. Rejection cannot inherit the preceding per-CPU reference.
  The final read can use the existing `hotspot_procs` map via `extmaps.h` and the
  trace PID; no counter-address scratch map is needed. Test that repeated HotSpot
  tail calls retain the first captured epoch rather than resetting it. State,
  not epoch zero, distinguishes first capture from an already stable record.
- [ ] Add gadget parameters `target_safepoint_counter` and
  `target_initial_safepoint_epoch`, default zero address/disabled. Add event
  `hotspot_epoch`, `hotspot_epoch_state`, `hotspot_epoch_read_error`. Independently
  bracket `gadget_get_user_stack_from_kprobe`; keep occurrence/filename collection
  and `generic_params` reset. An event-side rejection remains explicit even if
  the provider emitted a definition within its narrower bracket.
- [ ] Add `--hotspot-identity=safepoint-epoch`; `unknown` is the only other
  accepted value. Strict capture discovers the profile after pidfd/starttime
  checks and passes the same target to Gadget options and BPF parameters.
  Refuse debug-rendered strict mode and duration >60 seconds. Recheck the JVM
  mapping before attachment; observed unexpected mapping replacement/exec ends strict
  capture rather than silently reconfiguring the counter.
- [ ] Rebuild bytecode and gadget OCI image; run producer tests green and live
  capture/CLI refusal controls. Record actual new patch/object/image hashes.
  Commit only Task 3 hunks; userspace lifetime acceptance is not yet complete.

## Task 4: Guard every HotSpot cache layer and transactional definition publication

**Consumes:** `HotSpotEpochInstance`, stable captured identity and existing remote
memory/caches. **Produces:** guarded immutable definitions or typed rejection
without tentative Java labels.

**Files:** provider HotSpot/process-manager epoch additions and patch targets;
existing identity helpers remain active.

**Private conversion interface:**

```go
func (pm *ProcessManager) convertHotSpotEpochFrame(pid libpf.PID,
    ef libpf.EbpfFrame, dst *libpf.Frames) (bool, error)
```

For HotSpot this calls the existing `symbolizeFrame` and returns its original
error; for other interpreters/native frames it delegates to existing
`convertFrame`. Only strict handling uses this helper. Do not let bool-only
legacy conversion erase a strict failure.

- [ ] Add begin/end guard tests to the real `qualityInstance` memory fixture.
  Use the existing `identityReader` to change the counter between reads:

```go
func TestHotSpotEpochRejectsBoundaryAfterCacheHit(t *testing.T) {
    ii, _ := qualityInstance(t)
    if err := ii.ConfigureHotSpotEpoch(libpf.HotSpotEpochTarget{
        PID: 123, ProcessStartTicks: 456, CounterAddress: 0x300,
        InitialEpoch: 2,
    }); err != nil { t.Fatal(err) }
    reads := 0
    ii.rm.ReaderAt = identityReader(func(p []byte, off int64) (int, error) {
        if off != 0x300 || len(p) != 8 { t.Fatal("unexpected epoch read") }
        reads++
        value := uint64(2)
        if reads > 1 { value = 4 }
        binary.LittleEndian.PutUint64(p, value)
        return len(p), nil
    })
    captured := libpf.HotSpotIdentity{Epoch: 2, State: libpf.HotSpotEpochStable}
    if err := ii.BeginHotSpotEpoch(captured); err != nil { t.Fatal(err) }
    if err := ii.EndHotSpotEpoch(captured); err == nil {
        t.Fatal("cross-boundary definition accepted")
    }
}
```

- [ ] Add real metadata-cache tests: seed `addrToMethod`, `addrToSymbol` and
  `addrToJITInfo`, establish epoch 2, then observe epoch 4. Assert all three old
  entries are absent and capacities unchanged. Include same Method/ConstMethod
  and Symbol addresses with replacement names. For valid same-epoch reuse, seed
  caches after the first successful begin, not before a required initial purge.
- [ ] Add process-manager tests whose optional fake interpreter counts begin/end
  and `Symbolize` calls while using the actual `pm.frameCache`. Populate the
  cache at epoch 2, then hit it: guard must still execute. Epoch 4 must invoke
  conversion and cannot return the epoch-2 name. A failing end removes all
  tentative HotSpot labels and leaves new frame-cache keys unadmitted. Include
  native frames to verify their order/evidence survives Java rejection.
- [ ] Run red:
  `go test -modfile build/experiment.go.mod go.opentelemetry.io/ebpf-profiler/interpreter/hotspot go.opentelemetry.io/ebpf-profiler/processmanager -run TestHotSpotEpoch -count=1`.
- [ ] Implement checked counter reads at the absolute `CounterAddress` supplied
  to `ReadAt`; `RemoteMemory.Bias` adjusts decoded pointer values, not read
  addresses. Validate conversion to the signed ReaderAt offset and preserve `%w`
  errors/short reads. Begin requires stable/even/lower-half capture and equal live
  epoch. Purge the three existing metadata LRUs when entering a new epoch. End
  reads again and rejects a transition. Lower *live* counter poisons this target;
  a delayed lower *capture* is only a changed-epoch rejection. Recheck live flags
  and verified mapping on provider synchronization; do not broaden the profile.
- [ ] Preserve metadata read failures that existing helpers can hide. Install a
  trace-scoped `epochReader` after a successful begin and restore the original
  ReaderAt on every end/abort. It delegates all reads and records the first cause:

  **Rejected proposal (R1/R3):** this shared-reader mutation and first-error-only
  implementation are retained here to identify the defect. They are not approved
  implementation instructions; replace them with reviewed reader ownership and
  bounded error retention before executing Task 4.

```go
type epochReader struct { base io.ReaderAt; failure error }
func (r *epochReader) ReadAt(p []byte, off int64) (int, error) {
    n, err := r.base.ReadAt(p, off)
    observed := err
    if observed == nil && n != len(p) { observed = io.ErrUnexpectedEOF }
    if r.failure == nil && observed != nil { r.failure = observed }
    return n, err
}
```

  `pm.mu` does not protect the provider's concurrent `SynchronizeMappings` calls.
  Reader ownership must cover those accesses too. The reader adapter must not
  own/close the underlying reader. End must preserve all fatal causes, including
  permission errors after earlier failures, together with its
  checked epoch-read result. Add a regression where counter reads succeed but
  the underlying class Symbol read returns EACCES: no fallback named Java frame
  may be published and `errors.Is(result, syscall.EACCES)` must remain true.
  Strict conversion uses `convertHotSpotEpochFrame` to preserve direct decoder
  errors. Retain the existing legacy decoder behavior outside strict mode.
- [ ] Wrap the existing `HandleTrace` loop with guarded lifetime checks under its
  existing interpreter locks. Add epoch to HotSpot `frameCacheKey` and hash;
  native keys use unknown identity. Validate frame lengths/counts before indexing
  and bounding the admission list. Record key/output segment offsets for new
  HotSpot conversions and clone/admit them only after a successful end guard.
  Preserve the existing single-handler/non-reentrant ownership.
- [ ] On guard failure remove named HotSpot output, keep native/error output, set
  `Trace.HotSpotIdentity` to the rejection and preserve
  `Trace.HotSpotIdentityError error` with the original typed cause. Report this
  definition so waiters wake; never publish tentative labels through a log-only
  fallback. Add no epoch data to interned frames.
- [ ] Run full HotSpot/process-manager packages with `-race` plus existing decoder
  tests. Test zero, short reads, EIO, EPERM, EACCES, end-only read failure, odd
  counters, out-of-order definitions and poisoned regression. Commit Task 4.

## Task 5: Preserve identity through Gadget lookup, recovery and JSONL

**Consumes:** guarded `libpf.Trace` identity/error and event-side identity.
**Produces:** epoch-matched structured evidence, immutable historical definitions,
explicit unavailable reasons and fatal permission/identity conflicts.

**Interfaces:** add `HotSpotIdentity libpf.HotSpotIdentity` to Gadget `Task` and
`StructuredStack`; add `identity`/`identityErr` to existing `correlatedFrames`.
Keep `storeTrace(id, frames, pid, tid)` as the legacy private wrapper. Add:

```go
func (o *otelResolverInstance) storeEpochTrace(id uint64, frames libpf.Frames,
    pid, tid uint32, identity libpf.HotSpotIdentity, identityErr error) error
// Existing ResolveStructured/RecoverDefinition signatures are unchanged.
```

**Files:** Gadget epoch patch/additions, capture/model files and their tests.

- [ ] Add correlation tests using `fixtureResolver`/`fixtureFrames`: store epoch
  2, resolve task epoch 2, and reject task epoch 4 despite matching PID/TID/ref.
  Resolve the validated old definition after a simulated live epoch 4 without
  asking the decoder to read replacement metadata. The live counter is not a
  condition for reusing an immutable already-validated historical definition.
- [ ] Store a rejected definition and prove waiters wake with the typed rejection
  and no Java labels. Duplicate identical definitions are idempotent; conflicting
  epoch/PID/TID/frames must return identity mismatch without replacing the old
  entry. Keep existing cancellation/adaptive wait/prune tests unweakened.
- [ ] Run the Gadget package tests red before forwarding identity. In the
  reporter callback call `storeEpochTrace` with `t.HotSpotIdentity` and
  `t.HotSpotIdentityError`; use the trace, not rendered names or a side map.
  Strict `ResolveStructured` checks event epoch/state and stored definition;
  legacy unknown tasks cannot claim checked identity.
- [ ] Update recovery's map key-size check to `24+3072*8`, decode the epoch record
  at byte offset 8, and require strict identity agreement before selecting a
  candidate. Retain complete iteration, entry limit, timeout, relookup and
  ownership checks. Update the real 256-entry kernel LRU/pruning fixture to ABI
  v2 without changing its two-cycle assertions.
- [ ] Add optional model output with pointer epoch so stable zero survives:

```go
type HotSpotIdentity struct {
    State string `json:"state"`
    Epoch *uint64 `json:"epoch,omitempty"`
    Profile string `json:"profile,omitempty"`
    ReadError *int32 `json:"read_error,omitempty"`
}
// Event and StackEvidence: HotSpotIdentity *HotSpotIdentity
// JSON field name: hotspot_identity. StackRef carries provider identity.
```

  Decoder requires the three new event fields for the newly built gadget.
  Provider-state conversion is explicit/exhaustive; reject unknown numeric
  states. Serialize accepted enrichment as `safepoint_epoch_checked` with
  profile `temurin25_xint_serial`, only after event/definition agreement.
- [ ] Test that stable zero serializes `"epoch":0` while unavailable/unknown
  identity does not. Preserve original event ID/ref, source lines, BCI quality,
  terminal accounting and existing bounded payload charging. Route semantic odd/
  changed/missing cases to explicit unavailable reasons. Check permission and
  unexpected errors before semantic classification; EPERM/EACCES/EIO, regressed
  epoch and identity conflict propagate as session errors. Do not apply missing-
  definition recovery to a received rejected definition.
- [ ] Run `make unit upstream-test kernel-cache-test` with the existing Docker
  override for kernel tests; run the genuine first-definition-drop recovery
  control. Add a strict Xint/Serial recovery run to prove exact epoch matching.
  Commit Task 5 hunks after those gates pass.

## Task 6: Prove useful unloading correctness and record the bounded result

**Consumes:** complete strict mode and unchanged manifest/unloading validator.
**Produces:** three fresh normal-collector evidence runs, controlled delayed-
definition rejection, supported-profile docs and final verification.

**Files:** unloading report/tests, integration gate, harness/Makefile, README and
results docs, existing identity issue/index.

- [ ] Extend report output with per-generation/per-branch correct/unavailable/
  wrong counts, epoch/reference pairs and typed rejection counts. Preserve the
  current wrong-generation assertion. Add validator regressions that pass all
  token/unload counts but have every segment unavailable, one late generation
  missing branch B, or a reference reused across different observed epochs;
  the new strict acceptance gate must reject each.
- [ ] Add `TestDisposableLoaderEpochAttribution`: normal collector, no raw-log
  requirement. Require all 1600 unique tokens, all 80 confirmed unloaded classes,
  zero wrong labels, epoch-checked correct evidence and at least one correct
  leaf/root segment per branch in each generation 1–79. Generation zero may be
  cold unavailable. Keep the existing investigation/control gate as evidence of
  the original failure; do not rewrite its wrong results as unavailable.
- [ ] Add deterministic delayed-decoder regression at the provider/process-manager
  boundary: capture epoch 2, replace metadata at the same addresses, move live
  epoch to 4 before dispatch, and assert no old-reference/new-generation labels.
  Pair it with a successful already-validated historical definition test. Any
  live delay instrumentation belongs to a separate labelled diagnostic build;
  it must not enter the normal collector.
- [ ] Add Make target `unloading-epoch: build gadget unloading-fixture`; invoke
  the existing harness with strict mode, normal binary, no diagnostic requirement
  and the new gate. The harness must allow this explicit gate instead of
  unconditionally replacing it in unloading mode. Record target runtime flags,
  actual kernel, live profile verification, source/class/object/patch/image hashes
  and per-generation results in each fresh artifact directory.
- [ ] Run three fresh JVMs, invoking this command separately for each run:

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  make unloading-epoch DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

  Each run must pass independently. Run one explicit baseline control without
  epoch mode and retain its failing report. Confirm same-epoch producer reuse
  still occurs while references split at confirmed unloading epoch boundaries.
  Report availability totals, not only zero wrong labels.
- [ ] Run strict refusal controls for the pinned tiered/default-GC target, altered
  ELF identity, unreadable counter and changed process/mapping. Startup refusal
  is not a successful capture and cannot contribute to attribution coverage.
- [ ] Update guidance with the supported profile, unavoidable boundary-related
  unavailability, new ABI and exact recorded evidence. Keep concurrent collectors,
  JIT unloading, semantic BCI, complete loss, memory/overhead and Kubescape gates
  explicitly pending. Record the bounded trace-local admission memory and avoid
  performance claims until measured.
- [ ] Run `make verify` after focused/native checks pass. **Repository root:**
  run `./scripts/adkw check-backlog`, `git diff --check`, then `./gradlew build`
  once. No Seccomp/Landlock/USER_NOTIF production behavior changed.
- [ ] Review the scoped diff against the design, then commit only this task's
  files/hunks. Keep the existing identity issue open for broader lifetime coverage
  and review; do not archive it merely because this restricted profile passes.

## Self-review and handoff

Second review supersedes the initial self-review below: R1–R5 are open, and the
original transaction/error/incarnation instructions are not execution-ready.

The tasks cover profile discovery (1), shared ABI/embedded bytecode (2), producer
identity (3), frame/method/Symbol lifetime (4), historical lookup/recovery/output
(5), and useful live correctness/refusals/evidence (6). All use the same
`libpf.HotSpotIdentity`/`HotSpotEpochTarget` signatures and ABI state numbering.
No test replaces the red wrong-generation assertion or claims zero whole-session
loss. No implementation has run at plan-writing time.

Execute inline with `executing-plans` after operator review of these documents.
If the actual compiled producer cannot be exercised, a profile cannot be verified
from the live target, or epoch checks cannot preserve useful later-generation
resolution, stop with the specific failing evidence and revise the design before
adding broader lifecycle hooks or weakening acceptance.
