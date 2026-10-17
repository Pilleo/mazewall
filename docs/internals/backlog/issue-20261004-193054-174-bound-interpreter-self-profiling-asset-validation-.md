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

---
<!-- id: issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-  file: issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md -->
