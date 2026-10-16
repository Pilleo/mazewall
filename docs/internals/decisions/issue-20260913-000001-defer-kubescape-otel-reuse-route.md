---
title: Defer Kubescape IG and OTel reuse route until Tier E evidence contract is complete
status: accepted
component: profiler
priority: medium
---

## Decision

Defer reuse of the Kubescape IG and OpenTelemetry route until the Tier E evidence
contract is complete. This decision does not alter the native backend, the selected
`raw_tp/sys_enter` observation boundary, the MZSD v2 wire format, or collector-owned
unique-edge acknowledgement.

## Rationale

Tier E still requires durable evidence for attribution coverage, transport
completeness, dictionary integrity, lifecycle validity, capture-domain validity, and
runtime overhead. Reusing an external route before those dimensions are independently
proven would blur ownership and make failures difficult to classify.

## Exit criteria

Reconsider only after the Tier E matrix has durable artifacts for exact attribution,
producer shutdown, snapshot replay, bounded retention, native outcome preservation,
and performance gates. Any future route must prove identical event ordering,
identity, failure behavior, and wire output before it becomes a supported backend.

## Evidence status

`policy_eligibility=false`; this is a design decision, not an implementation claim.
Rootless privileged fixtures remain `INCOMPLETE` and do not weaken host policy.
