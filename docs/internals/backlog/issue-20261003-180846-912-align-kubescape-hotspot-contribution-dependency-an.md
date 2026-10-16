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

---
<!-- id: issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an  file: issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an.md -->
