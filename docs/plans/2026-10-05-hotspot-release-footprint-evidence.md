# Verified stripped collector footprint and admission comparison

Approved inline release-footprint plan executed from baseline96ddfdec. Build change
commit `c9cad75644d328b8bc4ea9afb31f0165662f6caa`. No runtime/native protocol,
JVM admission, dependencies, hashing rules, worker/buffer/deadline or coverage
guarantees changed. Release linker flags add `-s -w` to both explicit build paths.

## Actual artifact qualification

- Original static helper95,730,281bytes, SHA256
  `848ab0de219fcf38d5ab575f6812b3cc59ee4aa860bb71ac02a782ce60e13d1e`.
- Candidate static helper68,231,330bytes, SHA256
  `335e6704ea10bc9b2ab3a0ac2d9387271babea7a04d943c2e89b8dd178b39ea2`.
- **27,498,951 fewer bytes /28.7254% reduction**. Baseline preserved under
  `profiler/hotspot-agentless/build/footprint-baseline/`; original `build/assets`
  also remains byte-identical.
- Two independent builds with pinned
  `golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84`
  reproduce the candidate checksum. CGO0/linux-amd64/trimpath/buildvcsfalse retained.
- Actual-artifact red check rejects baseline `.debug_info`. Green check verifies
  absent debug_info/debug_line/symtab, smaller bytes, retained Go module/build
  info and no ELF INTERP (static executable). Go -trimpath omits linker flags from
  embedded build info: linked patch bytes are checked separately; native public
  ready/provider validation checks the actual runtime patch value.
- Candidate manifestSHA
  `95a8263522f1cfac127b9111b981a582a37df5bb41a0bf51952fd080758cb5a2`;
  baseline manifestSHA
  `9ebd31a29c1c5f8cef6d7e58a6a3808f0c1c5bfb12e3105d3371d772846ca89d`.
  Non-helper listed hashes and all schema/ABI/platform/provider/graph identity
  fields compare equal. Gadget digest remains
  `sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68`.
  OverlaySHA remains
  `e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb`.

Candidate bundle/installation are new directories:
`profiler/hotspot-agentless/build/footprint-candidate-assets` and
`profiler/hotspot-agentless/build/footprint-candidate-installed`.
Library/collector/captureABI/wire pairing remains0.1.0-SNAPSHOT/2/2/2.

## Matched fresh-process interpreter admission

Pinned Temurin image
`eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`,
runtime25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`; same installed Kotlin classpath.
Serial alternation: baseline/candidate, candidate/baseline, baseline/candidate.
No overlapping measurements from this session; host load/cache are uncontrolled.
Fresh process is not a cold filesystem cache. Phase tool hashes every asset on
each admission. Total includes cold initialization/report metadata preparation.
Raw rows/provenance: `profiler/build/reports/hotspot-footprint-comparison/`.

|Bundle/repeat|Total ns|Manifest ns|Checksum ns|OCI graph ns|Collector ns|
|---|---:|---:|---:|---:|---:|
|Baseline0|71201608867|157697191|70943561775|53413295|446588|
|Candidate0|45167195578|115921296|44965990071|44732384|380811|
|Candidate1|34812553996|100719794|34632854285|43327893|360304|
|Baseline1|87603239710|124372899|87378956230|55941365|486640|
|Baseline2|59762299367|168377814|59497377026|51473690|408777|
|Candidate2|54173672585|78133460|53971097790|74694216|438375|

Listed input bytes95,912,323baseline versus68,413,372candidate,9files each.
Checksum median **70.944s ->44.966s**, observed **36.617% lower** in this small
sample. Total median71.202s ->45.167s. Byte reduction is deterministic; timing
variation is large and these percentages are not a deployment performance bound.

## Candidate public/native compatibility

Actual guarded matrix **7tests/0skips/0failures/0errors**,316.490s,
timestamp2026-10-05T16:37:54.962Z. Public unique-token ordered attribution,
retention accounting, permission/startup refusal, target restart, ordinary-user
current workload, matched standalone capture and diagnostic dense rejection pass
their contracts with the stripped helper. Native provider validation preserves
patch/graph identity. Kotlin real installed admission/ABI and Python integrity/
distribution tests also pass.

|Current workload|Admission ns|Warmup ns|Marked ns|Finish-call ns|Received/retained/pending|
|---|---:|---:|---:|---:|---|
|20/40/100ms accepted|69849045862|4012288778|2005121015|1275766516|60/60/0|
|600/40/1ms rejected|78274502526|62089453|844175572|2419678060|283/182/101|

Raw native outcomes:
`hs-current-91cf16e1-7336-4aa9-a9f5-56bd1aee5402-current` and
`hs-current-d0a987e7-a2c7-4d78-a009-fb987dad7953-dense` under
`profiler/build/reports/hotspot-acceptance/`.
Both have no pending resource ownership and helper/client absence checked before
forced fixture cleanup. Rejected partial evidence/diagnostics remain retained.
Finish-call timing includes public failure adaptation, not merely native stop.
Live admission69.8–78.3s demonstrates that matched checksum reduction establishes
neither an end-to-end latency bound nor dense readout success.

## Distribution and remaining work

Verified local source/license distribution:
`profiler/hotspot-agentless/build/distributions/mazewall-hotspot-0.1.0-SNAPSHOT-linux-amd64-stripped.tar.gz`,
28,488,501bytes, SHA256
`5bedf85335e5afa1c4089fc1d34b992bf76b956c8844212ceb1570076bf2862b`.
It includes verified candidate assets, Go/module notices, corresponding patched
collector/Gadget/profiler source and checksum inventory. No remote publication.
The original development archive is preserved; future explicit builds use the new
release flags. Debugger-oriented builds may explicitly omit stripping separately.

Footprint reduction is complete. The performance issue remains open: interpreted
SHA256 is still expensive, and dense JSON decode/mapping still exceeds useful
readout capacity. Digest acceleration and mapper/parser work require separately
scoped design/verification; there is no new provider or larger native budget here.

## Final gates

`./gradlew build` passes (run-5d5a4e121c36); repository lint/backlog pass
(run-dff70b8e39e0). Candidate real Kotlin admission and ABI checks pass
(run-934257120dcf), Python asset/distribution checks and source/license packaging
pass. Final archive was regenerated with the updated operator guidance. Baseline
integrity remains unchanged; no hooks or operator configuration were modified.
