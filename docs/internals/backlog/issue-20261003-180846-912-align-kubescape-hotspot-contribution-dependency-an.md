---
title: "Align Kubescape HotSpot contribution dependency and storage contracts"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "docs/plans/2026-10-03-hotspot-kubescape-contribution-proposal.md"
  - "docs/superpowers/specs/2026-10-18-kubescape-agentless-hotspot-design.md"
  - "docs/superpowers/plans/2026-10-18-kubescape-agentless-hotspot.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Align Kubescape HotSpot contribution dependency and storage contracts

**Context:**
Standalone strict HotSpot evidence is ready for a scoped local contribution proposal, but the inspected Kubescape Gadget fork lacks the OTel bridge and its storage StackFrame cannot preserve method/BCI/quality fields. Select the intended root dependency and event/storage contracts before node-agent wiring.

**Needed:**
1. Prepare the local evidence-backed proposal and patch review units; obtain maintainer decisions on fork/bridge alignment, event identity, schema and limits, then rerun the standalone corpus on the selected node-agent graph.

**Local preparation evidence (2026-10-03):**
The contribution proposal and patch review inventory are prepared. Source inspection
at pinned node-agent `44c4cb2951d5de2ce09231920d9b21c61329eaca` shows Go1.27,
Cilium0.22.0, storage0.0.303 and Gadget replacement06b0d12baca0. The inspected fork
has no OTel resolver path/profiler requirement and lacks the event-stack helper
used by the standalone graph. Pinned storage still exposes only FileID/Lineno/
FrameType for a frame. No compatible node-agent build or integration is claimed.
Keep open until the actual dependency/event/storage contracts are selected and
the standalone acceptance corpus passes on that graph. No remote issue/PR exists.

**Forward planning (2026-10-18, not implementation evidence):**
The [governing agentless design](../../superpowers/specs/2026-10-18-kubescape-agentless-hotspot-design.md)
and [four-stage implementation entrypoint](../../superpowers/plans/2026-10-18-kubescape-agentless-hotspot.md)
describe a node-agent-owned existing-collector companion, shared Go verification/
wire boundary, additive typed storage evidence, bounded ownership and actual
Kubernetes saved-profile acceptance. The current strict profile and Tier E
prototype remain distinct. The user's explicit Subagent-Driven selection after
the six-document handoff on 2026-10-18 approves P0 implementation/design scope by
reference, including the public SDK, schema3 observation semantics, storage API
and companion placement. Task0 records this approval; native qualification/
admission, SDK publication, writable storage provisioning, the dirty node-agent
baseline and cluster operations retain their separate gates.

Source inspection additionally confirms storage's current callstack aggregation
reconstructs frames from FileID/Lineno strings and drops FrameType; new Symbol/
Evidence data would also be lost. The storage subplan includes a discriminating
typed-value preservation regression and the real protobuf/OpenAPI/API gates.
Keep this issue open: plans, old fork inspections and completed allocator checks
do not constitute node-agent integration, ordinary-JVM admission or storage rollout.

---
<!-- id: issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an  file: issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an.md -->
