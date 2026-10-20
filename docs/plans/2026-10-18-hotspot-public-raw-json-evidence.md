# Deferred public HotSpot raw diagnostics

## Decision and scope

Public stack/frame construction now retains its existing immutable internal owner;
`rawJson` delegates to that owner's existing lazy value. This removes rendering of
**unused individual frame strings**, including when the public aggregate report is
requested. Stack rendering needed by `toJson()` is deferred, not eliminated. Event
arguments still render/parse/validate eagerly. No second cache or data-semantic
change was introduced.

This implements the [measured follow-up](2026-10-17-hotspot-consumer-readout-evidence.md#smallest-follow-up-and-required-regression)
under the [remaining-work globals/R2](2026-10-17-hotspot-remaining-work-spec.md)
and [upstream globals/C/D](2026-10-17-hotspot-upstream-alignment-spec.md).
Only the two owner parameters and two getters in
`profiler/src/main/kotlin/io/mazewall/profiler/api/StructuredProfileEvidence.kt`
change production behavior. Public
descriptors, typed fields, event/summary handling, parser validation, native code,
assets, profiles, permissions, buffers, retention and deadlines remain as specified.

**Dense600/1ms still rejects; the performance issue stays open.** The timings below
do not establish a general end-to-end gain or sole deadline cause. The historical
native640 stream and six rejected JFR/control cases remain separate experiments.

## Baseline and reproducible local receipts

Work was confined to the authorized checkout
`/home/leanid/Documents/code/java/jseccomp/.worktrees/hotspot-review-remediation`,
branch `feat/hotspot-followups`, base
`86b3a96f4e76edbe50632968e2d6a40ca656f65b`. The source checkout was not modified.
`OUT=profiler/build/reports/public-raw-json` was created with an existence guard.
Generated evidence is local, not staged or a published artifact.

Before production edits, `./gradlew :profiler:toolingClasses :profiler:installDist`
succeeded (28 actionable tasks, all up-to-date). `baseline/` retains all3,818
tracked source/build inputs in a hashed inventory and archive,1,223 actual
main/tooling/resources/install-lib files, input copies, public `javap -public -s`
output, classpath, toolchain/image receipts, asset inventory/manifest and historical
report hashes. Its dry-run dependency graph is retained in `commands/baseline-graph`.
These are preserved current build outputs, not a claim of a fresh clean rebuild.

`candidate/` preserves the corresponding built outputs after the production change.
Only the two wrapper class files and their containing profiler jar differ among
the1,223 archived files. Tooling and all other classes/jars are byte-identical.
`candidate/source.json` includes the new test before its last whitespace-only fix;
`candidate/final-regression.kt` preserves the actual final test before measurement.
The final production source matches the candidate archive. Exact commands, cwd,
selected environment, exit status and complete stdout/stderr are under `commands/`.

Scoped `adkw blast-radius` ran for both public wrapper symbols; both exited2 with
`PARTIAL / INDEX_MISSING`. No index was created. `stack-blast.log`, `frame-blast.log`,
`exact-callers.log` and `raw-callers.log` supplement that limitation with exact source
references. The construction chain is public evidence → occurrence → stack → frame;
`HotSpotCoverageAdapter.toLiveResult` constructs the actual public result.
`HotSpotEvidenceDocument.encode` reads aggregate stack/event raw values, not frame
raw getters. Existing internal `HotSpotRawJson.text` owns the only lazy rendering.

|Receipt in OUT|SHA-256|
|---|---|
|`before-measurement.sha256`|`98edf383d761d6b7302d183fb1734505c349b72bfb8e1531fbba249334b580d3`|
|`baseline/source.json`|`97030801051b66b421d2b1d82410971eb2cf09b5e4ad9a2663c43cf2f4960c04`|
|`baseline/binaries.json`|`57ff44a9b47306abbe9bebd386c1f111b167c6bd4c361c20999ffbd7a8a0e122`|
|`candidate/source.json`|`20ec0e2d3c450fdc18102467d8a396db67240aa51f5dc74622b1aca1c9ffd14a`|
|`candidate/binaries.json`|`2a8088aacae18ab4157fcef7843cb5d913fc10ec4d98f2397b59b79cc2f69030`|
|`PublicReplay.java`|`4195f5e13cc33dbad716aea415aefb187221113024b39c415ed354a9f70164f7`|
|`measurement-analysis.json`|`207548d6015f214c0aab9304bb6b40102e3cb4869c7c1a541ac7b337ba4641c9`|

Gradle9.6.1 uses Ubuntu JDK25.0.4.1+1-1-26.04.4. Replay and actual client use the
unchanged local Temurin25.0.4.1+1-LTS image
`eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`,
with `-Xint -XX:+UseSerialGC`, no JFR, no warmup and no image pull. Host kernel is
`7.2.10-x64v3-xanmod1`. Neither native binaries nor dependencies were rebuilt.
Historical exact native source/build linkage remains **INFERRED/UNVERIFIED**.

The unchanged manifest SHA256 is
`0584b244540dbc615a126b690144b68eb774963f6c5c969a252445abb5669b04`,
collector SHA256 `afb3fd5a0a994a06ed70e42689abe586b926b21fe2967db2c423c73a60c3907e`,
OCI root `sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68`.
The archived full directory inventory hashes the actual local graph. The manifest
covers9files/68,487,216bytes. Normal live admission still validates every byte and
the complete local OCI graph; no digest cache or native hash provider was selected.

## Genuine RED, GREEN and ABI

`HotSpotPublicRawJsonTest` starts from the measured projection and actual assembler
partial snapshot, then gives real owned stack/frame/event raw values distinct
render counters. It constructs the public partial `ProfilingResult` through the
existing adapter, and separately the actual public evidence constructor/report.
Unknown provider/frame fields, UTF8 `λ猫`, uint64 maximum raw/native positions,
uint32 maximum raw BCI, observed BCI5, canonical bytes, shared lazy string identity,
defensive filename bytes and immutable copied record/frame/error/recovery lists are
checked. The original mutable source lists are cleared before public construction.

Counter order is `[event, stack, frame0, frame1]`:

|Operation|Required/current counts|Original RED counts|
|---|---|---|
|Public construction and typed access|`[1,0,0,0]`|`[1,1,1,1]`|
|Public `toJson()`|`[1,1,0,0]`|`[1,1,1,1]`|
|Only frame0 raw getter|`[1,0,1,0]`|`[1,1,1,1]`|
|Then stack raw getter|`[1,1,1,0]`|already eager|
|All later raw getters/repeated reads|`[1,1,1,1]`|already eager|

RED: `./gradlew :profiler:test --tests '*HotSpotPublicRawJsonTest'` exited1;
4tests,3actual render-count failures,0skips. The eager invalid event flags/base64
test already passed. Original XML, failure messages and triage output are preserved
under `commands/red/` before production edits.

GREEN: the approved six-suite selection plus EvidenceAssembler, Coverage,
SessionLifecycle, SessionMachine, Backend and WorkloadFacade tests and both ABI
tasks exited0 (23.005s). Actual XML:12suites/56tests/0failures/0errors/**2skips**:
the two installed/native backend cases require their opt-in environment. The new
four tests all ran. Existing checks cover report encode/decode, zero-retention
invalid fields, duplicate/session/PID-birth/limit refusal, incomplete policy
coverage, pending ownership, retries and physical-retirement classification.
`commands/green-abi` retains XML, counts and exact invocation.

Three intermediate focused formatting checks rejected only the new test's chain/
argument wrapping; these logs are retained. Final regression/style command exited0:
4tests/0skips/failures/errors, `:profiler:ktlintTestSourceSetCheck` passed. Main-source
formatting passed. No formatter changed other files.

`:profiler:checkKotlinAbi` and deprecated alias `:profiler:checkLegacyAbi` passed.
Independent saved `javap -public -s` outputs are byte-identical for stack, frame,
event and top-level evidence. Stack constructor remains
`(Lio/mazewall/profiler/internal/hotspot/HotSpotStackEvidence;)V`, frame constructor
`(Lio/mazewall/profiler/internal/hotspot/HotSpotFrame;)V`; both `getRawJson` methods
remain `()Ljava/lang/String;`, as do every other public descriptor. Existing result
constructor/copy JVM reflection checks ran. Event bytecode is unchanged.

## Matched complete-public replay

The retained scratch Java source-mode `PublicReplay.java` calls the existing JVM-
callable `HotSpotReplayMeasurementKt.measureReplay`, then **the actual public
`StructuredProfileEvidence` constructor and `toJson()`**, then writes actual UTF8
canonical output with `CREATE_NEW`. No product measurement mode was added.

Inputs copied/hashed before measurement:

- `original3row`: measured projection,2,380bytes/3wire rows/1occurrence, SHA256
  `f8f1674326e7fc08cb9de310a631462d29bf964aaa430d20e94602854e5e6720`.
- `retained84row`: genuine retained `hotspot-review-remediation-qualification/xint-g1/capture.jsonl`,
  353,492bytes/84wire rows/41occurrences, SHA256
  `4767c27a971c05ee8cd55b68e0cc50ed0550925ad155d0406c262410aee44118`.
  This historical interpreter/G1 capture is neither a new supported G1 profile nor
  600fresh native events. No synthetic600 corpus was used.

`measure.py` executes three fresh alternating pairs per input **and per mode**:
`baseline0,candidate0,candidate1,baseline1,baseline2,candidate2`. All24processes
exit0. Exact classpaths, argv, image and input/harness hashes are retained in every
run directory. Docker uses `--rm --pull=never`, ordinary UID:GID, read-only checkout
and only that run's output writable. Source/build/binary/toolchain/input/command
receipts were hashed in `before-measurement.sha256` before the first replay.

`report` requests the full public canonical report without individual raw getters.
`raw` additionally requests every stack/frame raw getter and writes those returned
strings to a second file. It cannot avoid work the caller explicitly requests.
The raw mode moves that work from construction into getters. In report mode the
stack raw string is deferred until encoding, while unused individual frame strings
are actually avoided (proved by counters, not inferred from timing).

All timings below are milliseconds inside the source-mode main. Parse includes
input open/read/decode/map/finish/close; constructor is public adaptation; getters
also count frames; render is actual public `toJson`; I/O includes UTF8 encoding and
canonical write (plus requested-raw write in raw mode); hash includes reading and
SHA256 of the actual canonical file. Total includes all these stages. Source-mode
compilation, JVM/container startup and writing the outer measurement JSON are
excluded. Writes are OS-buffered, not fsync durability. Stage medians need not sum
to the median total.

|Input/mode/run|Parse|Public ctor|Getters/count|Render|Output I/O|Read/hash|Total|
|---|---:|---:|---:|---:|---:|---:|---:|
|original3row/report/baseline0|86.423|4.445|0.137|2.648|0.305|23.741|117.699|
|original3row/report/candidate0|144.477|4.496|0.316|6.191|0.627|22.042|178.148|
|original3row/report/candidate1|150.300|4.988|0.311|6.534|0.617|23.120|185.869|
|original3row/report/baseline1|103.992|3.407|0.162|3.433|0.461|10.689|122.144|
|original3row/report/baseline2|157.635|5.770|0.286|5.798|0.739|23.816|194.044|
|original3row/report/candidate2|154.855|4.933|0.315|6.722|0.659|23.498|190.982|
|original3row/raw/baseline0|93.407|4.252|0.286|3.111|0.478|13.612|115.147|
|original3row/raw/candidate0|159.543|4.351|1.395|5.218|0.747|22.088|193.342|
|original3row/raw/candidate1|86.265|2.096|0.623|2.689|0.436|10.907|103.016|
|original3row/raw/baseline1|109.018|2.707|0.171|2.886|0.504|10.937|126.223|
|original3row/raw/baseline2|84.744|2.582|0.162|2.622|0.408|10.464|100.983|
|original3row/raw/candidate2|160.944|4.427|1.417|6.078|0.958|24.092|197.916|
|retained84row/report/baseline0|509.590|484.644|0.353|469.724|6.493|201.915|1672.718|
|retained84row/report/candidate0|549.218|40.587|0.300|600.022|6.001|183.523|1379.651|
|retained84row/report/candidate1|550.701|40.231|0.304|608.304|6.312|201.632|1407.483|
|retained84row/report/baseline1|519.790|354.235|0.289|395.777|5.807|187.696|1463.593|
|retained84row/report/baseline2|541.968|361.995|0.313|425.882|5.845|190.156|1526.159|
|retained84row/report/candidate2|533.131|40.055|0.284|603.240|6.894|197.376|1380.980|
|retained84row/raw/baseline0|521.424|364.413|2.212|427.380|16.833|198.222|1530.484|
|retained84row/raw/candidate0|281.065|23.040|149.786|191.476|12.151|107.572|765.090|
|retained84row/raw/candidate1|515.214|39.803|330.038|413.606|15.488|192.049|1506.197|
|retained84row/raw/baseline1|556.810|385.881|2.280|415.810|17.591|194.165|1572.538|
|retained84row/raw/baseline2|336.282|399.376|2.336|403.228|15.160|142.373|1298.755|
|retained84row/raw/candidate2|287.320|20.265|142.459|290.832|15.859|190.099|946.835|

Total medians baseline/candidate: original3row report122.144/185.869ms,
raw115.147/193.342ms; retained84row report1526.159/1380.980ms,
raw1530.484/946.835ms. Larger report construction median361.995→40.231ms, but
render425.882→603.240ms. The unchanged parse path itself varies substantially,
especially in raw mode; three pairs on an uncontrolled host/cache do not establish
a general speedup, latency bound or deadline repair. No gain claim is made from
moved serialization or these noisy totals. Avoided frame rendering is a behavioral
result; complete public throughput remains unqualified.

All12 canonical files per input compare byte-for-byte, including both modes:

|Input|Received/retained/discarded/pending|Canonical bytes|SHA256|
|---|---|---:|---|
|original3row|1/1/0/0|2424|`16445cf21e91ed1a68d27864378d085fe26b66ecd9f31c1dc118621df1762bec`|
|retained84row|41/41/0/0|344258|`18ad2db7b859268d2de0075c972668221dff75ea5a02ee51bd3bb84ae0ff4ded`|

Every requested-raw file also compares byte-for-byte within its corpus. These
offline captures retain collection-loss incomplete and do not attest live native
retirement. Actual nanoseconds, nested mapping/finish timings and all output files
are in `measurement-analysis.json` and `measurements/`.

## One actual current dense public case

The unchanged success-or-rejection test ran with Ubuntu host JDK, both rootful
endpoint variables set to `unix:///var/run/docker.sock`,
`MW_HOTSPOT_NATIVE_ACCEPTANCE=true` and the unchanged absolute
`MW_HOTSPOT_ASSET_TEST_DIR=W/profiler/hotspot-agentless/build/assets`:

```bash
./gradlew :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest.dense interpreter diagnostic retains checked success or rejection*' --rerun
```

Gradle exit0/219.655s:1selected test plus22rootful prerequisite tests actually ran,
0skips/failures/errors. The **public collection rejected**, client exit1, helper
exit137, startup/stop and cleanup deadline errors preserved in `client-output.txt`.
The existing test retains its pre-cleanup helper/client absence assertion.
Post-test `docker inspect` independently reports both exact owned names absent.

Artifacts: `native/hs-current-6c6bac96-d87e-43f9-b584-a2018ea5a877-dense/`.
Actual session `4641a85f40d33b7b22aa972bb3d9d2bf`, PID1201148/birth53031625.
600marked/40warmups/1ms, admission30.176s, warmup0.048s, marked0.747s,
public finish-call2.464s. Partial278received/277paired/1pending/0discarded,
**237/600** unique marked tokens, correct even-A/odd-B labels and strictly
increasing capture BOOTTIME in retained order. Summary158resolved/119unavailable;
finalized, terminal accounting and physical drain all false. Pending physical
ownership is false; collection loss/policy coverage is incomplete.

The run preserves actual rejection and retirement, not promotion or comparison
with historical cases. No separate collector JSONL observer was run; public
evidence, measurements, native diagnostics/client failure and actual test XML are
the available outcome. Old XML was copied before the run. All37,264 previously
inventoried historical report files rehash unchanged (`native/result.json`).

## Completion boundary

The scoped commit uses the unchanged mandatory pre-commit hook for the single
complete build/portal gate. Exact commit outcome, immediately copied
`/tmp/mazewall-precommit-test.log`, task cache/skips/failures, staged inventory,
reference/backlog/whitespace checks and self-review are recorded in
`.superpowers/sdd/2026-10-18-hotspot-public-raw-json/task-1-report.md` and local OUT.
No duplicate standalone full build is used. Independent controller spec/quality
review follows this implementation. No broader capability is promoted.
