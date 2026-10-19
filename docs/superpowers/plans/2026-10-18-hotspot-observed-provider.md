---
document_type: execution_plan
base_revision: 16fab8e6f36f3f1fdb57964003affd30a420219c
status: admitted_cli_qualified_pending_sdk_release
---

# Agentless HotSpot Provider and Go Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Read the governing specification and this plan's ledger before dispatch; earlier allocator tasks are complete.

**Goal:** Produce a reusable validated Go boundary and an explicitly qualified ordinary-JVM observational collector, without changing strict schema2 semantics.

**Architecture:** Extract the existing pure asset/JSON contract into one standard-library bridge module. Add a separately negotiated schema3 profile that reuses the actual OTel engine, process owners and native drain, but makes only generation-checked observational claims. A-OBS qualification and operator review precede admission/release.

**Tech Stack:** Go1.26, existing BPF/Gadget/OTel sources and pinned builder, existing Kotlin ABI/native acceptance tools; no new third-party SDK dependency.

**Spec:** [Governing design, A](../specs/2026-10-18-kubescape-agentless-hotspot-design.md#a-provider--bridge-contract).

**Execution approval (2026-10-18):** The user's explicit Subagent-Driven selection
after the six-document handoff approves P0 implementation/design scope by reference.
A awaits Task0 review; ordinary-JVM qualification/admission and SDK publication
still require their separate named gates. No ordinary-JVM support is yet qualified.

**Execution checkpoint (2026-10-19):** A1/A2/A3 and the initial rejected A4 corpus
are committed; SDKv0.1.0 remains immutable. The subsequent
[owned-drain repair](../../plans/2026-10-19-hotspot-observed-drain-evidence.md)
and [coupled JVM exec qualification](../../plans/2026-10-19-hotspot-coupled-exec-evidence.md)
complete the six-case controlled candidate gate. The original rejected matrices
remain frozen; actual rebuilt helper strict acceptance executes8tests without
skips/failures. A4 admission and A5 publication/handoff remain blocked. Do not redispatch
the completed extraction/decoder/negotiation work or promote successful route
prefixes to a lifetime proof.

**Admission continuation:** The operator's explicit "yes" approves the scoped
observed-profile admission change. [New source and actual-CLI evidence](../../plans/2026-10-19-hotspot-observed-admission.md)
record three completed CLI captures, preserved negative controls and two identical
rebuilt bundles. Root and all eight strict native tests pass. SDK publication and A5
handoff remain separately gated; this answer does not authorize them.

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

## File responsibilities / local setup

All paths below are relative to M from the specification. New public API is only
the approved bridge module; runtime/model internals stay private to the collector.

|Path|Responsibility|
|---|---|
|`hotspot-bridge/go.mod`|Module `github.com/Pilleo/mazewall/hotspot-bridge`, Go1.26, stdlib only|
|`hotspot-bridge/bundle/{verify,verify_test}.go`|Existing full-byte, bounded, no-symlink manifest/OCI verifier|
|`hotspot-bridge/wire/{types,decoder,decoder_test,stackid,stackid_test}.go`|Plain DTOs, bounded session state, canonical observed-stack identity|
|`hotspot-bridge/wire/testdata/`|Small committed synthetic adversarial fixtures and one receipted real projection|
|`profiler/hotspot-agentless/internal/tooling/{assets,assets_test,distribution}.go`|Delegate common verification, preserve exact strict admission/pack policy|
|`profiler/hotspot-agentless/internal/model/{event,record,stack,identity}.go`|Keep native converters/methods and compatible JSON after DTO extraction|
|`profiler/hotspot-agentless/internal/{capture,identity}/observed*.go`|New profile, identity admission, full owned stop without strict safepoint dependency|
|`profiler/hotspot-agentless/internal/capture/observed_candidate.go`|Test-only tagged candidate entry; absent from shipped default build|
|`profiler/hotspot-agentless/patches/profiler-additions/interpreter/hotspot/observed.go.txt`|Bounded external metadata-read/error observer for existing HotSpot symbolization|
|`profiler/hotspot-agentless/patches/profiler-additions/processmanager/hotspot_observed.go.txt`|Connect observation reader/fatal errors without changing strict epoch path|
|`profiler/hotspot-agentless/cmd/mw-hotspot-collector/{main,main_test}.go`|Explicit profile/schema/expected-target CLI negotiation|
|`experiments/hotspot-agentless/internal/candidate/observed*.go`|Bounded ordinary-JVM qualification using existing owner/receipt machinery|
|`docs/plans/2026-10-18-hotspot-observed-profile-evidence.md`|Actual A-OBS verdict/artifact identities, not an assumed success|

The named native additions must be copied to the exact experimental mirror by the
normal preparer. Enumerate all pin consumers again; fourteen is the baseline,
not permission to miss new consumers introduced here. No dependency/module cache
or prepared tree is an editing location.

Before SDK publication, use an owned local development workspace rather than a
fictional registry version. **Go forbids combining workspace mode with -modfile.**
Generate normal prepared replacements before changing native imports, then make
one workspace per native owner from its actual prepared replacement table. The
canonical and experimental owners have the same module path and must not both
be workspace main modules. This is build input, not a committed absolute replace:

```bash
M=/home/leanid/Documents/code/java/jseccomp
cd "$M/profiler/hotspot-agentless"
GOWORK=off go run -buildvcs=false ./cmd/mw-collector-tools prepare
```

After creating the SDK go.mod, create the canonical development workspace:

```bash
export M
export OWNER="$M/profiler/hotspot-agentless"
export GOWORK="$(python3 - <<'PY'
import json, os, pathlib, subprocess, tempfile
m, owner = pathlib.Path(os.environ['M']), pathlib.Path(os.environ['OWNER'])
build = m / 'build' / 'kubescape-dev'
build.mkdir(parents=True, exist_ok=True)
work = pathlib.Path(tempfile.mkdtemp(prefix='workspace-', dir=build))
env = dict(os.environ, GOWORK='off')
def go(*args):
    return subprocess.check_output(['go', *args], cwd=work, env=env, text=True)
mods = json.loads(go('mod', 'edit', '-json', str(owner/'build/experiment.go.mod')))
go('work', 'init', str(m/'hotspot-bridge'), str(owner))
env['GOWORK'] = str(work/'go.work')
for replacement in mods.get('Replace', []):
    def ref(v): return v['Path'] + ('@'+v['Version'] if v.get('Version') else '')
    new = replacement['New']
    if not new.get('Version') and not pathlib.Path(new['Path']).is_absolute():
        new['Path'] = str((owner/new['Path']).resolve())
    go('work', 'edit', '-replace='+ref(replacement['Old'])+'='+ref(replacement['New']))
print(work/'go.work')
PY
)"
```

Run only after Task1 creates the module; record actual Go/compiler/source inputs.
Use `go test` without -modfile while this explicit workspace is active. For the
experimental owner, set OWNER to M/experiments/hotspot-agentless and generate its
own workspace/replacements; do not add it to the canonical workspace. Container
builds must mount the same source/workspace graph. After the separate operator-approved SDK publication gate,
normal native commands use `GOWORK=off` plus build/experiment.go.mod with the real
released dependency for its existing behavior. v0.1.0 was actually published at
`1a6d58d2c73f5b089588add7364bf8d7913c5604`; its bundle verifier rejects Observed25.
Task3's additive format3/Expected support is an explicit local candidate, so its
checks and packing retain the per-owner workspace above until a new SDK release
is separately approved and published. GOWORK=off does not select candidate SDK
behavior. Never let an implicit workspace select other code.

### Task 1: Extract shared bundle verification without weakening admission

**Files:** New bridge module/bundle package; existing native tooling assets,
distribution, tests, and canonical/mirror `go.mod`/`go.sum` only after P0.

**Interfaces:** Produces `bundle.Verify(root, expectedManifestSHA string,
profile wire.Profile) (Verified,error)` and checked `Verified.Collector/Store/Image`
getters plus `Expected(target)`. `wire.Profile` is defined here in `wire/types.go`;
copy the small Provider/Target/Expected contracts from spec A2 now as well so
Verified.Expected compiles in this task. Remaining DTOs are Task2. Native tooling
remains the strict-version policy caller.

- [ ] **Step1: Copy the current asset fixture into bridge tests**, preserving its
  complete index/config/layer graph and exclusive-path/error cases. Add a genuine
  same-length transitive corruption test; the helper itself must not run:

First create only the module declaration, Profile vocabulary and Manifest DTO
contracts, not Verify. This keeps RED from being a missing-module/test-selector
failure. The module is `github.com/Pilleo/mazewall/hotspot-bridge`, Go1.26.0,
stdlib only. Create Profile as shown in Step3; move the existing manifest fields
as the new exported Manifest without changing their JSON spelling.

```go
func TestVerifyRehashesUnchangedLengthLayer(t *testing.T) {
    f := newAssetFixture(t)
    f.pack(t)
    expected := manifestDigest(t, f.bundle)
    if _, err := Verify(f.bundle, expected, wire.Strict25); err != nil {
        t.Fatal("valid fixture rejected before mutation", err)
    }
    b, err := os.ReadFile(f.layer)
    if err != nil { t.Fatal(err) }
    b[0] ^= 1
    if err := os.WriteFile(f.layer, b, 0644); err != nil { t.Fatal(err) }
    if _, err := Verify(f.bundle, expected, wire.Strict25); err == nil {
        t.Fatal("same-size transitive mutation admitted")
    }
}
func manifestDigest(t *testing.T, root string) string {
    t.Helper()
    b, err := os.ReadFile(filepath.Join(root, "manifest.json"))
    if err != nil { t.Fatal(err) }
    h := sha256.Sum256(b)
    return hex.EncodeToString(h[:])
}
```

Move `newAssetFixture` from `internal/tooling/assets_test.go:12-55`, preserving its
actual OCI construction. Its old pack method calls private native tooling and
cannot be copied unchanged into the SDK. Replace that private test helper with a
fixture-only file copier that lays out `collector`, `oci/index.json`, `oci-layout`
and blobs, hashes them, and writes Manifest. This does not implement verification.
Use the actual Manifest field names moved below; synthetic revisions are labelled
fixture values. Keep/migrate missing/ABI/alias/descriptor/unlisted-blob cases.

The replacement `assetFixture.pack` uses os.MkdirAll/WalkDir to copy the existing
fixture collector and source graph beneath bundle/oci, accumulates each copied
relative name's full SHA256, then writes Manifest. Its profile is strict2/2 and
platform linux-amd64; native revisions are explicit synthetic hex fixtures.
It does not call Verify, invoke native tooling, or import its old private packer.

```go
func (f assetFixture) pack(t *testing.T) {
    t.Helper()
    files := map[string]string{}
    copyOne := func(src, name string, mode fs.FileMode) {
        b, err := os.ReadFile(src)
        if err != nil { t.Fatal(err) }
        dst := filepath.Join(f.bundle, filepath.FromSlash(name))
        if err := os.MkdirAll(filepath.Dir(dst), 0755); err != nil { t.Fatal(err) }
        if err := os.WriteFile(dst, b, mode); err != nil { t.Fatal(err) }
        h := sha256.Sum256(b)
        files[name] = hex.EncodeToString(h[:])
    }
    copyOne(f.collector, "mw-hotspot-collector", 0755)
    err := filepath.WalkDir(f.store, func(path string, entry fs.DirEntry, err error) error {
        if err != nil { return err }
        if entry.IsDir() { return nil }
        rel, err := filepath.Rel(f.store, path)
        if err != nil { return err }
        copyOne(path, "oci/"+filepath.ToSlash(rel), 0644)
        return nil
    })
    if err != nil { t.Fatal(err) }
    m := Manifest{SchemaVersion: 1, CollectorVersion: "2", CaptureABI: 2, WireSchema: 2,
        Platform: "linux-amd64", GadgetRevision: strings.Repeat("1", 40),
        ProfilerRevision: strings.Repeat("2", 40), PatchSHA256: strings.Repeat("3", 64),
        GadgetDigest: f.digest, Files: files}
    b, err := json.Marshal(m)
    if err != nil { t.Fatal(err) }
    if err := os.WriteFile(filepath.Join(f.bundle, "manifest.json"), b, 0644); err != nil { t.Fatal(err) }
}
```

These revision strings are test-only synthetic values under a test-pinned manifest,
never native acceptance pins. Required test imports are standard io/fs/strings plus
the imports already used by the moved fixture.

- [ ] **Step2: Run RED before exporting implementation.**

```bash
cd "$M/hotspot-bridge"
go test ./bundle -run '^TestVerifyRehashesUnchangedLengthLayer$' -count=1
```

Expected undefined Verify/new package initially, followed by actual mutation
refusal after extraction. Label compile RED separately from behavioral controls.

- [ ] **Step3: Move common verifier code and remove its old duplicate.** Move
  `ownedAsset`, bounded JSON/hash helpers, descriptor types, graph traversal and
  byte verification from existing tooling. Keep limits65536/256/512MiB. Move the
  fixture and add checked close-error joining. The SDK uses stdlib structural
  executable-mode checks and consumers preserve the real exec error; do not add
  x/sys to the SDK just for Access. Existing native tooling policy wrappers retain
  their `unix.Access(..., unix.X_OK)` checks in verification and packing, preserving
  original EACCES/EPERM causes; mode bits are not permission-equivalent. Preserve
  pathname/manifest/graph validation and strict tool version checks in the caller.

```go
// hotspot-bridge/wire/types.go, the initial profile vocabulary for both packages.
type Profile string
const (
    Strict25 Profile = "temurin25_xint_serial"
    Observed25 Profile = "otel_hotspot25_observed_v1"
)
type Provider struct {
    Name string `json:"name"`
    GadgetRevision string `json:"gadget_revision"`
    ProfilerRevision string `json:"profiler_revision"`
    PatchSHA256 string `json:"patch_sha256"`
}
type Target struct {
    ContainerID, ImageID string
    PID uint32
    StartTicks, CgroupID, MountNS uint64
}
type Expected struct { Target Target; Provider Provider; Profile Profile; Schema int }
```

The module's go.mod is exactly `module github.com/Pilleo/mazewall/hotspot-bridge`
plus `go 1.26.0`; it requires no third-party packages. In package bundle:

```go
type Verified struct {
    root, collector, store, image string
    manifest Manifest
    valid bool
}
func (v Verified) Collector() (string, error) {
    if !v.valid { return "", errors.New("unverified collector") }
    return v.collector, nil
}
```

`Manifest` is the moved `assetManifest` with its exact existing fields; profile3
packing is Task3. All getters reject zero values. Verify compares the **actual
manifest bytes** to the supplied trusted digest before parsing. A strict tooling
wrapper still checks current accepted patch/revisions/wire2; it does not accept a
caller-provided arbitrary native profile.

- [ ] **Step4: Run GREEN and preserve moved negative controls.**

```bash
cd "$M/hotspot-bridge"
go test -race ./bundle ./wire -count=1
cd "$M/profiler/hotspot-agentless"
go test -race -buildvcs=false ./internal/tooling
```

Include malformed/oversize/UTF8, symlink root/member, traversal, missing/extra OCI
member, descriptor size, corrupt executable/layer, wrong profile/version, close
failure and permission-cause tests. Confirm no helper invocation and no cache by
mtime/size/inode. Copy exact relevant native mirrors.

- [ ] **Step5: Commit one extraction with its tested API.**

```bash
git add hotspot-bridge profiler/hotspot-agentless/internal/tooling profiler/hotspot-agentless/go.mod profiler/hotspot-agentless/go.sum experiments/hotspot-agentless/internal/tooling experiments/hotspot-agentless/go.mod experiments/hotspot-agentless/go.sum
git diff --cached --check
git commit -m "refactor(profiler): share verified HotSpot bundle boundary"
```

Inspect exact staged paths; the existing mandatory hook supplies the full gate.
Do not stage unrelated files in those directories or run the same full gate twice.

### Task 2: Implement the bounded wire consumer and canonical stack identity

**Files:** Bridge wire files/testdata; native plain DTO definitions/converters.
**Interfaces:** Produces all DTO/API names listed in spec A2, and:

```go
type Scope struct {
    IdentityScope, ImageID, JVMBuildID, JVMSHA256 string
    Profile Profile
    Provider Provider
}
func CanonicalStackID(scope Scope, frames []Frame) (string, error)
func ParseStartTicks(stat string) (uint64, error)
```

Extract plain DTO fields from the four named model files; `Summary` uses a local
closed `Lane` string type, not an accounting/BPF import. Keep native-only methods
in model-defined wrappers/converters; Go cannot add methods to an external alias.
Also move the existing pure `internal/preflight/check.go:ParseStartTicks` into
wire and delegate its old native entry point, retaining its tests. This gives the
two Go consumers the same raw-tick parser without adding another proc abstraction.
Keep the Provider/Target/Expected declarations already created in Task1; do not
redeclare them while moving the other DTOs. Schema3 additions are `Record.PolicyEligibility *bool` and lifecycle
`ValidationMode string`, `CgroupID uint64`, `MountNS uint64`, with JSON spellings
policy_eligibility/validation_mode/cgroup_id/mount_namespace. Schema2 absent fields
stay absent; no blanket source-DTO alias may change old JSON.

- [ ] **Step1: Add consumer identity tests before implementation.** This test
  uses the existing small strict lifecycle fixture, not a fabricated native run:

```go
func TestReadyRejectsForeignProcessBirth(t *testing.T) {
    data, err := os.ReadFile("../../profiler/src/test/resources/hotspot/production-lifecycle-projection.jsonl")
    if err != nil { t.Fatal(err) }
    lines := bytes.Split(bytes.TrimSpace(data), []byte{'\n'})
    var ready Record
    if err := json.Unmarshal(lines[1], &ready); err != nil { t.Fatal(err) }
    x := Expected{Schema: 2, Profile: Strict25, Provider: ready.Lifecycle.Provider,
        Target: Target{PID: ready.Lifecycle.PID, StartTicks: ready.Lifecycle.ProcessStartTicks + 1}}
    d := NewDecoder(x)
    if err := d.Accept(lines[0]); err != nil { t.Fatal(err) }
    if err := d.Accept(lines[1]); err == nil { t.Fatal("foreign birth admitted") }
}
```

For schema3 synthetic tests build `Record` DTOs and JSON-marshal them; mark them
synthetic in testdata. Add a table mutating one field at a time: schema/profile/
session/provider/PID/birth/cgroup/clock, duplicate event/terminal/summary, missing
terminal, terminal-before-event, overflow, late row, native-error/retirement flag.
Each row must identify its expected error class, not merely accept any failure.

- [ ] **Step2: Run RED.**

```bash
cd "$M/hotspot-bridge"
go test ./wire -run 'TestReady|TestDecoder' -count=1
```

- [ ] **Step3: Implement private decoder state and exact rule table.**

```go
type decoderPhase uint8
const (awaitStart decoderPhase = iota; awaitReady; collecting; summarized; failed)
```

Transitions: start→ready only with same session/provider/profile; ready→event,
enrichment or recovery; summary→EOF only. `collector_error` stores the original
message/errno and transitions failed. Verify event IDs equal session/CPU/sequence,
all identities match Expected, sequence is nonzero/unique per CPU, filename text
equals `string([]rune(string(FilenameBytes)))`, and retained byte/frame/row caps.
The rune conversion matches Go JSON's replacement of invalid filename bytes while
raw bytes remain authoritative. Terminal closes exactly one prior occurrence;
never time/TID join. The native producer validates per-CPU accounting rows before
checked aggregation; the current wire carries only the aggregate diagnostic.
The parent Decoder checks available aggregate equations, target/session binding
and all full-native/definition/auxiliary finality flags before Finish; it cannot
independently replay per-CPU validation from this wire. No per-CPU row payload or
layout is added. Selected completeness does not prove global lanes. Finish also
requires EOF and successful Wait.

Add a complete accepted synthetic session test as well as rejection tests, so an
implementation that rejects every row cannot pass. The fixture has start, ready,
one event, one successful enrichment, final summary and successful exit; ready
binds PID123/birth456/cgroup789/mount42, fixed valid synthetic provider hashes,
unknown-global lanes and every true physical-finality flag. Its summary has
received=resolved=selected-attempted=submitted=terminals=1, reserve failures0,
mapID7/possibleCPUs1 and scope `owned_openat_handler_entry`. Use the current
native finalization scope/phase strings from the producer's closed constants,
not a guessed label. Include a zero-retention variant with one discarded pair.

Add tests for uint64 `9007199254740993`, signed-max correlation IDs, zero missing
reference, empty/absent source locations, unknown extension retention, invalid
UTF8, duplicate keys, nesting65 and max-line+1. Preserve unknown/partial outcomes.

- [ ] **Step4: Implement content identity with no occurrence namespace.**

```go
func CanonicalStackID(scope Scope, frames []Frame) (string, error) {
    if len(frames) == 0 { return "", errors.New("empty observed stack") }
    owned := append([]Frame(nil), frames...)
    for i := range owned {
        if owned[i].ErrorFrame { return "", errors.New("error frame is not a useful stack") }
        owned[i].Extensions = nil // Unknown raw fields may contain occurrence IDs.
        if owned[i].Mapping != nil {
            m := *owned[i].Mapping
            m.Start, m.End = 0, 0
            m.Extensions = nil
            owned[i].Mapping = &m
        }
    }
    b, err := json.Marshal(struct { Scope Scope; Frames []Frame }{scope, owned})
    if err != nil { return "", err }
    h := sha256.Sum256(b)
    return hex.EncodeToString(h[:]), nil
}
```

Test exact frame changes/order/BCI/quality/provider scope change the ID; changing
outer session/event/PID/TID/correlation or absolute mapping placement does not.
Assert caller mapping slices/pointers are unchanged. This groups observed provider
frames, not universal class-loader identities or enforcement-approved methods.

- [ ] **Step5: GREEN, real corpus parity, commit.**

```bash
cd "$M/hotspot-bridge"
go test -race ./... -count=1
cd "$M"
./gradlew :profiler:test --tests '*HotSpotProtocolTest' --tests '*HotSpotEvidenceAssemblerTest' :profiler:checkKotlinAbi
git add hotspot-bridge profiler/hotspot-agentless/internal/model
git diff --cached --check
git commit -m "feat(profiler): add bounded shared HotSpot stream validation"
```

Use a frozen real schema2 projection for old-client parity; do not rewrite its
provider hash as if historical input were new. No new schema is admitted here.
The SDK also returns bounded original rows in Capture.RawJSONL for outer/provider
extension fidelity; RawSHA256 covers all accepted delimiter-free rows plus one LF
each, even when retention discards a pair. Public errors.Is classes and this raw
carrier complete the A2 parser contract without a new configuration framework.
Before Task3 consumes the SDK in hermetic release builds, obtain operator approval
to publish the tested `hotspot-bridge/v0.1.0` module and record its actual commit/
sum. Update native module requirements to that real version and regenerate normal
prepared modfiles. Use GOWORK=off for subsequent -modfile commands. If publication
is not authorized/available, this is a real release handoff blocker; keep validated
local-workspace results and continue only independent B work, not fake a pin.

### Task 3: Add the separately negotiated observation profile

**Files:** Named observed files/CLI/native additions; tooling pack/manifest tests;
exact relevant mirrors; new `docs/plans/2026-10-18-hotspot-observed-profile-contract.md`.
**Interfaces:** `identity.ObserveProfile(pid, expected)`, `capture.RunObserved`,
`capture.RunObservedControlled` and `ObservationSchema=3`; existing RunProduction
and ProductionSchema2 retain exact contracts. `Config` gains a private/internal
profile decision, not a silent fallback flag.

Exact new signatures/types produced by this task:

```go
// internal/identity
type ObservedTarget struct { Target wire.Target; JVMBuildID, JVMSHA256 string }
func ObserveProfile(pid uint32, expected wire.Target) (ObservedTarget, error)
// internal/capture
const ObservationSchema = 3
func RunObserved(context.Context, Config, io.Writer) error
func RunObservedControlled(context.Context, Config, io.Writer, *os.File) error
// observed_candidate.go: //go:build mw_hotspot_qualification
func RunObservedCandidate(context.Context, Config, io.Writer, *os.File) error
// cmd/mw-hotspot-collector, private parse result
type Request struct {
    Protocol int
    Profile wire.Profile
    Target wire.Target
    Duration time.Duration
    StrictEpoch, DrainFull, Control bool
}
func parseCaptureRequest([]string) (Request, error)
```

- [ ] **Step1: Add negotiation/admission regression tests.**

```go
func TestObservationNeverUsesStrictAdmissionByAccident(t *testing.T) {
    for _, args := range [][]string{
        {"--capture", "--protocol-version=2", "--runtime-profile=otel_hotspot25_observed_v1"},
        {"--capture", "--protocol-version=3", "--runtime-profile=temurin25_xint_serial"},
        {"--capture", "--protocol-version=3", "--runtime-profile=otel_hotspot25_observed_v1", "--hotspot-identity=safepoint-epoch"},
    } {
        if code := run(args); code != 2 { t.Fatalf("bad negotiation %v: %d", args, code) }
    }
}
```

Negative CLI tests alone would already pass on an unknown flag. Also extract the
pure `parseCaptureRequest(args []string) (Request,error)` helper in this task;
`Request` contains Protocol/Profile/Target/Duration/StrictEpoch/DrainFull/Control.
Add this positive test before enabling its implementation:

```go
func TestParseObservedRequestDoesNotAttach(t *testing.T) {
    r, err := parseCaptureRequest([]string{"--capture", "--protocol-version=3",
        "--runtime-profile=otel_hotspot25_observed_v1", "--pid=123",
        "--expected-start-ticks=456", "--expected-cgroup-id=789", "--expected-mntns=42",
        "--hotspot-identity=unknown", "--drain-full", "--control-stdin", "--duration=15s"})
    if err != nil || r.Protocol != 3 || r.Profile != wire.Observed25 || r.Target.StartTicks != 456 {
        t.Fatal("valid ordinary-JVM request rejected before native admission", r, err)
    }
}
```

The parser has no process/filesystem/kernel effects; actual profile admission is
separate. Add direct unit tests for missing/changed expected birth/cgroup/mount namespace,
unknown JVM ELF, normal tiered/G1 vs old strict rejection, and no producer/owner
acquisition before valid admission. Reuse actual identity ELF/map fixtures.

- [ ] **Step2: Run RED, then implement explicit profile decision.**

```go
switch schema {
case 2:
    // Existing strict path: unchanged profile and safepoint/full-drain checks.
case 3:
    if profile != wire.Observed25 || strictEpoch || !drainFull || !controlStdin {
        return errors.New("unsupported observed profile negotiation")
    }
default:
    return errors.New("unsupported production schema")
}
```

This fragment belongs in the new negotiation helper defined by this task; CLI
schema1 remains its explicit experimental branch. Validate all expected target
fields before attaching. Construct exec/pidfd owners for both owned profiles;
remove nil-exec-barrier assumptions from ready/AfterReadout without relaxing checks.

- [ ] **Step3: Preserve native error causes through ordinary symbolization.**
  The upstream getJITInfo/read paths can format errors with `%v`; do not depend on
  parsing those strings. Wrap the actual HotSpot RemoteMemory ReaderAt at attach
  in the observation addition; record the original first EPERM/EACCES/native read
  failure and route it to the existing fatal owner. Keep compile-ID/frame/task
  guards and bounded reads. No safepoint-check/metadata-lease flag is fabricated.

```go
func TestObservedReadPermissionIsFatal(t *testing.T) {
    cause := &os.PathError{Op: "read", Path: "/proc/123/mem", Err: syscall.EACCES}
    r := &failingReader{err: cause}
    var fatal error
    observed := newObservedReader(r, func(err error) { fatal = err })
    _, err := observed.ReadAt(make([]byte, 8), 0)
    if !errors.Is(err, syscall.EACCES) || !errors.Is(fatal, syscall.EACCES) {
        t.Fatal("lost native permission cause", err, fatal)
    }
}
type failingReader struct{ err error }
func (r *failingReader) ReadAt([]byte, int64) (int, error) { return 0, r.err }
```

`newObservedReader(io.ReaderAt, func(error)) io.ReaderAt` is private in the named
HotSpot addition; implement first-cause synchronized ownership, checked bounds and
the real reader interface, not a public testing abstraction. Ordinary missing or
evicted metadata is an explicit unavailable terminal; permission failure is not.

- [ ] **Step4: Add schema3 sink/manifest negotiation and round trips.** Schema3
  emits explicit `policy_eligibility:false`, actual lifecycle profile/validation/
  target fields, immutable event terminals and original summary flags. Do not add
  those claims to historical schema2 rows or change the Kotlin decoder to accept3.
  Pack collector-version3/wire3 as a separate bundle; strict bundle remains2/2.
  Add schema2-refuses3/3-refuses2 and actual DTO serialization tests.
- [ ] **Step5: Focused host/race/ABI tests; source qualification before new pins.**
  Use normal prep and recorded source selections, actual old/new controls and all
  enumerated pin consumers. Derive actual new patch IDs only after native additions
  settle; never guess hashes. Stage exact files and commit through the full hook.

```bash
cd "$M/profiler/hotspot-agentless"
GOWORK=off go test -race -buildvcs=false -modfile build/experiment.go.mod ./internal/capture ./internal/identity ./internal/tooling ./cmd/mw-hotspot-collector
cd "$M"
./gradlew :profiler:compileKotlin :profiler:checkKotlinAbi :profiler:ktlintCheck
git diff --check
git commit -m "feat(profiler): negotiate observational HotSpot capture separately"
```

Inspect/stage the task's named paths before commit. This commit remains unadmitted
until A-OBS; a passing host gate does not activate default-JIT runtime support.
The qualification-only entry point is compiled solely with
`mw_hotspot_qualification`; the shipped CLI has no experimental bypass option.

### Task 4: Execute A-OBS on ordinary JVMs and preserve a real verdict

**Files:** Experimental `internal/candidate/observed.go`, `observed_verify.go`, `observed_test.go`,
`observed_kernel_test.go`; `src/ObservedWorkload.java`; evidence/contract/native
guides and exact runtime source/pin changes only if an actual defect requires them.
**Interfaces:** `RunObservedMatrix(ctx, assets, output string) (ObservedVerdict,error)`;
`ObservedVerdict{Status, Profile, InputDigest, ReportPath string}` status is exactly
`qualified` or `rejected`. No caller may turn a rejected report into admission.
The matrix implementation/native test uses `//go:build mw_hotspot_qualification`
and calls internal `capture.RunObservedCandidate`; ordinary candidate/collector
builds cannot reference that test-only symbol. Pure route verifier tests remain
available without the tag. Native test entry is `TestObservedMatrixNative` and
requires explicit `MW_HOTSPOT_ASSETS`/`MW_OBSERVED_OUTPUT` directories; missing
inputs fail this selected gate, not skip it.
Put the route DTO/verifier in untagged observed_verify.go so its pure tests compile
normally; only the native matrix implementation/test is qualification-tagged.

- [ ] **Step1: Add an independent route-binding verifier and swap negative.**

```go
func TestObservedRoutesDetectSwap(t *testing.T) {
    got := []ObservedRoute{{Token: "a-1", Method: "routeB"}, {Token: "b-1", Method: "routeA"}}
    want := map[string]string{"a-1": "routeA", "b-1": "routeB"}
    if err := VerifyObservedRoutes(got, want); err == nil { t.Fatal("worker swap accepted") }
}
```

Define `ObservedRoute{Token,Method string}` and `VerifyObservedRoutes([]ObservedRoute,
map[string]string) error` in the named candidate file. Check every useful marked
stack against its token's expected route, duplicate/missing tokens and raw terminal
denominators; require the spec's400/360/zero-wrong gate, not just nonempty output.

- [ ] **Step2: Add plain-JVM workload and bounded new matrix.** Java main has
  routeA/routeB→leaf→Files.newByteChannel with unique filenames, four platform
  workers,40 warmups and400 marked calls at20ms requested pacing. Main waits on a
  test gate before marked work and remains alive until collector stop. Launch with
  the pinned container's `java -cp /fixture/classes ObservedWorkload /fixture/gates 400 4 20`, with no collection agent
  or forced interpreter/GC options. Record actual flags/maps/version, not inference.
  Main's four positional inputs are gate directory, marked count, worker count and
  pace milliseconds (four arguments after the class); compile the actual source
  with `javac -d /fixture/classes /fixture/ObservedWorkload.java` in the owned pinned
  target container. Test-only gates are not application-agent instrumentation.
- [ ] **Step3: Run once per distinct required case.** Plain tiered/G1; same
  workload with compilation/code-cache churn; class load/unload; target exec/exit;
  permission denial. Preserve first outcomes, raw failures, independent source/
  compiler/target/collector/cleanup receipts. No retries until green. Qualified
  means the controlled observational claim, not concurrent-reclamation ownership.

```go
func TestObservedMatrixNative(t *testing.T) {
    assets, output := os.Getenv("MW_HOTSPOT_ASSETS"), os.Getenv("MW_OBSERVED_OUTPUT")
    if assets == "" || output == "" { t.Fatal("explicit native gate inputs required") }
    ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
    defer cancel()
    verdict, err := RunObservedMatrix(ctx, assets, output)
    if err != nil { t.Fatal(err) }
    if verdict.Status != "qualified" { t.Fatalf("ordinary-JVM gate rejected: %#v", verdict) }
}
```

Compile before execution and hash the binary. Run the selected test in the same
explicit rootful/privileged host-PID qualification environment used by existing
candidate ownership tooling; the test orchestrates disposable target containers,
so its Docker API access is test-only, not a node-agent runtime requirement:

```bash
cd "$M/experiments/hotspot-agentless"
CGO_ENABLED=0 GOWORK=off go test -buildvcs=false -modfile build/experiment.go.mod -tags=mw_hotspot_qualification \
  -c -o build/observed-matrix.test ./internal/candidate
sha256sum build/observed-matrix.test
E="$M/build/observed-gate"
mkdir -p "$E"
test ! -e "$E/result"
ASSETS="$M/profiler/hotspot-agentless/build/assets-observed-candidate"
GO_IMAGE="$(cd "$M/profiler/hotspot-agentless" && go run ./cmd/mw-collector-tools image --key go)"
docker --host unix:///var/run/docker.sock run --rm --pull=never --network=none \
  --privileged --pid=host -v "$M:$M:ro" -v "$E:$E" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e DOCKER_HOST=unix:///var/run/docker.sock -e MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock \
  -e "MW_HOTSPOT_ASSETS=$ASSETS" -e "MW_OBSERVED_OUTPUT=$E/result" \
  -w "$M/experiments/hotspot-agentless" "$GO_IMAGE" \
  "$M/experiments/hotspot-agentless/build/observed-matrix.test" \
  -test.run='^TestObservedMatrixNative$' -test.count=1 -test.timeout=240s
```

Before this block, Task3's normal packer writes assets-observed-candidate with its
schema3 manifest; every source/compiler selection receipt precedes compilation,
and object/binary hashes precede invocation. The explicit command is not permission
to reuse an existing output, skip receipt checks or retry a rejection.
Record the actual host Go version for this static test binary separately from
the locked Go image used to execute it and build shipped helpers; the runtime
image does not retroactively identify the test compiler. No CGO/libc compatibility
assumption is hidden in that execution recipe.
- [ ] **Step4: Review candidate verdict, then reproduce admitted bundles.**
  Preserve a rejected result without admission. A qualified result requires the
  named operator review before changing the production admission table; rebuild
  the actual shipped CLI after that source change. Build strict/observed bundles from
  locked sources twice, verify full byte/OCI equality and wrong-profile/pin refusal.
  Reuse unchanged native allocator proofs only after byte/selection equality.
  Run actual public strict acceptance for changed shared ownership paths, plus
  observed SDK/CLI capture acceptance; cached/skipped cases are not live proof.
- [ ] **Step5: Record final gate, commit and review.** Write candidate and actual
  admitted-CLI results separately; an admission source change invalidates an old
  helper hash until rebuilt/checked. Preserve strict profile/historical failures.
  Run focused/backlog/link checks and the unchanged mandatory full hook.

### Task 5: Produce an actual release handoff for C/D

**Files:** `hotspot-bridge/README.md`, native README/operator guide, generated
`build/kubescape-handoff/provider.json`; release/version wiring only after approval.
**Interfaces:** Actual keys listed in the master plan; no fake future pin values.

- [ ] **Step1: Verify the two consumers import the shared verifier/DTO source**;
  no duplicated validation fork and no BPF/OTel dependencies in the bridge module.

```bash
cd "$M/hotspot-bridge"
GOWORK=off go test -race ./... -count=1
go list -deps -f '{{if not .Standard}}{{.ImportPath}}{{end}}' ./...
```

Expected nonstandard packages are only this module's own packages.
- [ ] **Step2: Verify the actual module release** `hotspot-bridge/v0.1.0` from
  Task2 and its recorded commit/sum; ensure release builds used it with GOWORK=off.
  No later SDK code change may masquerade as those same immutable release bytes.
  Tag/push/publication remains operator-controlled; do not fabricate registry pins.
- [ ] **Step3: Generate provider.json from actual build outputs.** Hash manifest,
  helper and every OCI descriptor, record source/compiler/report IDs and profile.
  Verify native readers/physical owners have retired; the artifact does not certify
  global loss or policy eligibility. Include all master-plan keys, notably actual
  assets_dir and qualification_status=qualified/rejected. No native artifact is
  called qualified when the report is rejected or required tools were unavailable.
- [ ] **Step4: Self-review API/ABI and instruction/reference drift; commit the
  exact documentation/wiring scope and complete task review.** C remains blocked
  on a qualified A-OBS result and real SDK dependency provenance, not on a promise
  to finish them while wiring node-agent.
