# Explicit HotSpot agentless profiler

**Latest native checkpoint:** The scoped ordinary-JVM public fixture passed on the
selected host after CAP_SYSLOG was added to its private collector grant:400marked
operations,380useful correct routes and176marked compiled application events.
One test executed with0skips/failures/errors; target/helper cleanup and capability
removal were verified. This remains observational: collection-loss/global coverage
and policy eligibility are false. Independent outcome review and Kubescape
persisted-profile acceptance remain separate. See the dated result linked below;
earlier rejection/checkpoint paragraphs retain their chronology.

For the longer-term SBOB goal, see the
[coverage and performance research note](../docs/internals/research/hotspot-sbob-coverage-and-performance.md).
It separates measured attribution from future optimization candidates and does
not change runtime admission or the active implementation plans.

The public strategy `HOTSPOT_AGENTLESS` supervises the installed external
Gadget/OTel collector. Initial admission is Linux-amd64, exact pinned Temurin25
interpreter (`-Xint`), SerialGC and process-wide synchronous openat handler entry.
Attaching cannot change JVM startup settings. A normal running JIT JVM may be
refused before any workload code runs. AUTO continues selecting USER_NOTIF.

For ordinary tiered/G1 JVMs, use the separate explicit
`MazewallProfiler.observeHotSpot(target, options)` entry point below. It does not
change the strict strategy or silently fall back between profiles.

Build/install the [verified collector assets](hotspot-agentless/README.md)
explicitly. Library startup performs no build/download or automatic sudo. The
operator-configured argv prefix must preserve parent stdin/control/EOF and wait
for the actual collector. Parent JVM privilege probes do not admit/refuse an
independently authorized helper. Sessions own their collectors independently:
closing the selector prevents new work; finish/close every returned session.

## Ordinary-JVM observations

`observeHotSpot` selects collector version3/wire schema3, capture ABI2 and
`otel_hotspot25_observed_v1`. Initial scope remains Linux-amd64 and pinned
Temurin25.0.4.1+1-LTS with tiered compilation/G1; it does not require `-Xint`, a Java
agent or a change to the target JVM's startup settings. Supply the verified
**observed** bundle, not the strict2 bundle described below.

The caller must independently discover the host PID, raw process-birth ticks,
cgroupv2 ID and mount-namespace inode. Never obtain the expected identity from the
collector being admitted, convert ticks to wall time, or guess container-relative
identities. MazeWall checks birth before acquiring a collector; the helper checks
the full supplied tuple and pinned runtime before ready.

```kotlin
val target = HotSpotObservationTarget(Pid(hostPid), startTicks, cgroupId, mountNamespace)
MazewallProfiler.observeHotSpot(target, HotSpotOptions(observedAssets, collectorArgvPrefix)).use { session ->
    // Run the workload only after observeHotSpot returns ready; keep it alive through finish.
    val result = session.finish()
    val evidence = requireNotNull(result.structuredEvidence)
    check(evidence.runtimeProfile == "otel_hotspot25_observed_v1")
    check(!evidence.policyEligibility && !result.coverage.complete)
}
```

Types are in `io.mazewall.profiler.api`; `Pid` is in
`io.mazewall.platform.lifecycle`. The example assumes independently acquired tuple
values and an operator-authorized argv prefix. Library startup never grants those
permissions or discovers a container on the caller's behalf.

Frames retain provider order, source/BCI presence and unsigned positions. Missing
or evicted JIT metadata remains unavailable; OTel generation checks do not prove
metadata lifetime, class-loader identity or logical/virtual-thread ownership.
Every schema3 row must explicitly deny policy eligibility. Physical drain and
selected-stream accounting do not establish whole-collection completeness;
`toPolicy`/`toDedicatedWorkerPolicies` cannot promote these observations.

The Kotlin path has synthetic protocol/process tests, not a new native acceptance
claim. Actual ordinary-JVM public-API qualification and Kubescape persisted-profile
acceptance remain separate from those host checks and from strict2 qualification.

The opt-in `HotSpotObservedIntegrationTest` is compiled with the host tests but
disabled unless `MW_HOTSPOT_OBSERVED_ACCEPTANCE=true`. It requires separately
authorized direct collector execution, `MW_HOTSPOT_OBSERVED_JAVA` selecting the
pinned JVM executable and `MW_HOTSPOT_ASSET_TEST_DIR` selecting verified observed
assets. It reuses the400-operation ordinary-JVM workload without Docker or sudo.
Its test-only identity discovery requires host `/proc` and the target's cgroupv2
membership to be visible at `/sys/fs/cgroup`; no identity is guessed on mismatch.
Native selection disables Gradle test-result caching/reuse. Scratch is retained,
and a pending collector prevents target release. The first authorized
[2026-10-23 native attempt](../docs/internals/reviews/2026-10-23-hotspot-public-native-attempt.md)
**failed before collector readiness** with `mapped JVM file identity changed`.
Gradle exited1; the target exited0 with no pending ownership reported. Docker
helpers only set/removed the private collector's capabilities; the test stayed
host-side/non-root. This is not qualification or authority to retry.

The source correction now compares the opened libjvm's device/inode with the
kernel `map_files` entry for the selected VMA, retaining the maps-inode, full
digest and build-ID checks. This avoids Btrfs's different maps/stat device
representations without accepting replaced files. Missing or denied map_files
access fails closed; the existing qualification set's CAP_SYS_ADMIN covers
that access. Corrected source/unit tests do not update the retained old bundle
or turn the rejected native attempt into qualification.

A separately selected corrected build and second native attempt passed identity
discovery but again **failed before Ready**: pinned Gadget local.Runtime.Init
requires UID0 even with the selected file capabilities. Gradle1/XML1failure;
target1823767 exited0 with no pending ownership, helpers removed and capabilities
cleared. No kernel permission errno was observed. Both grants are consumed;
qualification remains incomplete. A capability-aware initialization correction
preserving real preflight/host checks and a fresh attempt require selection;
running the collector as root is a separate scope, never a silent fallback.

The selected source correction now initializes the same Gadget host state with
automount disabled after real preflight/target discovery, then constructs the
local runtime without its UID-only wrapper. Host checks and original errors remain
fail-closed; no credentials are granted or kernel denials ignored. Regression and
fresh native verification are tracked separately in the linked outcome review.

Focused regression/race/vet and the fresh pinned build/bundle checks pass, but
the completion gate failed twice in the separate USER_NOTIF cold-network test
(issue752). Attempt3 remains unused; no native helper/target started. The proposed
direct-loopback fixture correction is not selected, and no gate is waived.

The subsequent selected direct-loopback correction passed all15owning integration
cases and the configured full build (228tasks). Attempt3 then passed identity and
host initialization but **failed before Ready** because OTel could not read kernel
symbol addresses. Collector1/Gradle1/XML1failure; target1886276 exited0/no pending
ownership, helpers removed and capabilities cleared. No numeric native errno was
emitted. Passive inspection shows kptr_restrict1; Linux requires CAP_SYSLOG for
kernel-symbol visibility at that setting, and the current grant lacks it. Adding
CAP_SYSLOG is a new kernel-log/address access/control privilege requiring explicit
selection, not a silent fallback. All three native grants are consumed; no attempt4
or sysctl change has occurred. See the linked current outcome and issue761.

The test-only retention boundary saves original `/proc/<pid>/maps` bytes as
`target-maps-before` and `target-maps-after`, bounded to16MiB each. Both reads are
bracketed by equality with the independently discovered PID/birth/cgroup/mount
tuple; read denial, empty/oversized maps or identity changes reject the attempt.
All retention writes use exclusive creation, never replace an earlier attempt,
and cannot bypass collector close, pending-target hold or target-retirement checks.
The400marked operations, four workers and20ms pacing remain unchanged.

After the existing400-token/route validation, acceptance also requires at least
one marked event with an application `ObservedWorkload.leaf`, `routeA` or `routeB`
frame carrying actual `scope_lookup=exact_pc|preceding_pc` and
`scope_walk=returned`, with no error frame in that event. Interpreter, unknown,
stub, method-only and partial walks do not supply this compiled-frame proof;
enabled compilation or absent `-Xint` is not proof. `compiled-marked-event-ids.txt`
lists the exact event IDs; `compiled-marked-routes.tsv` gives marked/compiled-event
counts per route, and `compiled-marked-frames.jsonl` retains all frames' names and
quality fields for those sampled events. This minimum proves **some sampled
compiled application frames**, not every useful route, a generation lease,
universal JIT correctness or policy admission. No qualifying event means rejection.

`collector-{before,after}-close.stderr` and their separate `-discarded-bytes.txt`
files retain bounded public diagnostics before and after close, including rejected
attempts with a returned session. Failed attachment exposes only the exception's
diagnostic snapshot, not a before/after-close session pair.
`pending-ownership-after-close.txt`, `target-retirement.txt` and
`attempt-outcome.txt` distinguish actual cleanup ownership, target exit/hold and
acceptance. A generic original failure has **unknown** ownership, not false;
that is separate from a later session cleanup observation. `failure.txt` preserves
the thrown failure tree, and `root-failure-pending-ownership.txt` keeps the thrown
root's ownership unknown for a generic wrapper or promoted `Error`. Actual
reachable `HotSpotProfilingException` values separately
retain `collector-failure.stderr`, `collector-failure-discarded-bytes.txt`,
`native-errnos.json`, `pending-ownership.txt` and optional `partial-evidence.json`.
The test-only cause/suppressed traversal visits at most64throwable identities,
handles cycles and promoted `Error` values, and uses numbered suffixes for further
distinct native failures. No reached typed owner means unknown ownership; the
traversal cannot attest unseen owners beyond its bound. Serialization or write
failures join the first failure through the existing cleanup ordering.

`evidence.json` and partial evidence are bounded public reports preserving original
per-record/summary JSON and diagnostics, **not a complete standalone collector
stdout transcript**. Any independently required full-transport capture needs a
separately reviewed retention arrangement before native execution, not a second
collector window or a hidden public hook. Offline host regressions use replay data
and existing synthetic children only. They do not execute native acceptance or
discharge the separate explicit
[profiler test Detekt gate](../docs/internals/backlog/resolved/issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin.md),
which has now passed and is resolved.

## Versioned offline distribution

The current pairing is `io.mazewall:profiler:0.1.0-SNAPSHOT`, collector version **2**,
capture ABI **2**, wire schema **2**, Linux-amd64. This is a local development
distribution, not a remotely published release. Exact dependency/JDK/builder pins
are in `hotspot-agentless/dependencies.lock.json`; the accepted JDK is
Temurin **25.0.4.1+1-LTS**, started with `-Xint -XX:+UseSerialGC`. Acceptance was on
kernel `7.0.0-34-generic`; this does not certify all Linux kernel versions.

Build native inputs only when explicitly opted in via `-PincludeHotSpotGoBuild=true`
(or `INCLUDE_HOTSPOT_GO_BUILD=true`):

```bash
./gradlew :profiler:buildHotSpotAssets -PincludeHotSpotGoBuild=true
./gradlew :profiler:verifyHotSpotAssets :profiler:distHotSpotAssets -PincludeHotSpotGoBuild=true
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
./gradlew :profiler:installHotSpotAssets -PincludeHotSpotGoBuild=true -PhotSpotAssetsDirectory=/absolute/new/assets
```

Checksum manifests detect corruption/version mismatch; they are trusted operator
input, not signatures. Do not replace an existing installation in place. Packaging
requires prepared native source/cache and Go; library use does not. Normal JVM
builds do not register or depend on these native tasks unless opted in, CI Go
validation is likewise opt-in via the repository variable `ENABLE_GO_VALIDATION=true`
or manual `workflow_dispatch` input `enable_go_validation=true`, and collector
startup never downloads, queries a registry, compiles code or escalates privilege.

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

The pinned upstream already captures `nmethod._compile_id` and checks it during
JIT cache lookup/symbolization. The shared candidate audit reports those reviewed
source checks; it does not prove loaded-object identity or native code/metadata
validity through decode or exclude concurrent reclamation. Compiled/concurrent-GC
production qualification remains false, with the interpreted/Serial profile above
unchanged. The actual diagnostic retained a four-carrier identity refusal; a later
fully receipted six-case run passing the experimental validators does not erase
that failure or qualify runtime lifetime. See the
[generation evidence](../docs/plans/2026-10-17-hotspot-upstream-generation-evidence.md)
and [native mechanism decision](../docs/plans/2026-10-17-hotspot-jitgc-native-mechanism-decision.md).

Known limit: the retained failure has a nine-event/two-TID correlation alias;
unchanged actual C also fails distinct-key uniqueness under equal clocks. The
historical trigger remains unproved. The approved bounded shared allocator is
implemented with actual-C and isolated multi-CPU kernel checks. The corrected
`8212ee25…` graph now has two byte-identical locked helper/full-OCI builds and eight
actually executed public-native cases (zero skips). The paced case accepts;
both dense600 diagnostics still reject with retained partial evidence. Exact task
binding, selected accounting/drain, zero-retention conservation and non-time
rendered/pdata compatibility pass within the existing profile. An experimental
four-carrier control observes no cross-task reference alias, without qualifying
logical ancestry, JIT/GC/Loom lifetime, active perf/NMI or all publication/eviction
interleavings. Task3's complete-production-path native kprobe fixture closes the
finite supported-context cache/definition/eviction/drain gate:376calls,366exact
definitions,0reported loss, actual eviction/republish and checked all-owner retirement.
Synthetic entry seeds complement the existing real JVM captures; the allocator
issue is resolved within that scope. Its raw positive
reference is not Kubescape's frame-content CallID; the existing Tier E importer
does not consume this agentless schema-2 stream. See
[allocator evidence](../docs/plans/2026-10-18-hotspot-correlation-allocator-evidence.md)
and the preserved [identity evidence](../docs/plans/2026-10-18-hotspot-correlation-identity-evidence.md).

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

The2026-10-17 alternating public consumer diagnostics (recovered2026-10-18)
retain6 actual rejected collections:411/230/548/311/232/273 paired marked tokens
of600, despite green success-or-rejection tests and checked resource retirement.
All six partial summaries still lack complete physical drain/terminal accounting.
Actual pinned client JFR identifies JSON parsing and eager public diagnostic/report
serialization cost; the decision is `consumer_cost_identified_requires_followup`.
It does not establish a sole deadline cause, optimization gain or600 acceptance.
Whole-client stack categories overlap, scalar fixture durations cannot locate a
post-stop interval, and JFR itself adds work/openat events. The proposed follow-up
was deferred public rawJson materialization with unchanged validation/ABI/ownership
regressions. See
[actual consumer evidence](../docs/plans/2026-10-17-hotspot-consumer-readout-evidence.md).

The2026-10-18follow-up now delegates public stack/frame `rawJson` getters to their
existing immutable owners. Typed construction avoids unused frame/stack rendering;
`toJson()` still renders aggregate diagnostics but does not force individual frame
strings. Event argument parsing stays eager; requested raw getters preserve exact
canonical bytes and all public descriptors. Full-public replay (including output
I/O) retains identical bytes/counts, but timing variation and deferred render cost
do not establish a general end-to-end gain. The fresh dense600/1ms collection still
**rejects**278received/277paired/1pending,237/600marked pairs, with checked resource
retirement. All current bounds and admission profiles remain unchanged. See
[public raw diagnostic evidence](../docs/plans/2026-10-18-hotspot-public-raw-json-evidence.md).

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

Full owned captures may include `selected_event_accounting` in
`evidence.summary.rawJson`. Its `complete=true` proves scoped selected openat
conservation using the acquired session/PID/birth/map/CPU binding and final native
owner; it does not mean zero loss or complete collection. The optional
`unproven_collection_sources` array currently contains exactly `definitions`,
`enrichment`, `wire`, `retention` when selected proof succeeds. Missing or invalid
selected proof also leaves `selected_events` unproven. Definition/auxiliary reader
joins, zero loss metrics and terminal counts cannot prove those independent source
denominators. Early partial snapshots may omit the diagnostic; omission is not
completeness. Even retaining zero client records can preserve selected accounting
while `retention` remains unproven. See the
[production inventory evidence](../docs/plans/2026-10-17-hotspot-production-loss-evidence.md).

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

## Standalone ordinary-JVM observation example

`HotSpotObservedExample` observes an **external, already running ordinary JVM**;
the observer JVM is not the target. It does not launch, reconfigure, install policy
in, or terminate that target. Keep the target alive through both `finish()` and
session close. Do not add `-Xint` or SerialGC to an ordinary observation target.
The strict installed example above remains a separate strict-mode workflow.

Prepare and verify an **observed** asset bundle separately before invoking this
example. Its contract is collector3 / wire schema3 / capture ABI2,
`otel_hotspot25_observed_v1`, Linux-amd64, and the pinned
Temurin25.0.4.1+1-LTS tiered/G1 envelope, not generic JVM support certification.
Require an independently trusted archive checksum and selected source, library
and runtime identities; verify extracted per-file `SHA256SUMS`, provenance and
the observed manifest. Checksums are not signatures. Use a fresh installation,
not in-place asset replacement. The strict2 archive/build recipe and strict native
Gradle packaging tasks do **not** establish an observed3 bundle. This runbook
supplies no fetch/build/install/probe command or helper privilege launcher.

The operator must independently supply all four identities for the **same live
target in the helper's visible host identity domain**: host PID, raw
`/proc/<pid>/stat` birth ticks (not time in milliseconds), cgroupv2 identity and
mount namespace inode. No identity discovery, container mapping guess, collector
identity substitution or default is performed. PID is positive decimal Int;
the other identities are positive decimal uint64 values, without signs or spaces.
Admission independently verifies birth before acquisition and the full tuple and
runtime before Ready. Strict2, mismatched protocol and runtime assets fail rather
than silently downgrading.

The prefix file is a JSON array of literal nonempty strings (at most 64 entries).
Ordering and spaces inside each argv element are preserved: there is no shell
splitting, interpolation or implicit `sudo`. `[]` requests direct helper execution
that the operator has **already authorized**; it does not grant authority.
All three path arguments must be absolute and nonblank. Assets must already be a
directory, prefix an existing regular file, and output a **new** directory whose
parent exists. Existing output is rejected, never reused or replaced. All retained
files use `CREATE_NEW`.

After the target and verified assets have separately been prepared:

```bash
java -cp '/selected/fixture/lib/*' \
  io.mazewall.profiler.hotspot.example.HotSpotObservedExample \
  /verified/observed/assets /fresh/observation-output /selected/prefix.json \
  "$HOST_PID" "$BIRTH_TICKS" "$CGROUPV2_ID" "$MOUNT_NAMESPACE_INODE" 1000
```

`/selected/fixture/lib/` is a copy of the current `installDist` lib directory, not
a native asset bundle. This invocation confers no helper authority, target
identity, release acceptance or reusable grant. The eight arguments are exactly
assets, new output directory, prefix JSON file, PID, birth ticks, cgroupv2 ID,
mount namespace inode and window milliseconds. Window is decimal `1..30000`, an
example-local conservative bound, **not** an API limit. The example keeps the
API defaults: readiness 10s, session 60s, finish 2s, retained occurrences 4096,
retained bytes 33554432. It waits the requested window after admission and then
calls `finish()`; finish requests stop, rather than waiting out that window itself.

After successful validated admission and taking ownership of the returned session,
the example writes exactly `MAZEWALL_OBSERVATION_READY` plus a newline to stdout,
explicitly flushes it and checks for publication errors **before** entering the
window. Validation or admission failure emits no readiness line. The signal means
only **admitted and owned**: it proves neither coverage completion nor helper
authority, native acceptance or general platform support.

A separately authorized coordinator can blocking-read that exact line and then
release its existing workload gate. This adds no acknowledgment, gate-file
argument or target-control capability to the example. The coordinator must still
check the observer's final exit and retained `outcome.json`; readiness alone is
not a successful observation. Keep the target alive through final cleanup.

Broken stdout publication fails with an `IOException`, skipping the window and
`finish()` while retaining failure details and closing the owned session through
the existing cleanup path. Interruption is checked before and after publication
and restored after cleanup. A line already written cannot be retracted if its
flush fails or interruption follows it. Stdout is borrowed and is never closed
by the example.

Output files:

- `target-identity.json`: operator-supplied tuple, assets and requested window;
  unsigned identities remain decimal strings. This is **not** admission evidence.
- `evidence.json`: actual structured evidence, replayable with
  `StructuredProfileEvidence.fromJson`; failures retain partial evidence when
  available. Absence is explicit in the outcome, not an empty successful profile.
- `outcome.json`: CLI outcome, evidence availability and successful persistence,
  runtime/finalization, terminal accounting, physical drain, retention and
  collection-loss quality, summary counts/loss, discarded/pending occurrences,
  retained bytes, native errnos, original/final ownership, diagnostic truncation
  and staged primary/close/retry/output failures. Unknown values remain JSON null,
  including unknown loss and ownership; they are not zero or false.
- `diagnostics.txt`: available acquisition/collection/cleanup diagnostics, even
  when no session was returned.

Owned-session close is attempted after acquisition even on interruption, failed
finish, serialization or output failure. Retained exception ownership receives
one explicit bounded `retryCleanup()` attempt; failed evidence never becomes
complete because cleanup succeeded. Interrupt status is restored after cleanup.
Original failure remains primary, with cleanup/output failures suppressed, and
the CLI throws after retention. If storage itself fails, some files may be absent;
the thrown primary/suppressed errors remain authoritative for that failed attempt.
Never replace missing output with a claim of success or retry into the same output
directory. No external target is ever killed or released by this example.

Successful CLI completion, finalized evidence, helper exit and physical drain do
**not** prove collection-loss completeness. Coverage remains bounded to the
observation window and incomplete; ordinary-JIT frames can be unresolved or
unavailable and loss counters are lower bounds. Generic `BillOfBehavior` remains
empty, policy eligibility and collection loss completeness remain false, and
`toDedicatedWorkerPolicies()` rejects. This is observational diagnostics, not
universal stack correctness or security-policy inference. Fake Java child host
tests cover the example flow only; they cannot attach BPF and are not native
acceptance or support qualification.
