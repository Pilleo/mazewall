---
document_type: execution_plan
base_revision: 8da8b4efcc0b72d9c4f06cf798e6daee0d9b1120
status: proposed_source_audit_not_runtime_activation
---

# Upstream HotSpot Generation Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegate only after operator selection; no additional worktree is authorized.

**Goal:** Characterize and reuse the existing nmethod compile-ID check, then report its presence without claiming a native lifetime mechanism.

**Architecture:** A test-only Go overlay exercises the actual prepared upstream decoder without modifying its source graph. One canonical, explicitly mirrored audit model describes reviewed pinned source; the candidate runner uses that local model after common asset verification. Runtime admission and new capture fields remain under their separate proof/approval gates.

**Tech Stack:** Existing Go1.26.8/prepared modules/Go overlays/JUnit compatibility checks; pinned upstream ef6cf294 and existing candidate/driver/tooling packages; no dependencies.

**Spec:** `docs/plans/2026-10-17-hotspot-upstream-alignment-spec.md` A/D, supplementing `docs/plans/2026-10-17-hotspot-remaining-work-spec.md` R5.

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

## Scope, supersession and file structure

This replaces the **unexecuted** old Plan05 ABI-only audit and duplicated candidate
sidecar. `Facts`/`Evaluate` remain offline models, unchanged. Existing original
native reports, completed work and old source-receipt limitations remain intact.
The production patch input list does not include internal/qualification testdata:
host overlays therefore need no production patch/pin or generated-source edit.

|Action/file|Responsibility|
|---|---|
|Create canonical `profiler/hotspot-agentless/internal/qualification/jitgc/testdata/upstream_compile_id_test.go` and exact experiment mirror|Characterize actual unexported upstream getJITInfo through a build overlay|
|Create canonical `profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit.go` and `capture_audit_test.go`, plus exact experiment mirrors|One versioned source-contract model; no fabricated runtime qualification|
|Create `experiments/hotspot-agentless/internal/candidate/capture_audit.go` and `capture_audit_test.go`|Bounded manifest-to-source-contract sidecar|
|Modify `experiments/hotspot-agentless/internal/candidate/runner.go:40-84`|Common verifier before output/process acquisition; one sidecar per fresh run|
|Create `docs/plans/2026-10-17-hotspot-upstream-generation-evidence.md`; update JIT issue/public guide|Actual source/test/report receipts and accurate remaining mechanism decision|

Read profiler/AGENTS.md; current qualification/jitgc files; prepared HotSpot
data.go, instance.go, method.go and instance_test.go; candidate runner/driver/
tooling verification. Do not patch generated dependencies or copy source from main.

Every command block starts at the selected checkout root unless it contains its
own `cd`; explicitly set that workdir for each tool call. Shell cwd/variables do
not persist between steps. Each block below initializes the variables it uses.

### Task 1: Characterize the actual upstream generation guard without changing pins

**Files:**
- Create: `profiler/hotspot-agentless/internal/qualification/jitgc/testdata/upstream_compile_id_test.go`.
- Create exact mirror: `experiments/hotspot-agentless/internal/qualification/jitgc/testdata/upstream_compile_id_test.go`.
- Generated evidence only: `profiler/hotspot-agentless/build/reports/upstream-generation/` (fresh destination).

**Interfaces:**
- Consumes existing unexported `(*hotspotInstance).getJITInfo(libpf.Address, uint32) (*hotspotJITInfo, error)`, `hotspotData.GetOrInit`, `Attach` and `addrToJITInfo` from the actual prepared module.
- Produces `TestPinnedCompileIDCacheHit`, `TestPinnedCompileIDRejectsStaleCache`, `TestPinnedCompileIDRejectsFreshMismatch` and recorded baseline/two-mutant results. These are host source-characterization receipts, not a loaded-BPF/live lease proof.

- [ ] **Step 1: Write this complete fixture** in canonical testdata; the directory
  name keeps it out of ordinary local-package compilation:

```go
package hotspot

import (
    "bytes"
    "encoding/binary"
    "strings"
    "testing"

    "go.opentelemetry.io/ebpf-profiler/libpf"
    "go.opentelemetry.io/ebpf-profiler/remotememory"
)

func pinnedGenerationFixture(t *testing.T, current uint32) *hotspotInstance {
    t.Helper()
    raw := make([]byte, 64)
    binary.LittleEndian.PutUint32(raw[16:], current)
    data := &hotspotData{}
    _, err := data.GetOrInit(func() (hotspotVMData, error) {
        value := hotspotVMData{}
        value.vmStructs.Nmethod.Sizeof = 16
        value.vmStructs.Nmethod.CompileID = 0
        return value, nil
    })
    if err != nil { t.Fatal(err) }
    instance, err := data.Attach(nil, 0, 0,
        remotememory.RemoteMemory{ReaderAt: bytes.NewReader(raw)})
    if err != nil { t.Fatal(err) }
    return instance.(*hotspotInstance)
}

func TestPinnedCompileIDCacheHit(t *testing.T) {
    instance := pinnedGenerationFixture(t, 9)
    cached := &hotspotJITInfo{compileID: 7}
    instance.addrToJITInfo.Add(libpf.Address(16), cached)
    got, err := instance.getJITInfo(libpf.Address(16), 7)
    if err != nil || got != cached { t.Fatal(got, err) }
}

func TestPinnedCompileIDRejectsStaleCache(t *testing.T) {
    instance := pinnedGenerationFixture(t, 9)
    instance.addrToJITInfo.Add(libpf.Address(16), &hotspotJITInfo{compileID: 7})
    got, err := instance.getJITInfo(libpf.Address(16), 8)
    if got != nil || err == nil || !strings.Contains(err.Error(), "JIT info evicted since eBPF snapshot") {
        t.Fatal("stale cache bypassed captured generation", got, err)
    }
}

func TestPinnedCompileIDRejectsFreshMismatch(t *testing.T) {
    instance := pinnedGenerationFixture(t, 9)
    got, err := instance.getJITInfo(libpf.Address(16), 8)
    if got != nil || err == nil || !strings.Contains(err.Error(), "JIT info evicted since eBPF snapshot") {
        t.Fatal("fresh nmethod bypassed captured generation", got, err)
    }
}
```

The positive cache case deliberately has a different current remote value: cached
owned symbol information for captured generation7 is distinct from a later live
nmethod9. It does not assert live retention. PID0/Attach(nil) is the existing pure
host-test construction pattern, not a native descriptor or process receipt.

- [ ] **Step 2: Format/mirror the fixture and create a fresh overlay/receipt**.
  Run from the checkout root; the Python code generates evidence, not product tooling:

```bash
gofmt -w profiler/hotspot-agentless/internal/qualification/jitgc/testdata/upstream_compile_id_test.go
mkdir -p experiments/hotspot-agentless/internal/qualification/jitgc/testdata
cp profiler/hotspot-agentless/internal/qualification/jitgc/testdata/upstream_compile_id_test.go experiments/hotspot-agentless/internal/qualification/jitgc/testdata/upstream_compile_id_test.go
python3 - <<'PY'
import hashlib, json, subprocess
from pathlib import Path
root = Path.cwd()
native = root / "profiler/hotspot-agentless"
out = native / "build/reports/upstream-generation"
out.mkdir(parents=True, exist_ok=False)
module = "go.opentelemetry.io/ebpf-profiler"
directory = Path(subprocess.check_output([
    "go", "list", "-modfile", "build/experiment.go.mod", "-m", "-f", "{{.Dir}}", module,
], cwd=native, text=True).strip()).resolve()
directory.relative_to((native / "build/deps").resolve())
fixture = native / "internal/qualification/jitgc/testdata/upstream_compile_id_test.go"
target = directory / "interpreter/hotspot/mw_compile_id_overlay_test.go"
assert not target.exists()
original = directory / "interpreter/hotspot/instance.go"
raw = original.read_text()
assert raw.count("if jit.compileID == addrCheck {") == 1
assert raw.count("if compileID != addrCheck {") == 1
inputs = [fixture, original, directory / "interpreter/hotspot/data.go",
          directory / "support/ebpf/hotspot_tracer.ebpf.c", native / "go.mod",
          native / "go.sum", native / "build/experiment.go.mod",
          native / "build/experiment.go.sum", native / "build/patch-manifest.json"]
receipt = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
(out / "source-before.json").write_text(json.dumps(receipt, indent=2) + "\n")
(out / "go-version.txt").write_text(subprocess.check_output(["go", "version"], text=True))
(out / "module-directory.txt").write_text(str(directory) + "\n")
(out / "overlay.json").write_text(json.dumps({"Replace": {str(target): str(fixture)}}) + "\n")
for label, before, after in [
    ("cache-mutant", "if jit.compileID == addrCheck {", "if true {"),
    ("read-mutant", "if compileID != addrCheck {", "if false {"),
]:
    changed = out / (label + ".go")
    changed.write_text(raw.replace(before, after, 1))
    (out / (label + ".json")).write_text(json.dumps({"Replace": {
        str(target): str(fixture), str(original): str(changed),
    }}) + "\n")
print(out)
PY
```

Prepared directories are intentionally outside GOMODCACHE. Go's overlay affects
compilation, not runtime filesystem reads. A different prepared API/pin is a
baseline mismatch: reconcile it from actual source; do not edit dependency files.

- [ ] **Step 3: Build/run the unchanged-source characterization**:

```bash
cd profiler/hotspot-agentless
OUT="$PWD/build/reports/upstream-generation"
go test -c -race -buildvcs=false -modfile build/experiment.go.mod -overlay "$OUT/overlay.json" -o "$OUT/baseline.test" go.opentelemetry.io/ebpf-profiler/interpreter/hotspot
timeout -k 5s 120s "$OUT/baseline.test" -test.run '^TestPinnedCompileID' -test.count=1 -test.v > "$OUT/baseline.log" 2>&1
sha256sum "$OUT/baseline.test" > "$OUT/baseline-binary.sha256"
```

Expected: three tests PASS without skips. They characterize existing behavior and
can already be green; do not report that as newly implemented generation support.
Retain the exact build command/cwd and source-before receipt before compilation.

- [ ] **Step 4: Prove both guards matter with virtual-source mutations**:

```bash
cd profiler/hotspot-agentless
OUT="$PWD/build/reports/upstream-generation"
for label in cache-mutant read-mutant; do
  go test -c -race -buildvcs=false -modfile build/experiment.go.mod -overlay "$OUT/$label.json" -o "$OUT/$label.test" go.opentelemetry.io/ebpf-profiler/interpreter/hotspot || exit
  case "$label" in
    cache-mutant) CASE='^TestPinnedCompileIDRejectsStaleCache$';;
    read-mutant) CASE='^TestPinnedCompileIDRejectsFreshMismatch$';;
  esac
  status=0
  timeout -k 5s 120s "$OUT/$label.test" -test.run "$CASE" -test.count=1 -test.v > "$OUT/$label.log" 2>&1 || status=$?
  test "$status" -eq 1 || exit 1
done
python3 - <<'PY'
import hashlib, json
from pathlib import Path
out = Path("build/reports/upstream-generation")
for name, expected in [("cache-mutant", "TestPinnedCompileIDRejectsStaleCache"),
                       ("read-mutant", "TestPinnedCompileIDRejectsFreshMismatch")]:
    assert "--- FAIL: " + expected in (out / (name + ".log")).read_text()
for name, digest in json.loads((out / "source-before.json").read_text()).items():
    assert hashlib.sha256(Path(name).read_bytes()).hexdigest() == digest, name
print("both guards exercised; original sources/module/patch inputs unchanged")
PY
```

Timeout/build failure is not a killed regression. Preserve complete output and
require the named test failure. An induced panic may fail the read mutant, but it
must still identify the named rejected-generation case; it is not native proof.

- [ ] **Step 5: Run owning host checks, record receipts and commit only fixtures**:

```bash
(cd profiler/hotspot-agentless && go test -race ./internal/qualification/jitgc)
./scripts/adkw check-backlog
git diff --check
git add profiler/hotspot-agentless/internal/qualification/jitgc/testdata/upstream_compile_id_test.go experiments/hotspot-agentless/internal/qualification/jitgc/testdata/upstream_compile_id_test.go
git commit -m "test(profiler): characterize pinned upstream compile-id guards"
```

The actual full-build commit hook is mandatory. Record this commit and the inputs,
overlay, test-binary hashes, execution output and source-after equality in the new
generation evidence. No accepted patch/native asset update is warranted here.

### Task 2: Replace the absent-generation claim with a scoped source audit

**Files:**
- Create: `profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit.go` and `capture_audit_test.go`.
- Create exact mirrors under `experiments/hotspot-agentless/internal/qualification/jitgc/`.

**Interfaces:**
- Consumes Task1 characterization and reviewed pinned capture/decoder source; existing offline `Facts`/`Evaluate` are not changed or used for admission.
- Produces `AuditCapture(abi uint32, profilerRevision string, patchSHA256 string) (CaptureAudit, error)` and the exact JSON fields below. This signature supersedes the unimplemented old ABI-only proposal; no supported API is broken.

- [ ] **Step 1: Write the new failing audit tests**:

```go
package jitgc

import (
    "reflect"
    "testing"
)

func TestCompileIDPresenceDoesNotQualifyLifetime(t *testing.T) {
    const revision = "ef6cf29492c8a980f889052903fa2dd0c0765cc0"
    const patch = "618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c"
    got, err := AuditCapture(2, revision, patch)
    want := []string{"metadata_stability_through_decode", "code_lifetime_through_decode", "concurrent_reclamation_exclusion"}
    if err != nil || got.CaptureABI != 2 || got.ProfilerRevision != revision || got.PatchSHA256 != patch ||
        got.EvidenceScope != "reviewed_pinned_source_contract" ||
        got.CandidateScope != "compiled_or_concurrent_gc" ||
        !got.CompileIDCaptured || !got.CompileIDValidated || got.ProductionQualified ||
        !reflect.DeepEqual(got.Missing, want) { t.Fatal(got, err) }
    got.Missing[0] = "changed"
    again, err := AuditCapture(2, revision, patch)
    if err != nil || !reflect.DeepEqual(again.Missing, want) { t.Fatal(again, err) }
    for _, abi := range []uint32{0, 1, 3} {
        if _, err := AuditCapture(abi, revision, patch); err == nil { t.Fatal("unreviewed ABI accepted", abi) }
    }
    if _, err := AuditCapture(2, "36f875fd7d625a80de939a9325e1cde8fd43a9f6", patch); err == nil {
        t.Fatal("comparison main treated as reviewed production source")
    }
    if _, err := AuditCapture(2, revision, "0000000000000000000000000000000000000000000000000000000000000000"); err == nil {
        t.Fatal("unreviewed local overlay treated as reviewed source")
    }
}
```

- [ ] **Step 2: Observe RED** from canonical `go test -race ./internal/qualification/jitgc`:
  expected missing AuditCapture/CaptureAudit, not a native capability skip.
- [ ] **Step 3: Implement `capture_audit.go`**:

```go
package jitgc

import "fmt"

type CaptureAudit struct {
    CaptureABI uint32 `json:"capture_abi"`
    ProfilerRevision string `json:"profiler_revision"`
    PatchSHA256 string `json:"patch_sha256"`
    EvidenceScope string `json:"evidence_scope"`
    CandidateScope string `json:"candidate_scope"`
    CompileIDCaptured bool `json:"compile_id_captured_in_reviewed_source"`
    CompileIDValidated bool `json:"compile_id_validated_in_reviewed_source"`
    Missing []string `json:"unqualified_lifetime_requirements"`
    ProductionQualified bool `json:"production_qualified"`
}

func AuditCapture(abi uint32, profilerRevision string, patchSHA256 string) (CaptureAudit, error) {
    if abi != 2 || profilerRevision != "ef6cf29492c8a980f889052903fa2dd0c0765cc0" ||
        patchSHA256 != "618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c" {
        return CaptureAudit{}, fmt.Errorf("unreviewed capture ABI/profiler source")
    }
    return CaptureAudit{
        CaptureABI: abi, ProfilerRevision: profilerRevision, PatchSHA256: patchSHA256,
        EvidenceScope: "reviewed_pinned_source_contract",
        CandidateScope: "compiled_or_concurrent_gc",
        CompileIDCaptured: true, CompileIDValidated: true,
        Missing: []string{"metadata_stability_through_decode", "code_lifetime_through_decode", "concurrent_reclamation_exclusion"},
        ProductionQualified: false,
    }, nil
}
```

This is a versioned description of reviewed source, not feature discovery from an
ABI or arbitrary object. The boolean JSON names state their evidence scope.
Never feed them to `Facts` as native leases. Task1 receipts and the reviewed BPF
cookie source support the source statement; no loaded-object assertion is made.
The missing requirements apply to expanding into compiled/concurrent-GC candidates;
they do not revoke the separately qualified interpreted/Serial epoch profile.
The local patch is part of the reviewed key: the same upstream revision with an
unknown overlay must not inherit a positive source statement. If later approved
accounting changes the pin, repeat the actual-source characterization and review
before updating both mirrored audit/model-test keys. Keep old receipts intact.

- [ ] **Step 4: Format/mirror and observe GREEN**:

```bash
gofmt -w profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit.go profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit_test.go
cp profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit.go experiments/hotspot-agentless/internal/qualification/jitgc/capture_audit.go
cp profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit_test.go experiments/hotspot-agentless/internal/qualification/jitgc/capture_audit_test.go
(cd profiler/hotspot-agentless && go test -race ./internal/qualification/jitgc)
(cd experiments/hotspot-agentless && go test -race ./internal/qualification/jitgc)
cmp profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit.go experiments/hotspot-agentless/internal/qualification/jitgc/capture_audit.go
```

Expected: new audit plus existing negative lifetime models pass; neither can
qualify a native runtime. Repeat source-receipt capture before new binaries.

- [ ] **Step 5: Commit the exact four model/test files**:

```bash
git add profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit.go profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit_test.go experiments/hotspot-agentless/internal/qualification/jitgc/capture_audit.go experiments/hotspot-agentless/internal/qualification/jitgc/capture_audit_test.go
git commit -m "fix(profiler): distinguish compile-id checks from lifetime proof"
```

### Task 3: Publish the shared audit with verified candidate assets and a mechanism decision

**Files:**
- Create: `experiments/hotspot-agentless/internal/candidate/capture_audit.go`, `capture_audit_test.go`.
- Modify: `experiments/hotspot-agentless/internal/candidate/runner.go:40-84`.
- Create: `docs/plans/2026-10-17-hotspot-upstream-generation-evidence.md` and `docs/plans/2026-10-17-hotspot-jitgc-native-mechanism-decision.md`.
- Update: `docs/internals/backlog/issue-20261005-193139-440-qualify-pinned-hotspot-jit-and-gc-lifetime-candida.md`, `profiler/HOTSPOT_AGENTLESS.md`.

**Interfaces:**
- Consumes local mirrored `jitgc.AuditCapture(uint32, string, string)`, existing `tooling.Run(root string, args []string) error`, `driver.WriteJSON(path string, value any) error` and independent candidate identity/verifier/owners.
- Produces private `writeCaptureAudit(assets, output string) error`, one `capture-audit.json` at run root and summary key `capture_audit` with that relative path. No per-event/native wire field changes.

- [ ] **Step 1: Write the failing sidecar and pre-acquisition tests** in package
  candidate; imports encoding/json, os, path/filepath, testing and local jitgc:

```go
func TestSourceAuditCannotBecomeRuntimeQualification(t *testing.T) {
    assets, out := t.TempDir(), t.TempDir()
    path := filepath.Join(assets, "manifest.json")
    good := []byte(`{"capture_abi":2,"profiler_revision":"ef6cf29492c8a980f889052903fa2dd0c0765cc0",` +
        `"patch_sha256":"618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c"}`)
    if err := os.WriteFile(path, good, 0600); err != nil { t.Fatal(err) }
    if err := writeCaptureAudit(assets, out); err != nil { t.Fatal(err) }
    raw, err := os.ReadFile(filepath.Join(out, "capture-audit.json")); if err != nil { t.Fatal(err) }
    var got jitgc.CaptureAudit
    if err := json.Unmarshal(raw, &got); err != nil { t.Fatal(err) }
    if got.ProductionQualified || got.EvidenceScope != "reviewed_pinned_source_contract" ||
        got.CandidateScope != "compiled_or_concurrent_gc" ||
        !got.CompileIDCaptured || !got.CompileIDValidated || len(got.Missing) != 3 { t.Fatal(got) }
    for _, bad := range [][]byte{[]byte(`{"capture_abi":3}`), []byte("{"), {0xff}, make([]byte, 65537)} {
        if err := os.WriteFile(path, bad, 0600); err != nil { t.Fatal(err) }
        if err := writeCaptureAudit(assets, t.TempDir()); err == nil { t.Fatal("invalid source contract accepted") }
    }
}

func TestCandidateVerifiesAssetsBeforeCreatingOutput(t *testing.T) {
    root := t.TempDir()
    out := filepath.Join(root, "output")
    err := Run(root, []string{"--assets", t.TempDir(), "--output", out,
        "--docker-host", "unix:///nonexistent-upstream-audit-control.sock"})
    if err == nil { t.Fatal("missing assets accepted") }
    if _, err := os.Stat(out); !os.IsNotExist(err) { t.Fatal("output created before common admission", err) }
}
```

The minimal manifest is a unit fixture for a source-description helper, not a
trusted asset installation. The public Run path must complete the existing common
verifier before generating it or acquiring a compiler/target/collector.

- [ ] **Step 2: Observe RED**:

```bash
cd experiments/hotspot-agentless
go test -modfile build/experiment.go.mod ./internal/candidate -run 'TestSourceAuditCannotBecomeRuntimeQualification|TestCandidateVerifiesAssetsBeforeCreatingOutput' -count=1
```

Expected missing helper, then pre-admission output ordering failure before its
correction. Do not manufacture failure in already-passing identity/verifier tests.

- [ ] **Step 3: Implement the bounded helper** (package candidate):

```go
import (
    "encoding/json"
    "errors"
    "fmt"
    "io"
    "os"
    "path/filepath"
    "unicode/utf8"

    "io.mazewall/experiments/hotspot-agentless/internal/driver"
    "io.mazewall/experiments/hotspot-agentless/internal/qualification/jitgc"
)

func writeCaptureAudit(assets, output string) error {
    file, err := os.Open(filepath.Join(assets, "manifest.json"))
    if err != nil { return err }
    raw, readErr := io.ReadAll(io.LimitReader(file, 65537))
    if err := errors.Join(readErr, file.Close()); err != nil { return err }
    if len(raw) > 65536 || !utf8.Valid(raw) { return fmt.Errorf("invalid audit manifest bytes") }
    var manifest struct {
        CaptureABI uint32 `json:"capture_abi"`
        ProfilerRevision string `json:"profiler_revision"`
        PatchSHA256 string `json:"patch_sha256"`
    }
    if err := json.Unmarshal(raw, &manifest); err != nil { return err }
    audit, err := jitgc.AuditCapture(manifest.CaptureABI, manifest.ProfilerRevision, manifest.PatchSHA256)
    if err != nil { return err }
    return driver.WriteJSON(filepath.Join(output, "capture-audit.json"), audit)
}
```

In runner.go add the existing local tooling import. Immediately before the current
`os.Mkdir(out, 0755)` call, after absolute assets/output paths have been obtained:

```go
if err := tooling.Run(root, []string{"verify", "--bundle", assetRoot}); err != nil {
    return err
}
```

Immediately after successful fresh output-directory creation and before classes/
Docker compilation/target creation:

```go
if err := writeCaptureAudit(assetRoot, out); err != nil { return err }
```

Add `"capture_audit": "capture-audit.json"` to the existing final summary map.
Keep `production_qualified:false` and every case/verifier/ownership path unchanged.
Do not duplicate the three missing requirements in candidate code or import the
canonical module from the materialized experiment: use its exact local mirror.

- [ ] **Step 4: Green and actual candidate reporting**:

```bash
cd experiments/hotspot-agentless
go test -race -modfile build/experiment.go.mod ./internal/candidate ./internal/driver ./internal/qualification/jitgc
ROOT=$(git rev-parse --show-toplevel)
E="$ROOT/profiler/hotspot-agentless/build/reports/upstream-generation-task-3"
# Acquire E fresh; collect actual CLI go-list/source/module/toolchain/environment receipts before this build.
go build -buildvcs=false -modfile build/experiment.go.mod -o "$E/mw-experiment-tools-task-3" ./cmd/mw-experiment-tools
sha256sum "$E/mw-experiment-tools-task-3"
"$E/mw-experiment-tools-task-3" candidates --assets "$ROOT/profiler/hotspot-agentless/build/assets" --output "$ROOT/profiler/build/reports/hotspot-upstream-generation-candidates" --docker-host unix:///var/run/docker.sock
```

Fresh output must not exist. Capture current source/module/graph hashes **before**
build/run and record commands/cwd/binaries; do not treat Go's ancestor VCS stamp as
proof. Require actual independent identity, full token/terminal validation and
checked owner cleanup; source audit is identical for all admitted tuples while
per-tuple usefulness remains its real observation. All runtime/lifetime/policy
qualification stays false. Capability refusal is retained, not skipped or promoted.

- [ ] **Step 5: Record the actual mechanism decision and commit**. Evidence must
  distinguish existing source capture/validation, actual host regression results,
  observed candidate usefulness, loaded-object proof not performed, and unproved
  metadata/code/GC lifetime. The concrete decision table is:

|Question|Required statement at this stage|
|---|---|
|Compiled generation information|Existing `_compile_id` cookie and matching cache/read guard in reviewed pinned source; actual host characterization receipts linked|
|Loaded occurrence generation|No new live native generation qualification; do not infer it from ABI/sidecar/BTF names|
|Metadata/code validity through decode|Unqualified; inspect actual pin's nmethod unlink/reclaim/metadata dependencies before proposing a retained-owner or validated-snapshot mechanism|
|Concurrent reclamation|Unqualified; useful frames and equal integers alone are insufficient|
|Production admission|Refused; a separately reviewed mechanism specification and real adversarial/native proof are required|

Keep the current JIT issue open. Update the public guide's wording to existing
generation checks plus missing lifetime qualification. Do not add a new generation
field, lease boolean, whitelist entry, upstream revision or asset pin.

```bash
./scripts/adkw check-backlog
git diff --check
git add experiments/hotspot-agentless/internal/candidate/capture_audit.go experiments/hotspot-agentless/internal/candidate/capture_audit_test.go experiments/hotspot-agentless/internal/candidate/runner.go docs/plans/2026-10-17-hotspot-upstream-generation-evidence.md docs/plans/2026-10-17-hotspot-jitgc-native-mechanism-decision.md docs/internals/backlog/issue-20261005-193139-440-qualify-pinned-hotspot-jit-and-gc-lifetime-candida.md profiler/HOTSPOT_AGENTLESS.md
git commit -m "test(profiler): report existing generation checks without lifetime promotion"
```

Run the final block from checkout root; actual hook supplies the complete gate.
Retain Task1 receipts and all source/candidate failure evidence. Qualification of a
new runtime or upstream rebase is a subsequent approved project, not this audit.
