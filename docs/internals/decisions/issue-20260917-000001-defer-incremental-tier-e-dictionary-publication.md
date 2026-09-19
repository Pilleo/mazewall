# Defer incremental Tier E dictionary publication pending measured need

## Decision

Tier E keeps terminal and 100 ms coalesced live snapshot publication. It does not introduce
an incremental dictionary wire protocol in this change.

## Rationale

The current dictionary transport preserves raw MZSD compatibility, transactional sink semantics,
and explicit terminal closure. Incremental delivery would require an ordered committed-prefix
protocol, idempotent receiver admission, reconnect rules, bounded replay state, and a closure
watermark. Those semantics are worthwhile only if live snapshot measurements show repeated
serialization or retained snapshot overlap is a material cost.

## Required evidence before revisiting

- terminal versus live publication measurements for dictionary full-stream and unique-edge;
- stack-diverse, class-churn, and long-running workloads;
- cumulative publication bytes, serialization time, publication latency, retained-byte peaks,
  coalescing and failed-publication counts;
- a receiver design proving ordered dependencies, retry idempotence, bounded retention and an
  explicit incomplete terminal tail.

No policy or enforcement decision may depend on either transport while the evidence is incomplete.

## Measurement update: 2026-09-18

The measurement prerequisites are complete and retain this decision:

- `tier-e-publication-paired-20260918.json` covers the normal syscall-dense workload;
- `tier-e-publication-stack-diverse-20260918.json` varies exact Java call depth from zero through
  fifteen;
- `tier-e-publication-class-churn-20260918.json` generates a fresh disposable proxy class every
  sixty-fourth operation; and
- `tier-e-dictionary-publication-long-session-20260918.json` covers five dictionary-only paired
  rotations with three warm-ups, ten 500-operation batches, and 25,000 measured operations per
  dictionary variant.

All retained artifacts have workload correctness `PASS`, complete Tier E session evidence, zero
failed publications, and zero native retained-state exhaustions. In the long-session run, live
publication produced 10--16 snapshots and serialized 5.9--11.7 MB in 6--17 ms per fork, compared
with one 1.41 MB terminal snapshot in about 0.8--1.1 ms. Neither dictionary full-stream nor
dictionary unique-edge had a paired workload-time interval excluding zero. The stack-diverse and
class-churn results likewise provide no consistent, cross-mode live-publication benefit.

Therefore incremental delivery remains **not implemented**. A future proposal must supply a new
workload where terminal snapshots demonstrably constrain the required consumer behavior and must
also provide the ordered dependency, retry idempotence, bounded retention, and explicit terminal
tail receiver contract described above. Evidence remains policy-ineligible regardless of transport.
