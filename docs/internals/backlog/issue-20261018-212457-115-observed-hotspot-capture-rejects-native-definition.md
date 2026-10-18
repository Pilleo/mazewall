---
title: "Observed HotSpot capture rejects native definition drain at strict-only guard"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/definition_drain_native.go.txt"
target_symbols:
  - "DrainDefinitions"
open_questions: false
---

# 🟡 [Severity: HIGH]: Observed HotSpot capture rejects native definition drain at strict-only guard

**Context:**
Task4 first real non-root tiered/G1 captures each delivered 400 marked events and terminals but stop failed at the strict HotSpotEpochTarget PID guard; no summary, finality or selected conservation. Preserve rejected qualification and do not bypass ownership guards.

**Needed:**
1. Review actual observed initialization, consumer and quiescence ownership; add meaningful native-path regression before any scoped drain fix and new source qualification.

Actual source is `473acb5ab31168cdba2ad390c105ca83a163e91d9443e53ce45cfecd6f30c58b`.
`DrainDefinitions` rejects when `HotSpotEpochTarget.PID == 0`; the observed profile
intentionally uses `HotSpotObservedPID`, not an invented epoch target. The actual
event backend did invoke `AfterReadout` and reached that rejection. Removing the
guard alone is not evidence of observed initialization/quiescence/consumer ownership.

The first real non-root native cases are preserved under
`build/kubescape-dev/task-4/native-nonroot/`. Independent replay of their real
schema3 prefixes finds 372/400 ordinary, 376/400 compiled-churn and 384/400
class-unload useful route bindings, zero wrong routes and real stack-swap rejection.
Those prefixes are **not completed captures**: no summary, final counter snapshot
or native retirement proof exists. Target exit also rejects; permission denial
retains real EPERM/EACCES. Container removal is separate from native retirement.

The rejected readout returns with retained resources; the current test harness
then releases its target on the failure path. Thus complete target/collector
lifetime coupling is also unqualified. A future fix must preserve the target
until the actual collector owner is retired, not relabel container cleanup as
native completion. No lifetime guard or admission table was changed here.

See [the qualification evidence](../../plans/2026-10-18-hotspot-observed-profile-evidence.md).

---
<!-- id: issue-20261018-212457-115-observed-hotspot-capture-rejects-native-definition  file: issue-20261018-212457-115-observed-hotspot-capture-rejects-native-definition.md -->
