---
document_type: execution_plan
base_revision: 8da8b4efcc0b72d9c4f06cf798e6daee0d9b1120
status: proposed_diagnostics_not_dense_promotion
---

# Actual HotSpot Consumer Readout Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegate only after operator selection; no additional worktree is authorized.

**Goal:** Locate actual interpreted public-readout cost using retained live JFR and unchanged success-or-rejection/retirement contracts.

**Architecture:** Add an optional parameter only to the existing integration-test launcher, recording the actual pinned client JVM. The production reader, interpreter, native graph and budgets remain the measurement baseline. Three alternating unprofiled/profiled pairs produce a diagnosis and next-change decision, not an assumed optimization or latency promise.

**Tech Stack:** Existing Kotlin/JDK25/JFR/JUnit integration fixture, pinned Temurin image/rootful Docker, existing jfr CLI and standard-library analysis; no dependencies.

**Spec:** `docs/plans/2026-10-17-hotspot-upstream-alignment-spec.md` C/D and `docs/plans/2026-10-17-hotspot-remaining-work-spec.md` R2.

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

## File structure and baseline

|Action/file|Responsibility|
|---|---|
|Modify `profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotPublicIntegrationTest.kt:132-142,152-194,215-221`|Opt-in JFR flags, actual recording checks and retained recording in existing owned fixture|
|Read existing `HotSpotSupportedWorkloadMain.kt`, `HotSpotAcceptanceHarness.kt`, `HotSpotCollectorWorkers.kt`, `HotSpotSessionInterpreter.kt`, `HotSpotSessionProgress.kt`|Understand actual marker timing, process ownership, stdout mapping and finalization without changing them|
|Create `docs/plans/2026-10-17-hotspot-consumer-readout-evidence.md`|Paired actual outcomes/JFR attribution, limits and next measured proposal|
|Update current performance issue and `profiler/HOTSPOT_AGENTLESS.md`|Observed diagnostics and continued unsupported dense result if rejected|

Completed Plan02 measurements remain evidence; do not recreate its archived
baseline or implement its deferred HotSpotLineBuffer. The prior separate raw
capture is not a matching baseline for a new JFR client run. This task changes no
production parser/queue/lock, native map or API. Read profiler/AGENTS.md and testing
strategy before adding the fixture mode. A source review of named calls plus JFR
samples is evidence, not a demand to rerun unrelated packages.

Run every command block from the selected checkout root with explicit tool workdir;
do not assume a previous shell's cwd/exports persist. Repeated variables below are
intentional per-step initialization, not new configuration options.

### Task 1: Add a test-only live-client JFR mode

**Files:**
- Modify/Test: `profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotPublicIntegrationTest.kt`.

**Interfaces:**
- Consumes existing `currentJvmMeasurement(endpoint: ContainerEndpoint, directory: Path, dense: Boolean)`, owned fixture command/cleanup and current public evidence API.
- Produces private `currentJvmMeasurement(endpoint, directory, dense, readoutJfr: Boolean = false)`; the new named native test; a retained `readout.jfr` beside existing evidence/measurement/log files. No library or workload CLI option is added.

- [ ] **Step 1: Add the failing native fixture test** beside the existing dense
  diagnostic; the class's existing Linux/rootful/MW/timeout conditions apply:

```kotlin
@Test
fun `profiled dense interpreter diagnostic retains JFR and checked retirement`(
    endpoint: ContainerEndpoint,
    @TempDir directory: Path,
) = currentJvmMeasurement(endpoint, directory, dense = true, readoutJfr = true)
```

- [ ] **Step 2: Observe RED**, initially an unresolved readoutJfr parameter:

```bash
DOCKER_HOST=unix:///var/run/docker.sock MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock MW_HOTSPOT_NATIVE_ACCEPTANCE=true MW_HOTSPOT_ASSET_TEST_DIR="$PWD/profiler/hotspot-agentless/build/assets" ./gradlew :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest.profiled dense interpreter diagnostic*' --rerun
```

Capability skip is not RED/Green proof. Retain failure logs and do not loosen the
existing ordinary-UID or native permission/asset gates to run this test.

- [ ] **Step 3: Add the exact optional parameter and recording arguments**.
  Existing imports gain `jdk.jfr.consumer.RecordingFile`. Extend the private
  signature with `readoutJfr: Boolean = false`, preserving every old call. After
  `client`/`docker` are computed, before the existing try block, insert:

```kotlin
val readoutRecording = directory.resolve("readout.jfr")
val recordingArguments = if (readoutJfr) {
    listOf(
        "-XX:FlightRecorderOptions=stackdepth=128",
        "-XX:StartFlightRecording=filename=$readoutRecording,settings=profile," +
            "dumponexit=true,maxsize=16m,jdk.ExecutionSample#period=1ms," +
            "jdk.ObjectAllocationSample#throttle=1000/s,jdk.JavaMonitorEnter#threshold=1ms",
    )
} else {
    emptyList()
}
```

In the existing `endpoint.command` Java argument segment, insert the spread at
this exact position (keep the remaining arguments/mounts/timeout untouched):

```kotlin
HotSpotAcceptanceHarness.JDK,
"java",
"-Xint",
"-XX:+UseSerialGC",
*recordingArguments.toTypedArray(),
"-cp",
"$dist/*:$classes",
"io.mazewall.profiler.hotspot.HotSpotSupportedWorkloadMain",
```

The recording belongs to the actual client JVM, not the Gradle worker or Go
collector. It is written to the existing writable owned output mount. JFR adds
work/openat events, so this mode is diagnosis, not an optimization benchmark.

- [ ] **Step 4: Check and preserve the actual recording after normal command exit**.
  After the existing evidence/manifest copies into destination, insert:

```kotlin
if (readoutJfr) {
    assertTrue(Files.size(readoutRecording) > 0L, "Client JVM produced no JFR recording")
    var vmInformationSeen = false
    var executionSampleSeen = false
    RecordingFile(readoutRecording).use { recording ->
        while (recording.hasMoreEvents()) {
            val eventName = recording.readEvent().eventType.name
            if (eventName == "jdk.JVMInformation") vmInformationSeen = true
            if (eventName == "jdk.ExecutionSample") executionSampleSeen = true
        }
    }
    assertTrue(vmInformationSeen, "No actual JVM identity event in recording")
    assertTrue(executionSampleSeen, "No CPU samples; recording cannot diagnose readout")
    Files.copy(readoutRecording, destination.resolve("readout.jfr"))
}
```

The existing success-or-rejection/physical ownership checks and finally cleanup
stay in place. Missing/unreadable recordings fail the diagnostic; no empty profile
or canceled client is accepted as successful evidence. A successful case still
requires all original markers; a rejected600case stays rejected.

- [ ] **Step 5: GREEN, focused format and scoped commit**:

```bash
./gradlew :profiler:ktlintIntegrationTestSourceSetCheck
DOCKER_HOST=unix:///var/run/docker.sock MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock MW_HOTSPOT_NATIVE_ACCEPTANCE=true MW_HOTSPOT_ASSET_TEST_DIR="$PWD/profiler/hotspot-agentless/build/assets" ./gradlew :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest.profiled dense interpreter diagnostic*' --rerun
git diff --check
git add profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotPublicIntegrationTest.kt
git commit -m "test(profiler): retain actual dense consumer JFR diagnostics"
```

Require one actual selected test, zero skips/failures, real JFR identity/CPU data
and original cleanup checks. The full hook is mandatory; retain its actual output.
Do not call a green diagnostic accepted600throughput. Preserve all prior artifacts
before Gradle overwrites its usual XML output.

### Task 2: Run paired live diagnostics and publish a bounded next-change decision

**Files:**
- Create: `docs/plans/2026-10-17-hotspot-consumer-readout-evidence.md`.
- Modify: `docs/internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md`, `profiler/HOTSPOT_AGENTLESS.md`.
- Generated evidence only: `profiler/build/reports/hotspot-consumer-profile/`.

**Interfaces:**
- Consumes Task1 fixture, unchanged unprofiled dense fixture and their actual public outcome/marker/identity/retirement/JFR artifacts.
- Produces six owned runs in control0/profile0/profile1/control1/control2/profile2 order, actual sample/stall reports and a source-backed next-bottleneck decision. It does not produce a production optimization.

- [ ] **Step 1: Create a new evidence directory and input receipts**:

```bash
OUT="$PWD/profiler/build/reports/hotspot-consumer-profile"
test ! -e "$OUT"
mkdir -p "$OUT"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export DOCKER_HOST=unix:///var/run/docker.sock
export MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock
export MW_HOTSPOT_NATIVE_ACCEPTANCE=true
export MW_HOTSPOT_ASSET_TEST_DIR="$PWD/profiler/hotspot-agentless/build/assets"
git rev-parse HEAD > "$OUT/revision.txt"
"$JAVA_HOME/bin/java" -version > "$OUT/analysis-jdk.txt" 2>&1
(cd profiler/hotspot-agentless && go run ./cmd/mw-collector-tools verify --bundle build/assets) > "$OUT/assets-verify.log" 2>&1
sha256sum profiler/hotspot-agentless/build/assets/manifest.json profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotPublicIntegrationTest.kt profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotSupportedWorkloadMain.kt profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotCollectorWorkers.kt profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotSessionInterpreter.kt > "$OUT/inputs.sha256"
docker --host unix:///var/run/docker.sock image inspect eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6 > "$OUT/pinned-jdk-image.json"
python3 - "$OUT" <<'PY'
import hashlib, json, sys
from pathlib import Path
out = Path(sys.argv[1])
directories = [Path("profiler/build/classes/kotlin/main"),
               Path("profiler/build/classes/kotlin/integrationTest"),
               Path("profiler/build/install/tier-e-daemon/lib")]
assert all(p.is_dir() for p in directories)
paths = sorted({p.resolve() for directory in directories for p in directory.rglob("*") if p.is_file()})
assert paths
receipt = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
with (out / "classpath-before.json").open("x") as stream:
    json.dump(receipt, stream, indent=2)
    stream.write("\n")
PY
```

Record exact commands/environment selection alongside these receipts. Preserve
the old Plan02 baseline; this new paired experiment uses current same-code inputs.

- [ ] **Step 2: Execute the six exact selected cases and retain outputs immediately**:

```bash
OUT="$PWD/profiler/build/reports/hotspot-consumer-profile"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export DOCKER_HOST=unix:///var/run/docker.sock
export MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock
export MW_HOTSPOT_NATIVE_ACCEPTANCE=true
export MW_HOTSPOT_ASSET_TEST_DIR="$PWD/profiler/hotspot-agentless/build/assets"
for label in control-0 profile-0 profile-1 control-1 control-2 profile-2; do
  python3 - "$OUT/$label-before.json" <<'PY'
import json, sys
from pathlib import Path
paths = sorted(str(p) for p in Path("profiler/build/reports/hotspot-acceptance").glob("hs-current-*-dense") if p.is_dir())
Path(sys.argv[1]).write_text(json.dumps(paths) + "\n")
PY
  case "$label" in
    control-*) TEST='*HotSpotPublicIntegrationTest.dense interpreter diagnostic retains checked success or rejection*';;
    profile-*) TEST='*HotSpotPublicIntegrationTest.profiled dense interpreter diagnostic*';;
  esac
  status=0
  ./gradlew :profiler:integrationTest --tests "$TEST" --rerun > "$OUT/$label.log" 2>&1 || status=$?
  python3 - "$OUT" "$label" "$status" <<'PY' || exit
import json, shutil, sys
from pathlib import Path
root, label, status = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
before = set(json.loads((root / (label + "-before.json")).read_text()))
after = {str(p) for p in Path("profiler/build/reports/hotspot-acceptance").glob("hs-current-*-dense") if p.is_dir()}
out = root / label
out.mkdir(exist_ok=False)
for n, path in enumerate(sorted(after - before)):
    shutil.copytree(path, out / ("native-" + str(n)))
shutil.copytree("profiler/build/test-results/integrationTest", out / "test-results")
(out / "invocation.json").write_text(json.dumps({"label": label, "gradle_exit": status, "new_reports": sorted(after - before)}) + "\n")
assert status == 0, "diagnostic failed; retained available reports/XML/logs"
assert len(after - before) == 1, "missing or ambiguous fresh report; no result inferred"
PY
  test "$status" -eq 0 || exit "$status"
done
```

Inspect retained XML after every case: exactly the selected native test actually
ran, zero skips/failures. A fixture/ownership/capability failure stops the experiment
with its available artifacts, not a timer/queue/budget relaxation. Store the actual
client JVM identity/arguments from each profile, not the analysis JDK identity.

- [ ] **Step 3: Export JFR CPU/allocation/monitor/park events**:

```bash
OUT="$PWD/profiler/build/reports/hotspot-consumer-profile"
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
for label in profile-0 profile-1 profile-2; do
  JFR="$OUT/$label/native-0/readout.jfr"
  "$JAVA_HOME/bin/jfr" summary "$JFR" > "$OUT/$label/jfr-summary.txt"
  "$JAVA_HOME/bin/jfr" print --events jdk.JVMInformation "$JFR" > "$OUT/$label/client-jvm.txt"
  "$JAVA_HOME/bin/jfr" print --json --stack-depth 128 --events jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.JavaMonitorEnter,jdk.ThreadPark,jdk.DataLoss "$JFR" > "$OUT/$label/events.json"
done
```

Run this complete CPU-stack classification from checkout root:

```python
import collections, json
from pathlib import Path
root = Path("profiler/build/reports/hotspot-consumer-profile")
groups = {
    "reader": "io.mazewall.profiler.internal.hotspot.HotSpotWireReader",
    "mapper": "io.mazewall.profiler.internal.hotspot.HotSpotWireMapper",
    "assembler": "io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler",
    "interpreter": "io.mazewall.profiler.internal.hotspot.HotSpotSessionInterpreter",
    "render": "io.mazewall.profiler.internal.hotspot.HotSpotEvidenceDocument",
}
for label in ("profile-0", "profile-1", "profile-2"):
    data = json.loads((root / label / "events.json").read_text())
    event_counts, matched, methods = collections.Counter(), collections.Counter(), collections.Counter()
    missing_stack = 0
    for event in data["recording"]["events"]:
        event_counts[event["type"]] += 1
        if event["type"] != "jdk.ExecutionSample":
            continue
        frames = (event["values"].get("stackTrace") or {}).get("frames", [])
        if not frames: missing_stack += 1
        names = []
        for frame in frames:
            method = frame.get("method") or {}
            owner = (method.get("type") or {}).get("name", "").replace("/", ".")
            names.append(owner + "." + method.get("name", ""))
        methods.update(set(names))
        for group, prefix in groups.items():
            if any(name.startswith(prefix) for name in names): matched[group] += 1
    result = {"label": label, "event_counts": dict(event_counts), "cpu_stack_categories": dict(matched),
              "missing_cpu_stacks": missing_stack, "top_methods_by_sample_presence": methods.most_common(30)}
    (root / label / "analysis.json").write_text(json.dumps(result, indent=2) + "\n")
```

Categories overlap and are not additive CPU percentages. Allocation events are
weighted samples, not exact retained bytes; monitor/park durations must be read
from actual events/thread identities and source, not counted as CPU work. Missing
or zero samples, DataLoss, wrapped recording windows and truncated stacks leave
uncertainty. The existing fixture has scalar phase durations, not absolute JFR
phase markers: do not attribute all category samples to post-stop work by subtracting
unrelated clocks. Inspect stdout worker/timer/owner stacks and
the actual record/event/snapshot/summary call paths before assigning a bottleneck.

- [ ] **Step 4: Record actual outcomes and a concrete next-step decision**:

For each case, read measurement.json and evidence.json, independently count unique
`/current-N-[AB]` tokens against600expected, check route order/PID-birth/session,
terminal pairs, pending occurrences and actual physical ownership/retirement. Do
not infer600success from a zero test-process exit. Compare control/profile outcomes
as instrumentation sensitivity; JFR overhead prevents an optimization-speedup claim.

The evidence table must include these exact columns:

|Case|Actual client VM/args|Public accepted/rejected|Expected/paired marked|Received/paired/pending|Physical ownership|Readout/finish timing scope|Named JFR source evidence|
|---|---|---|---|---|---|---|---|

Populate only actual rows and retain file hashes. The decision ends in exactly one
of: `insufficient_samples`, `consumer_cost_identified_requires_followup`, or
`completion_observed_not_promoted`. For identified cost, name the exact source
function/lock/serialization stage, event/sample evidence, unchanged-validation/
ownership regression required and smallest proposed next change. This task does
not invent that fix before measuring it. Keep issue open for rejected cases and
preserve global collection/policy refusal even on observed completion.

- [ ] **Step 5: Validate documentation and commit only the evidence/public guidance**:

```bash
OUT="$PWD/profiler/build/reports/hotspot-consumer-profile"
sha256sum -c "$OUT/inputs.sha256"
python3 - "$OUT" <<'PY'
import hashlib, json, sys
from pathlib import Path
out = Path(sys.argv[1])
for name, digest in json.loads((out / "classpath-before.json").read_text()).items():
    assert hashlib.sha256(Path(name).read_bytes()).hexdigest() == digest, name
files = sorted(p for p in out.rglob("*") if p.is_file() and p.name != "evidence.sha256")
with (out / "evidence.sha256").open("x") as stream:
    for path in files:
        stream.write(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + str(path.resolve()) + "\n")
PY
./scripts/adkw check-backlog
git diff --check
git add docs/plans/2026-10-17-hotspot-consumer-readout-evidence.md docs/internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md profiler/HOTSPOT_AGENTLESS.md
git commit -m "docs(profiler): record actual consumer readout profile and limits"
```

Check local and Git durability of requirement references before publishing. The
mandatory hook supplies the full gate; reuse valid unchanged source tests. Preserve
all raw accepted/rejected/JFR/receipt data and source-after hashes for continuation.
