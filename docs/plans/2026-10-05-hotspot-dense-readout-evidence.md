# Deferred HotSpot readout evidence

Implemented internal parser-owned lazy canonical JSON for event/frame/evidence and
invocation-local strict UTF8 decoder reuse. Summary validation remains eager;
field/depth/row/frame/identity checks and retention accounting remain unchanged.
Raw getters preserve unknown fields/key order; PUBLICATION supports concurrent
diagnostics. A red follow-up restored canonical equals/hashCode for internal data
class value semantics. Public constructors/copy/ABI are unchanged.

Scoped commits92e9fa0a/233f8e6a; qualification diagnostics live under
`profiler/build/reports/hotspot-dense-comparison/`. Baseline9jars were copied before
changes and fingerprinted; the same pinned Temurin25.0.4.1+1-LTS image/flags and
native stripped graph are used. Offline Java driver is compiled once before runs,
then linked to baseline/candidate jars; no compilation occurs in observed workload.
Three serial alternating fresh-process pairs, uncontrolled host load/filesystem
cache. Input synthetic600projection is1,285,889bytes/SHA
f82aa97ba6397666ad3db3896b0a94827e1c2917e359aca9676a49b5de7545dd.
It is cloned from a genuine schema1projection, not600fresh native samples.

All baseline/candidate parsed diagnostic documents compare equal.600document bytes
1,151,802/SHA e5413cd679b1b4c06e703faafdd054e6c3df6d265abc641f769f7612cad4fe66.
Single-document bytes2424/SHA16445cf21e91ed1a68d27864378d085fe26b66ecd9f31c1dc118621df1762bec.

|Mode/repeat|Pipeline ns|Mapping ns|Reader/decode/I/O ns|Encode ns|Total ns|
|---|---:|---:|---:|---:|---:|
|Baseline0|3771230520|1587565246|2183665274|1204792864|5089195366|
|Candidate0|1303301448|154456266|1148845182|2112119651|3488818169|
|Candidate1|1370307825|161011811|1209296014|1306132118|2741577765|
|Baseline1|1183627179|483047728|700579451|1010283340|2228985664|
|Baseline2|1364945006|552684246|812260760|1302500674|2730547777|
|Candidate2|849573808|94648818|754924990|885693657|1767958682|

Mapping median0.553s ->0.154s; pipeline median1.365s ->1.303s. Encoding cost moves:
candidate median1.306s versus baseline1.205s. Total medians2.731s/2.742s do not
establish end-to-end improvement. The small noisy sample is not a latency bound.

Actual public matrix7tests/0skips/0failures/0errors,187.639s,
timestamp2026-10-05T19:04:55.042Z. Paced current run accepted60records/0pending,
admission46.201s and finish-call2.399s. Dense600/40warmup/1ms run rejected:
admission29.419s, marked0.720s, finish-call3.313s,301received/276retained/25pending.
Both pending ownership false; actual helper/client absence checked before fixture
cleanup. Finish-call includes public diagnostics/adaptation, not just native stop.
Native artifacts:
`hs-current-7685c56c-a873-4868-ba84-7dfd85288b87-current` and
`hs-current-efbfedca-cd72-4b9d-bd04-36df2f49428d-dense`.

Observed red missing raw helper/equality regression; strict UTF8 prefix/multibyte/
reuse controls pass before and after refactor. Full profiler suite/ABI/main Detekt/
ktlint pass. Canonical JSON, unsigned fields, zero-retention validation and snapshot
immutability pass. Native7case matrix passes its existing success/rejection contract.
No600case guarantee is promoted; native budgets, runtime capabilities and coverage
are unchanged. Performance issue remains open for dense and admission costs.
