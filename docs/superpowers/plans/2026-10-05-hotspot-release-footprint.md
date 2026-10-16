---
document_type: execution_plan
base_revision: 27846a59
status: approved_inline_complete
---

# HotSpot Release Collector Footprint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for inline execution after operator approval. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce bytes hashed by interpreter admission by stripping release-only collector debug/symbol sections, and measure actual benefit without weakening integrity.

**Architecture:** Apply Go linker `-s -w` to the existing explicit collector builds; preserve the patch fingerprint and embedded build information. Verify actual ELF sections/size, reproducibility and installed public behavior, then compare pinned interpreter admission on the original and candidate bundles. The operator approved inline execution; runtime guarantees remain unchanged.

**Tech Stack:** Pinned Go1.26 static linux-amd64 build, existing Python/Make/Gradle packaging, readelf for optional artifact verification, pinned Temurin25/Kotlin measurements.

**Spec:** `docs/plans/2026-10-05-hotspot-interpreter-performance-evidence.md` and `docs/internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md`.

## Global Constraints

- Exact Linux-amd64 pinned Temurin25 `-Xint -XX:+UseSerialGC` live profile unchanged.
- Collector2/captureABI2/wire2, dependency/image pins and overlaySHA unchanged.
- Hash every remaining asset byte and validate the entire OCI graph; no cache/native provider/bypass.
- Readiness10s/session60s/stop<=2 remaining seconds and native pending/worker budgets unchanged.
- Kotlin4096 occurrences/33,554,432 wire-payload bytes; always-incomplete coverage.
- No public API/dependency/native protocol change; native asset builds remain explicit/optional.
- Keep approved baseline bundle immutable; generate candidates at new destinations.
- Preserve full source/licenses in distributions and unrelated operator worktree edits.
- No expected percentage or admission bound is an acceptance criterion before measurement.

---

## File Structure

|File|Responsibility|
|---|---|
|Modify `profiler/hotspot-agentless/Makefile:18–30`|Release linker flags for host/container collector builds|
|Create `profiler/hotspot-agentless/scripts/test-release-footprint.py`|Actual baseline/candidate ELF/build-info verification|
|Modify `profiler/hotspot-agentless/README.md`|Release debug-symbol tradeoff/rebuild commands|
|Create `docs/plans/2026-10-05-hotspot-release-footprint-evidence.md`|Artifact hashes, measured bytes/admission/native compatibility|
|Modify performance backlog issue|Record demonstrated partial benefit; retain dense-readout follow-up|

### Task1: Qualify stripped release artifact

**Interfaces:** Existing `make build`, `make container-build`, `assets.py pack/verify/install` retain their inputs. New test CLI `test-release-footprint.py <baseline> <candidate>` exits nonzero for missing debug stripping, lost Go provenance or no size reduction.

- [x] Read profiler instructions and preserve baseline `build/assets/mw-hotspot-collector` at an explicit new measurement path; record SHA256 before changing build flags.
- [x] Write actual-artifact failing verifier:

```python
import pathlib
import subprocess
import sys

baseline, candidate = map(pathlib.Path, sys.argv[1:])
sections = subprocess.check_output(["readelf", "-SW", str(candidate)], text=True)
assert ".debug_info" not in sections
assert ".debug_line" not in sections
assert ".symtab" not in sections
assert candidate.stat().st_size < baseline.stat().st_size
info = subprocess.check_output(["go", "version", "-m", str(candidate)], text=True)
assert "io.mazewall/experiments/hotspot-agentless/cmd/mw-hotspot-collector" in info
assert b"e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb" in candidate.read_bytes()
assert "CGO_ENABLED=0" in info and "GOARCH=amd64" in info
```

- [x] Run `python3 scripts/test-release-footprint.py build/assets/mw-hotspot-collector build/assets/mw-hotspot-collector`; observe failure because `.debug_info` is present. Preserve this observed red output.
- [x] Change both existing collector linker strings to:

```make
-ldflags "-s -w -X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=$$patch"
```

The container command uses its existing `$$GADGET_PATCH_ID` variable in place of `$$patch`. Preserve `CGO_ENABLED=0`, GOOS/GOARCH, trimpath and buildvcs=false.
- [x] Run `make container-build` with the existing pinned Go image, then the verifier comparing immutable `build/assets/mw-hotspot-collector` with `build/mw-hotspot-collector`; require pass and actual reduced bytes. Rebuild once, compare SHA256 for reproducibility.
- [x] Pack/install candidate into new paths, retaining original `build/assets`:

```bash
python3 scripts/assets.py pack --collector build/mw-hotspot-collector \
  --store build/assets/oci \
  --image ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68 \
  --output build/footprint-candidate-assets
python3 scripts/assets.py install --bundle build/footprint-candidate-assets \
  --output build/footprint-candidate-installed
```

Recorded candidate helperSHA335e6704ea10bc9b2ab3a0ac2d9387271babea7a04d943c2e89b8dd178b39ea2,
manifestSHA95a8263522f1cfac127b9111b981a582a37df5bb41a0bf51952fd080758cb5a2.
Go -trimpath omits linker flags from build info; artifact verification checks the
linked patch value and the public matrix verifies the runtime provider separately.
- [x] Commit linker changes, verifier, README explanation and scoped plan/backlog: `build(profiler): strip release collector debug symbols` (c9cad756).

### Task2: Measure benefit and verify public compatibility

**Interfaces:** Consume original and newly verified candidate asset directories. Use existing `HotSpotPerformanceMain assets <directory> <new-report.json>` and actual `HotSpotPublicIntegrationTest`; no new runtime API.

- [x] Run3 fresh pinned interpreter processes per bundle with identical flags/classpath and no overlap:

```bash
LIB=$(realpath profiler/build/install/tier-e-daemon/lib)
BASE=$(realpath profiler/hotspot-agentless/build/assets)
CAND=$(realpath profiler/hotspot-agentless/build/footprint-candidate-installed)
OUT="$PWD/profiler/build/reports/hotspot-footprint-comparison"
mkdir "$OUT"
for repeat in 0 1 2; do
  order="baseline candidate"
  if [ "$repeat" = 1 ]; then order="candidate baseline"; fi
  for mode in $order; do
    INPUT="$BASE"
    if [ "$mode" = candidate ]; then INPUT="$CAND"; fi
    docker --host unix:///var/run/docker.sock run --pull=never --rm \
      --network=none --user "$(id -u)" \
      --mount "type=bind,src=$LIB,dst=$LIB,readonly" \
      --mount "type=bind,src=$INPUT,dst=$INPUT,readonly" \
      --mount "type=bind,src=$OUT,dst=$OUT" \
      eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6 \
      java -Xint -XX:+UseSerialGC -cp "$LIB/*" \
      io.mazewall.profiler.hotspot.performance.HotSpotPerformanceMain \
      assets "$INPUT" "$OUT/$repeat-$mode.json"
  done
done
```

Record complete phase rows/bytes/hashes, not only medians. Fresh processes do not imply cold filesystem cache.
- [x] Run the existing corruption/version/OCI tests:

```bash
./gradlew :profiler:test --tests '*HotSpotAssetsTest'
python3 profiler/hotspot-agentless/scripts/test-assets.py
python3 profiler/hotspot-agentless/scripts/test-distribution.py
```

- [x] Run candidate native matrix explicitly:

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock \
MW_HOTSPOT_NATIVE_ACCEPTANCE=true \
MW_HOTSPOT_ASSET_TEST_DIR=/home/leanid/Documents/code/java/jseccomp/profiler/hotspot-agentless/build/footprint-candidate-installed \
./gradlew :profiler:installDist :profiler:integrationTest \
  --tests '*HotSpotPublicIntegrationTest' -x :profiler:rootfulContainerTest
```

Inspect JUnit execution counts, useful ordered frames and actual cleanup. Dense diagnostic may still reject; preserve it honestly.
- [x] Record ELF section differences, build info, exact hashes, all admission rows, source/licenses and actual public outcomes in the evidence file. Keep the performance issue open if dense readout or admission bounds remain unproved.
- [x] Run `./scripts/adkw check-backlog`, `git diff --check`, `./scripts/lint.sh`, `./gradlew build`; commit `docs(profiler): record stripped collector admission comparison`.

## Approval checkpoint

Approved inline and verified: helper28.7254% smaller, matched interpreter checksum
median70.944s ->44.966s,7actual native/public cases pass. Live admission still
69.8–78.3s and dense run still rejects; this is a byte reduction, not a latency
bound. See [evidence](../../plans/2026-10-05-hotspot-release-footprint-evidence.md).

This candidate reduces input bytes rather than replacing cryptography. If reduction
is inadequate, separately design/review digest acceleration; do not introduce a
provider or privileged verification helper under this footprint task. Parser/mapper
work remains independently scoped.
