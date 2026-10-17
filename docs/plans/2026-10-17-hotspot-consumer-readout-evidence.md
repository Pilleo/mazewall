# Actual public HotSpot consumer readout diagnostics

## Decision and authority

**`consumer_cost_identified_requires_followup`**. The actual client recordings
repeatedly identify stdout JSON parsing and eager public diagnostic materialization/
report encoding. They do not establish a single cause of the incomplete dense
collection. All six public collections **rejected**; the success-or-rejection tests
passed. Dense600/1ms remains unqualified and the performance issue stays open.

This is the consumer workstream from the
[upstream-alignment specification, globals/C/D](2026-10-17-hotspot-upstream-alignment-spec.md)
and [remaining-work R2](2026-10-17-hotspot-remaining-work-spec.md#r2-dense-interpreter-readout).
The source baseline is `76195f141b49a5f8a73c4dc9e4b316d2aae0ecf6`, branch
`test/hotspot-consumer-readout-jfr`. No production optimization or runtime/budget
change is part of this evidence. Native B approval remains closed; global collection
completeness and policy conversion remain refused even if a future run completes.

## Retained experiment and recovery

`W=/home/leanid/Documents/code/java/jseccomp/.worktrees/hotspot-review-remediation`;
`OUT=W/profiler/build/reports/hotspot-consumer-profile`. Raw artifacts are local
generated evidence, not Git objects. Each case retains its exact command/result/log,
copied native evidence/measurement/result/client output/manifest, selected and
rootful-prerequisite XML, live container inspection and `/proc` identity, Docker
events, post-run retirement observation and `raw.sha256` inventory.

The interrupted worker had already completed, in order,
`control-0/profile-0/profile-1/control-1/control-2/profile-2`. The last command exited0
at `2026-10-17T22:17:04.291935+00:00`. Recovery on2026-10-18 reused all six runs and
all existing JFR exports; it executed **zero additional paired cases**. Existing
outputs were preserved. New derivations are under `OUT/recovery-20261018/` (`R`).
`R/attempt-3/derived/profile-{0,1,2}/analysis.json` and `analysis-detail.json` contain
the completed classification, including the previously missing profile-2 analysis.
`R/attempt-3/reconciliation.json` records successful independent checks of the430
recovered files and input receipts. Two earlier analysis-comparison failures are
retained: JSON tuple/list normalization and equal-count ranking order were wrapper
assumptions, not failed native tests. Equal-count order is not a measurement.

Each original invocation used the following selections, with explicit cwd `W`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export DOCKER_HOST=unix:///var/run/docker.sock
export MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock
export MW_HOTSPOT_NATIVE_ACCEPTANCE=true
export MW_HOTSPOT_ASSET_TEST_DIR="$PWD/profiler/hotspot-agentless/build/assets"
# Each control, in the retained sequence:
./gradlew :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest.dense interpreter diagnostic retains checked success or rejection*' --rerun
# Each profile, in the retained sequence:
./gradlew :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest.profiled dense interpreter diagnostic*' --rerun
```

All six logs show actual `:profiler:integrationTest` and
`:profiler:rootfulContainerTest` execution. Each has exactly **1 selected test and
22 prerequisite tests, zero skips/failures/errors**. Each Gradle invocation reports
24 actionable tasks,3 executed/21 up-to-date; the third executed task is
`:prepareTestHealth`. The132 prerequisite cases are separate
from the6 selected diagnostics. A test exit0 is not public600 acceptance.

## Actual client, target and owned resources

`C` below means the actual inspected client argv: `java -Xint -XX:+UseSerialGC`,
classpath `W/profiler/build/install/tier-e-daemon/lib/*:W/profiler/build/classes/kotlin/integrationTest`,
main `io.mazewall.profiler.hotspot.HotSpotSupportedWorkloadMain`, canonical assets,
owned `/tmp/junit...` output directory, the selected Docker socket, owned collector
name, and `600 40 1`. Each exact argv is retained in `case-analysis.json` and live
`inspect-<owned-run>-client.json`. Controls have no JFR flags or recording. `P` is
the same argv plus:

```text
-XX:FlightRecorderOptions=stackdepth=128
-XX:StartFlightRecording=filename=<owned-output>/readout.jfr,settings=profile,dumponexit=true,maxsize=16m,jdk.ExecutionSample#period=1ms,jdk.ObjectAllocationSample#throttle=1000/s,jdk.JavaMonitorEnter#threshold=1ms
```

All actual clients use image
`eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`;
live inspections match that image ID. The pinned-image Java receipt reports Temurin
25.0.4.1+1-LTS; each profile's own `jdk.JVMInformation` independently confirms that
runtime, argv, main/owned arguments and PID. Controls' runtime provenance is the
inspected exact image and its Java receipt, not an invented JFR identity. All six
live client `/proc/status` receipts show real/effective/saved/fs UID1000.

Every collector actually used
`golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84`,
UID0, privileged host-PID namespace, read-only canonical assets and `/sys`. Client
asset/classpath mounts were read-only; output and the authorized socket were the
existing fixture mounts. `retired` in the table means pending ownership=false,
both owned containers have actual create/start/die/destroy observations and fail
post-run inspection with no-such-object, and both original PID/birth identities
are absent (all recorded `/proc` after observations are null). It does **not** mean
complete stream drain. All six have finalized=false, physicalDrainComplete=false,
terminalAccountingComplete=false and collectionLossComplete=false, with zero
discarded occurrences/recoveries.

`A/M/F` are measured admission/marked/finish seconds. They are scalar scopes from
`HotSpotSupportedWorkloadMain`: admission includes asset validation and attach;
marked includes600 paced calls; finish runs from marked-end to return/exception,
including existing failure/close handling before the catch. It is not a native
stop timer or a complete end-to-end serialization/I/O time. Failed-evidence
`toJson()` at line112 and final measurement serialization occur after `ended`.

|Case|Actual client VM/args|Public accepted/rejected|Expected/paired marked|Received/paired/pending|Physical ownership|Readout/finish timing scope|Named JFR source evidence|
|---|---|---|---|---|---|---|---|
|control-0|Temurin25.0.4.1+1-LTS, C; PID839407|rejected; client exit1|600/411|468/451/17|retired; collector exit0|A35.746827/M0.707913/F6.725798; scalar scope above|No recording; unprofiled control|
|profile-0|Temurin25.0.4.1+1-LTS, P; JFR PID869549|rejected; client exit1|600/230|279/274/5|retired; collector exit0|A28.524588/M0.696049/F4.181908; scalar scope above|`HotSpotWireReader.parseObject`1391; public-view1557; document encode1867 samples|
|profile-1|Temurin25.0.4.1+1-LTS, P; JFR PID899098|rejected; client exit1|600/548|596/592/4|retired; collector exit0|A31.176229/M0.729666/F3.780889; scalar scope above|`HotSpotWireReader.parseObject`1395; public-view1309; document encode1514 samples|
|control-1|Temurin25.0.4.1+1-LTS, C; PID929156|rejected; client exit1|600/311|354/351/3|retired; collector exit0|A34.518361/M0.709231/F5.473090; scalar scope above|No recording; unprofiled control|
|control-2|Temurin25.0.4.1+1-LTS, C; PID958728|rejected; client exit1|600/232|273/272/1|retired; collector exit0|A28.108485/M0.733347/F3.240637; scalar scope above|No recording; unprofiled control|
|profile-2|Temurin25.0.4.1+1-LTS, P; JFR PID988834|rejected; client exit1|600/273|323/313/10|retired; collector exit1|A35.275837/M0.719724/F3.845349; scalar scope above|`HotSpotWireReader.parseObject`1407; public-view1335; document encode1337 samples|

The actual collector container exit in profile-2 is1, not clean0. All six client
outputs preserve failure chains including collector-process exit137; this is a
different process observation from the outer container exits above. Control-0 and
profile-2 report startup/stop deadline expiration; the other four report owned
cleanup deadline expiration. Suppressed close/cleanup/stream failures remain raw.
None is erased by checked eventual resource retirement.

|Case|Owned run suffix (`hs-current-` prefix)|Client PID/birth ticks|Collector PID/birth ticks|Session|Marked correlated/correct A-B path / unavailable|
|---|---|---|---|---|---|
|control-0|a24b3506-a2c3-4982-ac70-53ee2ee81a77|839407/52136521|839789/52139824|46f0fe6502e15b5a6372a2e14dd26dd5|340/340 /71|
|profile-0|f1b95975-52b4-481f-9cef-c0041194f617|869549/52160789|869883/52163557|c7b48879cdc4341ae608f35d07248327|187/187 /43|
|profile-1|b8d2fe44-a73a-42ae-a4fc-c2f615a6498e|899098/52184564|899354/52187564|5c07c0e0ce31b98a577c6ac25321dabb|185/185 /363|
|control-1|63a83a2d-87ab-4f76-81aa-69222d38876c|929156/52209020|929444/52212259|df302d8a22d1395380796e9fe79c04c8|256/256 /55|
|control-2|0a112a9d-9c8d-4075-8964-99c7dd943e16|958728/52232150|958982/52234789|6bac28b6ad26409b87ea1fe194b2e6cd|182/182 /50|
|profile-2|484d950f-08fa-4163-961c-2966f95b34c2|988834/52256148|989358/52259559|b87a0d21d5be730390515ceeeef5b8f1|209/209 /64|

Independent reanalysis counts unique `/current-N-[AB]` tokens against600, checks
even=A/odd=B, increasing marker/capture order, unique occurrence/session IDs and
every event's actual PID/birth/CLOCK_BOOTTIME/openat identity. Correlated paths
match `leaf,pathA,call,main` or `leaf,bridge,pathB,call,main`, without mismatches;
unavailable terminals are not useful stacks. Every run has40 paired warmups.
Profile-0/1 additionally have4 paired `/proc` opens each; these are not marked
operations. Every received count equals retained event/terminal pairs plus pending
occurrences. Exact missing-token sets and all identities remain in `observed.json`.

## Actual JFR recording and source diagnosis

Analysis used Ubuntu OpenJDK25.0.4.1+1-1-26.04.4-Ubuntu, not the target VM identity.
The retained `jfr` command/result receipts bind summary, JVM information, identity/
settings and the depth128 CPU/allocation/monitor/park/DataLoss export to each raw
recording hash. New recovery analysis reads those exports. `RecordingTypes.java`
reads the retained recording's event metadata to map actual ActiveSetting IDs.

|Profile|JFR bytes / SHA-256|UTC start / rounded duration|CPU / allocation / monitor / park / DataLoss events|Reader / mapper / assembler / interpreter / render stack presence|
|---|---|---|---|---|
|0|2010564 / `c32ffb2e222ce6b2c10245635b7132de78cb0fa5c97ddb23d1f454a922d68fdd`|22:00:27 /36s|27127/24/28/1/0|2240/241/247/276/1867|
|1|1894387 / `005a2374dacc87f4281dcef9b6707f7d040eb318445851dce3d148a471b2f5bb`|22:04:24 /38s|28946/30/25/1/0|2329/235/256/294/1514|
|2|1919229 / `7e320027e7b343a4d7b292607e3218385296c70aa633dd87df54d4311af13c25`|22:16:21 /42s|31315/32/18/1/0|2268/239/251/289/1337|

All recordings are JFR2.1, one chunk, below the actual16777216-byte maximum;
ActiveRecording retains the owned filename, disk=true, flush interval1s and
unbounded configured age/duration. ExecutionSample is enabled at1ms, allocation
enabled at1000/s, monitor enabled at1ms, ThreadPark enabled at10ms, DataLoss
enabled. Allocation/monitor/park stacks are enabled. The actual JVM args retain
stackdepth128/profile/dumponexit. There are **zero missing CPU stacks, zero exported
stack-truncated flags and zero DataLoss events**. First/last CPU timestamps are
22:00:27.626702044–22:01:03.889256176,
22:04:24.990169670–22:05:02.859089712 and
22:16:21.165110318–22:17:03.076155824 UTC respectively.
No size-wrap is indicated by these retained one-chunk recordings, but they begin
after JVM startup; absent samples/events cannot prove zero cost or complete coverage.

The category counts overlap, including callbacks under `HotSpotWireReader.read`;
they are not additive CPU percentages or milliseconds. The fixture has **no
absolute phase markers**. Do not align these windows to post-stop work by subtracting
unrelated clocks or call all reader samples decoding-only work.

Connected source evidence (all paths below are under `profiler/src/main/kotlin/io/mazewall/profiler/`):

1. **Stdout decoding:** `internal/hotspot/HotSpotCollectorWorkers.kt:33-35` calls
   `HotSpotWireReader.read`, then `decode`/`parseObject` (`HotSpotWireReader.kt:59,88,99`).
   Actual stdout Java-thread23/OS-thread869843,899311,989315 has2242/2327/2261 CPU
   samples;1391/1395/1407 contain `parseObject`/`Json.parseToJsonElement`.
   `ByteArrayOutputStream.toByteArray` appears in only1/0/0 samples and no allocation
   samples; these recordings still do not justify the deferred line-copy rewrite.
2. **Synchronized mapping/lifecycle:** callback `HotSpotSessionDriver.record:35`
   enters synchronized `HotSpotSessionInterpreter.record:33-49`, then accumulator
   accept/event/terminal mapping. The same monitor guards event/snapshot. Actual
   main waits with stdout as previous owner total23.546/17.575/22.292ms across
   15/12/15 events; timer waits on stdout total11.690/22.491/7.336ms across9/9/1.
   Other retained waits include main behind timer and stderr/exit callbacks. This
   is observed contention, not proof of a seconds-long lock bottleneck. The exit
   worker's single park is5.621/5.478/5.749s at `ProcessImpl.waitFor` through
   `HotSpotCollectorWorkers.kt:46`: blocking for the child, not CPU work. JFR's own
   PlatformRecorder also contends and is kept separate.
3. **Public partial-result materialization:** `api/HotSpotProfilingSession.kt:70-72`
   builds the failure's public view through `HotSpotCoverageAdapter.toLiveResult:22`.
   `api/StructuredProfileEvidence.kt:16,41-55,88` eagerly constructs all occurrence/
   frame wrappers and reads their `rawJson`. This forces the otherwise-lazy
   `internal/hotspot/HotSpotRawJson.getText:10`/`JsonObject.toString`. Actual whole-
   client sample presence is1557/1309/1335 for public-view construction and
   1376/1049/1178 for `getText`, on main. Allocation stacks under that construction
   are10/13/12 weighted samples,106278048/111879976/117478544 estimated bytes;
   `getText` has9/12/10,95092152/100694072/100699752. These categories overlap.
4. **Diagnostic report:** workload failure handling calls public `toJson()` at
   `HotSpotSupportedWorkloadMain.kt:112`; `internal/hotspot/HotSpotEvidenceDocument.encode`
   reparses event/terminal raw JSON at34-35 and serializes the completed tree at81.
   It occurs in1867/1514/1337 main-thread samples. Allocation stacks contain encode
   in12/16/18 samples, weighted116269600/127166896/128250736 bytes. These include
   string/byte-array/tree work, not exact retained bytes or a post-stop interval.
5. **Summary/snapshot/admission limits:** no CPU sample contains accumulator
   `finish`; synchronized interpreter snapshot appears in2/9/2. Source
   `HotSpotSessionInterpreter.kt:46-49` requires actual summary acceptance; none of
   these partial outcomes supplies it. This is not evidence that finalization is
   free or that the native source completed. Whole-client SHA2 appears in
   20911/23297/25801 samples during the unchanged full-byte admission path. It
   dominates the complete recording but does not locate the readout failure.

Total allocation sample weights are245658136/261419608/268840184 bytes, mostly
byte arrays. They estimate allocation across sampled work, not live heap, a leak,
or exact allocations in one phase. Timing/outcome differences between alternating
controls/profiles are instrumentation sensitivity: profiles add JFR work and `/proc`
openat events. The earlier raw640-pair stream in the
[Plan02 evidence](2026-10-17-hotspot-dense-next-evidence.md) is a **separate experiment**,
not a matched control or proof that these six native streams completed.

## Smallest follow-up and required regression

Propose one separately scoped change: keep public `rawJson` accessors on
`StructuredProfileStackEvidence`/`StructuredProfileFrame`
delegating to their already-owned immutable internal values instead of forcing
canonical rendering in public wrapper constructors. Start at
`api/StructuredProfileEvidence.kt:55,88`. The event wrapper separately parses raw
arguments at `api/StructuredProfileEvent.kt:17`; preserve that validation and its
eager typed fields. Preserve existing JVM getter/constructor descriptors, string values and
lazy owner lifetime; no new cache/provider/queue or parser/monitor redesign.
The measured target is the failure/snapshot public-view construction above. It
cannot by itself be claimed to repair missing600 or the native stop failure.

Before accepting that follow-up, require a regression with render-counting owned
raw values: constructing a public partial result must not render unused frame raw
diagnostics; accessing them must preserve canonical bytes, unknown provider fields,
uint64/raw-position/BCI data and immutable snapshot identity. Keep actual report
encode/decode and legacy JVM ABI checks, malformed/duplicate/session/PID-birth/
limit rejection cases and pending-owner/retry/physical-retirement checks. Then use
fresh same-input pairs including full public rendering/I/O and failure evidence;
demonstrate end-to-end improvement before any promotion. Existing scalar measurements
exclude some of that work and cannot be repurposed as that regression. All current
readiness/session/stop/enrichment, retention/line/frame/wire, native worker/map/
buffer, integrity and target/profile limits remain required.

## Input integrity, receipts and documentation gate

Original preflight retained command/cwd/environment/tool versions/module replacements,
exact image inspections, dry-run control/profile task graphs and full source/classpath
inventories before these runs. All2247 non-document source inputs,1142 classpath files,
10 asset-directory files and35320 historical evidence files rehash unchanged on
recovery; every case's before/after receipt was checked. All9 manifest files/
68487216 bytes and the complete6-blob local OCI closure rehash unchanged. This is
fresh byte verification of reused assets, **not a new native qualification build**.
`R/before-commit-2/` retains explicit source/classpath/assets/history after maps;
their receipt hashes equal the original before hashes. The OCI inventory hash
(canonical sorted JSON excluding only the observation timestamp) is
`635069929682aab4ab45b93b54757c1177a34d5b3570622cc3ba7dee2b570b52` before and after.

Collector SHA-256 `afb3fd5a0a994a06ed70e42689abe586b926b21fe2967db2c423c73a60c3907e`;
manifest `0584b244540dbc615a126b690144b68eb774963f6c5c969a252445abb5669b04`;
OCI root `sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68`.
Go1.26.8/linux-amd64/CGO-disabled binary metadata retains both local Gadget/profiler
replacements, patch618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c.
Gradle9.6.1 used Ubuntu JDK25, distinct from the pinned Temurin clients.
Historical native exact source/build linkage remains **INFERRED/UNVERIFIED**;
existing old receipts are not rewritten or upgraded.

|Original receipt|SHA-256|
|---|---|
|`source-before.json`|`26e8b1a118b794dbe691f037aabf7fddf4e01ed53c3e7bd2e8215fa97fe740f6`|
|`classpath-before.json`|`df73413cfdb64a238c385cc33ff1bbc91d20dc322d1cf65240ff0bad29037be2`|
|`assets-before.json`|`f516b96ee20310553df2ea249ae5fba3ca3862b1660215bba194b741894c60f3`|
|`asset-graph-before.json`|`7b90f7387fcc97aba1e6335e41a2616bbcc85cebabbd3833cd27beac271e1a0d`|
|`inputs.sha256`|`e5b75d589b4f61f916273fbbf295c93243520b539d9ba797da47ee1cbcbf2492`|

|Case|Original `raw.sha256` inventory SHA-256|
|---|---|
|control-0|`5dd9890bcf30719c189b2b33bc86c150182a412c759be2d0e73eb7bbc7266d8d`|
|profile-0|`9a14b7e26b271da6e7ca562da2988503d996ca259fc365d28e3acac79a1434da`|
|profile-1|`20e40940d2990ef730bf8525d9aadecf8a17290d23eb2882439ada284be8d288`|
|control-1|`60f729ab5ebdc3cac0c9580f16837d33851a099b4a191c268d0ce64a33e4ea51`|
|control-2|`e7682c11b3f3e0a2bcb287783f6c6167fdc20f2b2149a38d1a1ff2a9af539a56`|
|profile-2|`5237ffab3ced4586a60caf7419dc907bf8cf4d12e7e2b56da67c9a82551e2c94`|

Full command/result/log/raw/export hashes remain in `R` and `OUT/evidence.sha256`.
The scoped documentation commit uses the unchanged mandatory pre-commit hook
(`5bb08f911a47c34c026c533039d9c32888f2832d152ef17447badf72aaddbed1`) and its
full build/portal gate; that gate's receipts, immediately preserved full log and
fresh/reused/skipped counts are recorded in the local Task2 report and `R`. Any
native/JFR cases executed by that gate are separate from these six paired cases.
Reference/Git-durability checks, backlog validation, whitespace and final input-after
hash checks accompany the commit. No standalone duplicate full build is required.
