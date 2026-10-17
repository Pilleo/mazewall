# Explicit HotSpot agentless profiler

The public strategy `HOTSPOT_AGENTLESS` supervises the installed external
Gadget/OTel collector. Initial admission is Linux-amd64, exact pinned Temurin25
interpreter (`-Xint`), SerialGC and process-wide synchronous openat handler entry.
Attaching cannot change JVM startup settings. A normal running JIT JVM may be
refused before any workload code runs. AUTO continues selecting USER_NOTIF.

Build/install the [verified collector assets](hotspot-agentless/README.md)
explicitly. Library startup performs no build/download or automatic sudo. The
operator-configured argv prefix must preserve parent stdin/control/EOF and wait
for the actual collector. Parent JVM privilege probes do not admit/refuse an
independently authorized helper. Sessions own their collectors independently:
closing the selector prevents new work; finish/close every returned session.

## Versioned offline distribution

The current pairing is `io.mazewall:profiler:0.1.0-SNAPSHOT`, collector version **2**,
capture ABI **2**, wire schema **2**, Linux-amd64. This is a local development
distribution, not a remotely published release. Exact dependency/JDK/builder pins
are in `hotspot-agentless/dependencies.lock.json`; the accepted JDK is
Temurin **25.0.4.1+1-LTS**, started with `-Xint -XX:+UseSerialGC`. Acceptance was on
kernel `7.0.0-34-generic`; this does not certify all Linux kernel versions.

Build native inputs only when explicitly requested:

```bash
./gradlew :profiler:buildHotSpotAssets
./gradlew :profiler:verifyHotSpotAssets :profiler:distHotSpotAssets
./gradlew :profiler:installDist
```

The optional native archive is
`profiler/hotspot-agentless/build/distributions/mazewall-hotspot-0.1.0-SNAPSHOT-linux-amd64.tar.gz`,
with an adjacent `.sha256`. It contains `assets/`, per-file `SHA256SUMS`,
`provenance.json`, pinned inputs, verbatim third-party notices and
`native-source.tar.gz` (canonical collector/overlays and both actual patched
upstream source trees, including BPF source). MazeWall's README declares Apache-2.0;
upstream userspace notices and GPL-2.0 BPF licenses are included separately under
`licenses/`. Preserve these notices and corresponding source with redistributed
native artifacts. The inventory covers the actual collector's linked Go modules;
it is not a license inventory for separately supplied JVM dependencies.

The archive's `assets/manifest.json` hashes the helper and all reachable local OCI
blobs. Verify the trusted archive checksum, extract, then run `sha256sum -c SHA256SUMS`
inside its root. Configure `HotSpotOptions` with the absolute extracted `assets/`
directory, or install the existing verified local bundle into a **new** directory:

```bash
./gradlew :profiler:installHotSpotAssets -PhotSpotAssetsDirectory=/absolute/new/assets
```

Checksum manifests detect corruption/version mismatch; they are trusted operator
input, not signatures. Do not replace an existing installation in place. Packaging
requires prepared native source/cache and Go; library use does not. Normal JVM
builds do not depend on these native tasks and collector startup never downloads,
queries a registry, compiles code or escalates privilege.

### Installed runnable example

`installDist` includes the compiled tooling example and library dependencies under
`profiler/build/install/tier-e-daemon/lib/`. Copy this `lib/` directory to a fresh
fixture directory alongside the extracted native archive and a `prefix.json` file.
The file contains a JSON array of literal argv strings, for example `[]` when the
operator has already authorized direct collector execution. Run in the exact pinned
JVM, as the ordinary-user target, with its host PID visible to the helper:

```bash
java -Xint -XX:+UseSerialGC -cp '/absolute/fixture/lib/*' \
  io.mazewall.profiler.hotspot.example.HotSpotInstalledExample \
  /absolute/fixture/release/assets /absolute/fixture/output \
  /absolute/fixture/prefix.json
```

The example runs two Java call paths, returns `workload-value`, writes immutable
`evidence.json`, and requires useful example frames plus checked physical drain.
It performs 40 warmups and 20 marked opens paced at 100 ms. Provision Linux BPF/
perf/process-memory authorization for the **actual helper**, including LSM/Yama,
host `/proc` visibility, memlock requirements and local OCI assets. The accepted
test setup uses an explicitly operator-authorized privileged rootful container with
host PID namespace, read-only `/sys` and an identical absolute asset mount; the
ordinary target remains UID1000. Privileged-container/rootful-Docker access itself
grants broad host authority. No generic minimal-capability deployment is certified.
Keep wrapper stdin piped through to the helper and wait for its actual exit; a
detached wrapper cannot establish retirement. An authorized argv prefix belongs in
`prefix.json`; the library does not construct one or run automatic sudo.

Current-interpreter asset/admission work measured 34–82 seconds before workload
startup. Dense 600-open/1ms self-profiling experiments failed within the unchanged
stop bound; the paced example does not establish a high-rate self-profiling guarantee.
Fresh phase diagnosis measured interpreter checksum times of59–85 seconds on a
95.9MB bundle; hashing the collector dominates. Normal-JIT same-stage measurements
are offline controls, not supported live admission. The dense public diagnostic
still rejected with268 received records and retired its owned resources. See
[phase evidence](../docs/plans/2026-10-05-hotspot-interpreter-performance-evidence.md).
Release builds now strip collector ELF debug/symbol sections while keeping build
metadata, native BPF data and full asset hashing. The verified helper shrank from
95.73MB to68.23MB; matched checksum medians fell70.94s to44.97s in three pairs.
Live candidate admission still took69.8–78.3s and dense readout still rejected;
these observations establish no end-to-end bound. See
[footprint comparison](../docs/plans/2026-10-05-hotspot-release-footprint-evidence.md).
Native readiness means attachment/readers are active, not that cold method metadata
or every stack is available. General JIT, alternate GCs, virtual-thread logical
ancestry and complete loss accounting remain unsupported.

The optimized Kotlin reader defers canonical diagnostics and reuses a strict UTF8
decoder, with unchanged field/identity/refusal rules. Actual dense600case still
rejects; moved serialization cost is measured separately. Pinned
JIT/GC, virtual-thread identity and loss accounting currently have experimental
qualification tools, not broader production admission. See the
[readout evidence](../docs/plans/2026-10-05-hotspot-dense-readout-evidence.md) and
[workstream design](../docs/plans/2026-10-05-hotspot-performance-capability-design.md).

Fresh2026-10-17public acceptance still rejects dense600/1ms:
275received/265paired/10pending,225/600marked tokens in paired public evidence,
with no pending physical owner and checked helper/client absence. Passing the7-test
matrix, including its dense success-or-rejection diagnostic, does not promote
that workload. A separate retained
original native stream contains all600marked tokens and final retirement
observations, yet its public consumer also times out; native completion and
complete public readout are separate requirements. No new reader/buffer change
was justified by actual JFR, so that work is deferred. Fresh same-code alternating
replay timings show no optimization gain; they include canonical render/hash and
measurement-field construction, excluding process startup and outer report
serialization/file I/O. See the
[fresh matched evidence and decision](../docs/plans/2026-10-17-hotspot-dense-next-evidence.md).

The uncalled kernel-SHA256 qualification prototype is retained only for historical
replay at `96789e51`; it was never a production hash provider. Recover its exact
`profiler/hotspot-agentless/scripts/kernel-digest.py` and paired self-test with
`git show 96789e51:<path>`. Normal asset admission retains full byte hashing.

## Attach a supported existing JVM

```kotlin
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.*
import java.nio.file.Path

MazewallProfiler.open(
    ProfileOptions(
        strategy = ProfileStrategy.HOTSPOT_AGENTLESS,
        processWide = true,
        captureStacks = true,
    ),
).use { profiler ->
    val options = HotSpotOptions(assetsDirectory = Path.of("/absolute/verified/assets"))
    profiler.attach(Pid(targetHostPid), options).use { session ->
        val partial = session.snapshot()
        // Run the target's operation window; keep the target alive through finalization.
        val final = session.finish()
        val evidence = requireNotNull(final.structuredEvidence)
        println(evidence.records.map { it.evidence.frames.map { frame -> frame.functionName } })
        println(evidence.summary.unavailable)
    }
}
```

`HotSpotOptions.collectorArgvPrefix` is an immutable operator argv vector, not a
shell command string. Defaults bound readiness to 10 seconds, session lifetime
to 60 seconds, finish to at most two remaining seconds, and Kotlin retained wire
payload to 4096 occurrences/32 MiB. Strict runtime admission cannot be disabled.
`processWide=true`, `captureStacks=true` and no recorded-log/io_uring override
are required. Missing/tampered/stale assets and unsupported options fail explicitly.

## Current-JVM workload window

```kotlin
val settings = ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true)
val helper = HotSpotOptions(Path.of("/absolute/verified/assets"))
MazewallProfiler.open(settings, helper).use { profiler ->
    val result = profiler.profile { supportedWorkload() }
    println(result.value)
    println(result.structuredEvidence?.summary?.received)
}
```

Alternatively use `profiler.profile(helper) { ... }`. Attachment and validated
native ready precede the block. The observation scope includes background threads,
not just the lambda or a logical virtual-thread/asynchronous-parent task. Workload
and cleanup failures are both preserved through ordinary use/close suppression.

## Evidence, failure and policy eligibility

Typed evidence preserves occurrence identity, PID birth generation, BOOTTIME,
ordered immutable frames, optional BCI/source/native positions, uint64 provider
positions, unavailable/skipped outcomes, raw diagnostics and raw openat arguments.
Returned filename bytes are defensive copies. Relative/truncated paths are not
resolved Landlock operands, and handler entry is not a successful-operation result.
Snapshots are immutable and partial; pending pairs and retention loss are distinct.
Native physical drain, parent resource completion and whole-session collection
completeness are separate facts. Diagnostics are retained separately from JSONL,
with explicit discarded byte counts.

`HotSpotProfilingException` retains its original cause, optional partial result,
native errno observations, diagnostics and pending cleanup ownership. Explicit
`retryCleanup()` retries retained resources; it does not make failed collection
successful. Do not let a target exit/exec during metadata/enrichment drain.

Coverage is always incomplete in this version. Policy/DSL/dedicated-worker
conversion rejects it even after successful physical finalization. Result copies
preserve the typed evidence owner and cannot promote it using another coverage
record. Existing constructors and copy JVM descriptors remain intact.

`toProfileDocumentJson()` retains its existing behavior/coverage-only contract and
roundtrips the new strategy name. Save the independent diagnostic report with
`result.structuredEvidence!!.toJson()` and replay it with
`StructuredProfileEvidence.fromJson(json)`. That bounded report preserves frame
domains/optional values/raw fields and is not a policy or native-resource
attestation. Collection loss completeness remains false after deserialization.
