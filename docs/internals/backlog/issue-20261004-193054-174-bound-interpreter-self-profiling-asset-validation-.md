---
title: "Bound interpreter self-profiling asset validation and dense readout cost"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot"
  - "profiler/hotspot-agentless/Makefile"
  - "profiler/hotspot-agentless/internal/tooling/footprint.go"
target_symbols:
  - "HotSpotAssets"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bound interpreter self-profiling asset validation and dense readout cost

**Context:**
Real pinned -Xint current-JVM acceptance measured 34-82 second asset-admission cost and rejected a 600-operation 1ms window under the unchanged 2-second stop bound; snapshots preserved only 153-298 received records before abort

**Needed:**
1. Retain rejected fixture measurements and profile validation/JSON mapping cost before changing supported workload bounds or native budgets

**Diagnosis2026-10-05:** Interpreter checksum median70.273s/95,912,323bytes;
helper95,730,281bytes. JFR94.18% of3938stacks contain JDK SHA2. Synthetic600replay
median3.304s (1.399s mapping/1.905s remainder), final3.870ms. Actual dense run
rejects268received/267retained/1pending with no pending resource ownership; all7
guarded cases run/pass. See [evidence](../../plans/2026-10-05-hotspot-interpreter-performance-evidence.md).
Keep open: diagnosis complete, cost reduction unproved. Next candidate is release
helper footprint reduction with actual integrity/compatibility/admission comparison.
No budgets or runtime guarantees change based on these measurements.

**Footprint2026-10-05:** Two pinned builds reproduce a68,231,330byte stripped
helper (28.7254% smaller). All7candidate native/public cases execute/pass;
matched checksum median70.944s ->44.966s, but live admission still69.8–78.3s and
dense run rejects283received/182retained/101pending with complete resource cleanup.
Source/license candidate distribution is retained. See
[footprint evidence](../../plans/2026-10-05-hotspot-release-footprint-evidence.md).
Keep open for remaining admission/dense-readout costs; do not infer a latency bound.

**Readout/kernel qualification:** Lazy canonical diagnostics and decoder reuse are
implemented with unchanged typed validation/ABI. Matched mapping median0.553s
->0.154s, but moved encoding keeps total medians2.731s/2.742s; actual dense run
still rejects301received/276retained/25pending with no pending resource ownership.
AF_ALG prototype hashes all9candidate files identically in0.085s median versus
33.174s interpreted checksums on this host. Production hashing remains Java pending
explicit native bridge/opt-in/deadline/ABI review. See
[readout](../../plans/2026-10-05-hotspot-dense-readout-evidence.md) and
[digest qualification](../../plans/2026-10-05-hotspot-kernel-digest-evidence.md).

**Fresh completion decision2026-10-17:** Task2 line-buffer work is deferred:
actual JFR did not establish material copy cost. Baseline/candidate parser,
tooling and jars are byte-identical (1,218files). Three alternating fresh pinned
interpreted replay pairs per input preserve canonical bytes/accounting. Total
medians are0.109/0.099s for the3row fixture and0.583/0.644s for the genuine retained
84row capture; these same-code differences are not optimization gains. Totals
include canonical render/hash/measurement-field construction, not process startup
or outer report serialization/I/O. Full asset validation still rehashes all9files/
68,487,216bytes on the same read-only graph.

Actual public7case matrix runs with0skips/failures/errors, but dense600/1ms
**rejects**275received/265paired/10pending: only225/600marked tokens in paired
public evidence. No pending physical owner; actual helper/client absence checked.
Additional raw dense capture also rejects publicly (313/310/3,270/600marked),
although the original native JSONL contains all600marked tokens plus40warmups,
640terminal pairs and final resource-retirement observations. Native stream
completion does not repair public deadline failure. Keep open for measured
complete public parse/map/render/finalization/cleanup cost and admission; no
isolated line-copy bottleneck or600promotion is established. See
[fresh evidence](../../plans/2026-10-17-hotspot-dense-next-evidence.md).

**Admission contract2026-10-17:** Private full-byte Java provider boundary and
legacy ABI checks pass. Fresh native hash-only pairs are56.35/67.42/57.15ms for
all9files/68,487,216bytes; pinned interpreted common asset-mode totals are
27.17/32.13/19.75s. These are different semantics, not equivalent native admission
or an in-process deadline guarantee. Current7.2host operations succeed; absent
af_alg_restrict is ENOENT, not zero. Current upstream AF_ALG insecure/deprecated
status requires explicit operator acceptance separately from host support.
See [contract](../../plans/2026-10-17-hotspot-native-digest-contract.md) and
[qualification evidence](../../plans/2026-10-17-hotspot-admission-next-evidence.md).
Native activation remains **not_authorized**, Java default unchanged. Real pending
worker/FD ownership, deadline/cleanup/short-I/O/permission injection and additive
public/platform API approval remain required. Binary identities are verified;
historical exact Go source-byte linkage is INFERRED/UNVERIFIED, tracked by the
[source-receipt issue](issue-20261017-173125-908-record-qualification-source-receipts-and-linked-wo.md).
Keep open for complete admission latency and dense public readout; no600promotion.

**Consumer profile2026-10-17 (recovered2026-10-18):** All6 alternating selected
control/JFR diagnostics actually ran without skips; all6 public collections
**rejected**. Paired marked counts in execution order are411/230/548/311/232/273
of600. Event/terminal/pending and actual PID-birth/A-B identities are retained;
owned containers/processes retired, but every partial summary has incomplete
physical drain and terminal accounting. Actual client JFR identifies stdout
`HotSpotWireReader.parseObject` (1391/1395/1407 samples), eager public raw-diagnostic
materialization through `StructuredProfileEvidence`/`HotSpotRawJson.getText`, and
`HotSpotEvidenceDocument.encode` (1867/1514/1337 samples). Decision:
**consumer_cost_identified_requires_followup**, not a proved deadline root cause.
Smallest next proposal: defer public rawJson materialization to existing getters,
with render-counting/canonical-byte/legacy-ABI/validation/owner regressions and full
public rendering measurements. Whole-client categories overlap; scalar timings
have no absolute phase markers, and JFR adds work/events. No line-copy, buffer,
deadline, native pin or profile change is justified. See
[consumer evidence](../../plans/2026-10-17-hotspot-consumer-readout-evidence.md).
Keep open; historical raw640 is a separate experiment and600 remains unqualified.

**Public raw diagnostics2026-10-18:** The approved follow-up retains private stack/
frame owners and delegates existing rawJson getters to their existing lazy values.
Actual owned render counters fail before/pass after: construction leaves stack and
both frame counters0; full public toJson renders stack1/frame0, later requested
getters preserve canonical bytes/unknown/UTF8/unsigned/BCI and immutable snapshots.
Eager event validation and public JVM/Kotlin descriptors remain unchanged. Focused
56tests have0failures/errors and2environment-gated backend skips; all4new tests run.
Three fresh alternating pinned pairs per corpus/mode include actual public
construction/render/output I/O. Report total medians baseline/candidate are
122.144/185.869ms (original3row),1526.159/1380.980ms (retained84row); bytes/counts
match. Raw-getter mode shifts requested work; parse/host variation and deferred
render cost prevent a general speedup claim. No synthetic600 corpus was used.
Actual current dense600/1ms still **rejects**278received/277paired/1pending,
237/600marked pairs; pending physical ownership=false and helper/client absence
checked. Historical37,264report files rehash unchanged. Keep open for complete
admission/dense public readout; no600promotion or bound/profile/native change. See
[follow-up evidence](../../plans/2026-10-18-hotspot-public-raw-json-evidence.md).

---
<!-- id: issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-  file: issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md -->
