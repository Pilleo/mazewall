# JIT/GC native lifetime mechanism decision

Decision (2026-10-17): **source generation checks characterized; native lifetime
mechanism unqualified; production expansion refused**.

This records the result required by
[specification A/D](2026-10-17-hotspot-upstream-alignment-spec.md) and the
[active generation audit plan](../superpowers/plans/2026-10-17-hotspot-upstream-generation-audit.md).
The [evidence record](2026-10-17-hotspot-upstream-generation-evidence.md) links the
actual prepared-decoder baseline, two failing virtual mutants, mirrored source
audit, before-build receipts, failed six-case candidate run and a supplementary
fully receipted run on the unchanged graph.

| Question | Decision and evidence boundary |
| --- | --- |
| Compiled generation information | Existing `nmethod._compile_id` cookie and matching cache/fresh-read guard are present in reviewed pinned source. Three actual host characterization tests pass; removing each guard fails its named assertion. This is not a new capture field or a live retention mechanism. |
| Loaded occurrence generation | No new live native generation qualification. The ABI/revision/patch-keyed sidecar describes source only; ABI/BTF member names and bundle hashes do not bind an occurrence to an independently proved loaded BPF object. |
| Metadata/code validity through decode | **Unqualified.** No acquired native owner or validated immutable snapshot is established. Before proposing either, inspect the actual pin's nmethod unlink/reclaim paths and every Method/ConstMethod/metadata dependency used through decode, including cached-symbol ownership and generation reuse. |
| Concurrent reclamation | **Unqualified.** Useful frames and equal integers cannot exclude concurrent unloading, deoptimization, metadata reclamation or generation reuse. |
| Production admission | **Refused** for compiled/concurrent-GC expansion. A separately reviewed mechanism specification and actual adversarial/native proof are prerequisites. |

The offline audit always reports `production_qualified:false`; its three missing
requirements are metadata stability through decode, code lifetime through decode
and concurrent-reclamation exclusion. The existing `Facts`/`Evaluate` model is an
offline evaluator, never native authority. The candidate sidecar does not mint
leases or feed source facts into runtime admission. Its
`compiled_or_concurrent_gc` scope does not revoke the independently qualified
Temurin25 `-Xint -XX:+UseSerialGC` synchronous `openat_handler_entry` profile.

The observed C1/C2/G1 useful prefixes (31/40, 31/40, 34/40 initially; 27/40, 34/40,
27/40 in the supplementary run) remain application-route observations. Virtual
captures observed no logical identity. The original four-carrier collector failed
closed on task/definition identity mismatch and the offline complete-capture
verifier refused it. Physical owner retirement and a later fully receipted run
passing the six existing experimental validators do not erase that failure.
The additional run closed a pre-acquisition Java-source receipt omission; both
outcomes are retained. Neither run establishes native lifetime, production or
policy qualification.

Any future mechanism specification must identify the actual capture hook,
immutable occurrence-bound stamp, real owner acquisition, code/metadata/GC
reclamation exclusion through the final decode, and checked final release. It
must explain stale-cache, address/generation reuse, deopt/unloading and delayed
decode behavior and supply actual negative/native proof on the admitted graph.
No retained-owner or snapshot mechanism is approved by this decision.

The newer upstream comparison commit uses ring-buffer trace delivery; our pin's
perf delivery and Gadget occurrence/definition streams remain separate. A rebase,
new generation/lease field, helper/BPF layout, patch/revision/asset pin or whitelist
entry is not part of this audit. The older helper's historical exact-source
linkage remains **INFERRED/UNVERIFIED** despite new contemporaneous host receipts.
The [JIT/GC issue](../internals/backlog/issue-20261005-193139-440-qualify-pinned-hotspot-jit-and-gc-lifetime-candida.md)
remains open. The published
[selected-accounting contract](2026-10-17-hotspot-selected-accounting-contract.md)
is still `not_authorized_pending_operator_review`; its corrected acquisition
proposal is not native implementation authorization.
