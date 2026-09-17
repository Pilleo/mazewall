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
