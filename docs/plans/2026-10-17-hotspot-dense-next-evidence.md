# Fresh matched HotSpot replay and public completion decision

## Decision and scope

**Dense600/1ms remains rejected; the performance issue stays open.** The fresh
public matrix passes its existing success-or-rejection contract, not dense
promotion. Task2 is deferred: Task1's actual JFR samples did not establish
material line-copy cost. No production reader/buffer optimization was made.
Baseline and candidate below have byte-identical parser, tooling and jars;
their timing differences are same-code variation, not an optimization gain.

Execution: authorized worktree
`/home/leanid/Documents/code/java/jseccomp/.worktrees/hotspot-review-remediation`,
branch `perf/hotspot-dense-readout-next`, base
`b08464726fee609dbe130bf7e15fd5bae6401008`. Only this evidence, the
[performance issue](../internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md)
and [public guidance](../../profiler/HOTSPOT_AGENTLESS.md) are changed.
Runtime, native ABI, permissions, budgets, retention and public API are unchanged.

## Retained artifacts and identity

All new local evidence is under
`profiler/build/reports/hotspot-next-dense/task3-20261017/` (called `OUT` below).
The directory was created fresh; archived baseline and existing CREATE_NEW reports
were not overwritten. Reproduction scripts, stdout/stderr, JSON reports, canonical
documents, XML and native failures are retained there. `precommit-evidence.sha256`
hashes the pre-commit packet; its SHA256 is
`3a000809334754593fe6d8b82b89320ba8142e6444e4c1c2e54ad23207fd450f`.
These generated artifacts are local, not a published distribution.

- Preserved `../baseline` has1,218files. `../baseline.sha256` SHA256 remains
  `aaa67177f97da814d74ee3eb26ddc8ef865bc57716d7ce9ce697236f93b3e7b0`;
  all archived hashes passed (`baseline-verification.log`). Actual candidate
  main/tooling/lib bytes match all1,218archived files (`classpath-comparison.json`).
- Fixture input: `profiler/src/test/resources/hotspot/measured-event-projection.jsonl`,
  2,380bytes/3wire rows/1occurrence, SHA256
  `f8f1674326e7fc08cb9de310a631462d29bf964aaa430d20e94602854e5e6720`.
- Second input: retained genuine
  `profiler/build/reports/hotspot-review-remediation-qualification/xint-g1/capture.jsonl`,
  353,492bytes/84wire rows/41occurrences, SHA256
  `4767c27a971c05ee8cd55b68e0cc50ed0550925ad155d0406c262410aee44118`.
  It is historical interpreter/G1 qualification, not a supported live G1 session
  or600fresh native events. Inputs were hashed before replay (`inputs.sha256`).
- Every process uses local pinned image
  `eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`,
  observed Temurin `25.0.4.1+1-LTS`, `-Xint -XX:+UseSerialGC`.
  `image.json` records actual image inspection. No pull/download/build of native assets.
- Both archived/current asset loaders rehashed all9files/68,487,216bytes and
  validated the complete local OCI graph from the same read-only mounted assets.
  Manifest SHA256
  `0584b244540dbc615a126b690144b68eb774963f6c5c969a252445abb5669b04`;
  graph `sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68`;
  helper SHA256
  `afb3fd5a0a994a06ed70e42689abe586b926b21fe2967db2c423c73a60c3907e`.
  All10native report manifests compare byte-for-byte to that manifest.
  `asset-comparison.json` records per-file hashes/sizes; `native-manifest.json`
  preserves the supplied manifest. Actual helper inspect also records read-only
  assets and `/sys` mounts. Collector2/capture ABI2/wire2 pins remain unchanged.

## Fresh matched measurements

Executed `./gradlew :profiler:toolingClasses :profiler:installDist` successfully
(2.082s command wall time), then `bash "$OUT/measure.sh"` (71.580s command wall
time). The script contains exact classpaths and commands. For each original
input it executes three alternating fresh-process pairs in this order:
baseline0/candidate0/candidate1/baseline1/baseline2/candidate2, with no JFR or
in-process warmup. Each Docker command uses `--rm --pull=never`, ordinary host
UID:GID, the worktree read-only and only `OUT` writable. The command form is:

```bash
docker --host unix:///var/run/docker.sock run --rm --pull=never \
  --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$ROOT,dst=$ROOT,readonly" \
  --mount "type=bind,src=$OUT,dst=$OUT" "$JDK_IMAGE" \
  java -Xint -XX:+UseSerialGC -cp "$CP" \
  io.mazewall.profiler.hotspot.performance.HotSpotPerformanceMain \
  replay "$INPUT" "$OUT/$corpus/$label.json"
```

All following timings are **nanoseconds inside the offline main**, not wall time.
`pipeline_nanos` includes wire I/O/decode/map; mapping is a subset.
`total_nanos` includes opening/reading the input, parse/map, finish, canonical
render, UTF8 conversion/hash and construction of measurement report fields. It
excludes outer report envelope construction/serialization, report file I/O and
process/container startup. `render_nanos` measures canonical encoding only.
Offline replay does not measure attach, workload, native stop or resource joins.

|Input/run|Pipeline|Mapping|Reader/decode/I/O|Finish|Render|Total|
|---|---:|---:|---:|---:|---:|---:|
|projection/baseline0|137694390|23686842|114007548|1706516|6907730|174424851|
|projection/candidate0|79593483|12949008|66644475|896158|3174933|97694306|
|projection/candidate1|79728327|13599421|66128906|938384|3247328|98517468|
|projection/baseline1|90274480|13225110|77049370|894328|3213992|108946040|
|projection/baseline2|77562293|13011287|64551006|1004656|3264362|95892005|
|projection/candidate2|135642039|22910970|112731069|1626105|8029418|186874212|
|native-xint-g1/baseline0|509649476|63579505|446069971|1980924|558254589|1261729432|
|native-xint-g1/candidate0|408335035|50762606|357572429|1953565|574468503|1180052950|
|native-xint-g1/candidate1|235350895|28380332|206970563|1059654|238831360|643832798|
|native-xint-g1/baseline1|235826350|29464584|206361766|1015513|232216870|574135596|
|native-xint-g1/baseline2|233174647|28946378|204228269|1055276|243219693|582685996|
|native-xint-g1/candidate2|240376632|28908003|211468629|1037192|232511021|580217972|

Projection total medians108,946,040/98,517,468ns; retained-native total medians
582,685,996/643,832,798ns (baseline/candidate). Uncontrolled host/cache effects,
cold initialization and three pairs do not establish latency or improvement.
Rendering remains material on the larger retained input; it is not hidden behind
mapping-only numbers. The previous JFR evidence, not these unprofiled timings,
is the basis for deferring line-buffer work.

All6reports per input have identical non-timing fields. Projection received/
retained/discarded/pending=1/1/0/0, canonical2,424bytes/SHA256
`16445cf21e91ed1a68d27864378d085fe26b66ecd9f31c1dc118621df1762bec`.
Retained-native=41/41/0/0, canonical344,258bytes/SHA256
`18ad2db7b859268d2de0075c972668221dff75ea5a02ee51bd3bb84ae0ff4ded`.
Both report collection loss incomplete and native retirement uncertified.
Additional untimed invocations of the existing render wrapper through local
`RenderReplay.java` saved baseline/candidate canonical files for both inputs;
actual byte comparisons passed, not just parsed-object/hash comparisons.
`analysis-v2.json` contains the comparisons and medians.

Actual asset-admission measurements (one per variant; not matched latency claims):

|Variant|Manifest ns|Checksums ns|OCI ns|Collector ns|Total ns|
|---|---:|---:|---:|---:|---:|
|baseline|119665526|29853124468|50900321|231138|30054850267|
|candidate|141428341|25434333709|28253455|209938|25630269081|

## Actual fresh public/native outcome

Executed the prescribed command with both rootful endpoint variables and MW flags:

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock \
MW_HOTSPOT_NATIVE_ACCEPTANCE=true \
MW_HOTSPOT_ASSET_TEST_DIR="$PWD/profiler/hotspot-agentless/build/assets" \
./gradlew :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest' --rerun
```

`native/invocation.json` records the actual invocation (subprocess elapsed
345.9682219028473s, exit0). Diagnostic-only `JAVA_TOOL_OPTIONS` requested JUnit temporary
directory retention; no new temporary directories were retained by that attempt.
The copied official reports and XML remain authoritative. Public suite timestamp
`2026-10-17T16:01:22.336Z`,149.483s: **7tests/0skips/0failures/0errors**.
The dependency rootful suite also ran22tests/0skips/0failures/0errors.

Artifacts under `OUT/native/acceptance/`:

- Paced `hs-current-8dc4eaae-0c83-43ba-85f2-fd16b910031b-current`:
  accepted20marked/40warmups/100ms; PID79289/birth50013373,
  session `20e12e78cfd5c25e477a1f9b501068b6`.
- Dense `hs-current-87d16494-50f5-4495-9e99-0cae87295fa2-dense`:
  rejected600marked/40warmups/1ms; PID81468/birth50017894,
  session `61439e58fdce768f4f5ef03a9118dc2f`.

|Actual public case|Admission ns|Warmup ns|Marked ns|Public finish-call ns|Received/paired/pending|Paired marked denominator|
|---|---:|---:|---:|---:|---|---|
|paced accepted|32983255647|4017122882|2005374407|1535820987|60/60/0|20/20|
|dense rejected|27020360878|46899928|720342851|4709868235|275/265/10|225/600|

The denominator is exact filename token `current-<index>-A/B`, excluding all40
warmups. Tokens are unique; even=A/odd=B and capture-BOOTTIME order increases.
The dense partial paired set is not a contiguous complete prefix (ends222,224,226);
`analysis-v2.json` retains exact present/missing indices. PID/birth/session and
CLOCK_BOOTTIME are consistent within each run. Known named A frames are
leaf/pathA/call/main, B frames leaf/bridge/pathB/call/main; compiler lambda/invoke
frames are retained in evidence. Paced20 and dense204 event-correlated marked
records have those ordered named frames; other paired terminals explicitly
report unavailable. This does not certify capture-time lifetime leases.

Paced is finalized, terminal-accounting/physical-drain complete. Dense is not
finalized, terminal-accounting/physical-drain incomplete, discarded0. Both report
pending physical ownership=false; the unchanged test asserts actual helper/client
absence before fixture cleanup. Failure retains startup/stop deadline expiry,
cleanup-deadline expiry and collector exit137; it is not suppressed into success.
Finish-call time includes public adaptation/diagnostics and cleanup, not only the
native stop deadline. All collection-loss completeness remains false.

### Additional raw dense rejection capture

Because the test copies partial evidence but not original collector stdout, an
additional fresh invocation of the **unchanged** `HotSpotSupportedWorkloadMain`
used the same pinned runtime/assets/settings/public facade, with a new retained
output directory. `capture-dense.py` and `native-dense-raw/command.json` record the
exact invocation. A bounded external `docker logs --follow` observer copied actual
collector stdout/stderr; it neither generated events nor changed the collector.
The longer retained path and observer make this a separate diagnostic, not a
matched throughput comparison.

Client exit1/wall42.929s, rejected; admission33,182,782,314ns,
warmup47,423,809ns, marked749,044,559ns, public finish5,236,109,957ns.
Public partial313received/310paired/3pending/0discarded,270/600paired marked,
246useful known A/B paths, no wrong named path/order. PID82679/birth50026630,
session `5296be7ab5622f42f49738e1fc119246` is identical in partial and raw stream.

Original `native-dense-raw/collector.jsonl` SHA256
`033f17f9cd8e077523edce588abc5f5f19979d32e7a1ebfa628860ac5bd2588d` contains
1,603actual rows:640events (40warm+600distinct marked),640exactly matching terminal
enrichments,320recoveries, start/ready/summary. All600marked tokens are in exact
0..599 capture order with correct A/B labels;265event-correlated marked paths
match,335are unavailable. Native final summary reports resources retired,
producers quiesced, receiver joined, final counter read, buffer drain and terminal
accounting complete,640received/265resolved/375unavailable. This raw native
completion **does not repair the rejected public readout**, which never consumed
its final summary within the existing stop bound. The helper still ultimately
exited137 during failure cleanup. Selected-event loss snapshot0 remains partial.

`collector-inspect.json` records actual helper PID90837 and read-only assets/sys;
`containers-after.jsonl` records both named collector/client absent. Subsequent
`process-retirement.json` checks all three target PIDs and this helper absent from
`/proc`; no invented closeable ownership or administrative cleanup stands in for
actual retirement. Public pending ownership=false after existing retry cleanup.
Original raw/partial/failure packets and client stdout/stderr are retained.

## Validation and next measured work

Focused semantic/compatibility command:

```bash
./gradlew :profiler:test \
  --tests '*HotSpotPerformanceMeasurementTest' \
  --tests '*HotSpotWireReaderTest' --tests '*HotSpotRawJsonTest' \
  --tests '*HotSpotEvidenceAssemblerTest' --tests '*HotSpotStreamingEvidenceTest' \
  --tests '*HotSpotProtocolTest' --tests '*HotSpotResultCompatibilityTest' \
  :profiler:checkKotlinAbi
```

Exit0/7.485s;7suites/36tests/0skips/0failures/0errors, saved under `focused/`.
Covers exact canonical diagnostics, missing-summary refusal, malformed UTF8,
split rows/decoder reuse, unknown raw fields/concurrency/equality, unsigned and
out-of-order identity, retention/zero-retention validation, immutable snapshots,
legacy constructor/copy descriptors and current ABI check.

Next measured bottleneck is bounded **complete public stream consumption and
finalization**, including parsing, mapping, canonical diagnostics and cleanup;
the diagnostic shows a complete native stream alongside a timed-out partial
public snapshot. This does not isolate line copying or grant permission to
optimize it. Asset admission still streams every byte and remains costly.
Keep the diagnostic success-or-rejection contract and issue open. No600promotion,
new latency SLA, budget/permission change or broader runtime support follows.

Local analyzer v1 incorrectly expected Kotlin frames without generated lambda/
invoke entries. Its script/output are retained; corrected v2 checks known named
application frames in order and preserves full original stacks. No production
defect was inferred from the analyzer mistake. Independent review follows this
scoped evidence commit; its mandatory unmodified full-build hook outcome and
immediately preserved log/XML are recorded in the local Task3 report.
