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
  - "docs/plans/2026-10-18-hotspot-observed-profile-contract.md"
  - "docs/plans/2026-10-19-hotspot-bridge-release.md"
  - "docs/plans/2026-10-19-hotspot-integration-gate-checkpoint.md"
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
  - "docs/plans/2026-10-19-hotspot-storage-live-api.md"
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
The archived [governing agentless design](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/superpowers/specs/2026-10-18-kubescape-agentless-hotspot-design.md)
and [four-stage implementation entrypoint](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/superpowers/plans/2026-10-18-kubescape-agentless-hotspot.md)
describe a node-agent-owned existing-collector companion, shared Go verification/
wire boundary, additive typed storage evidence, bounded ownership and actual
Kubernetes saved-profile acceptance. The current strict profile and Tier E
prototype remain distinct. The user's explicit Subagent-Driven selection after
the six-document handoff on 2026-10-18 approves P0 implementation/design scope by
reference, including the public SDK, schema3 observation semantics, storage API
and companion placement. Task0 records this approval; native qualification/
admission, SDK publication, writable storage provisioning, the dirty node-agent
baseline and cluster operations retain their separate gates.

The original [storage](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/superpowers/plans/2026-10-18-kubescape-stack-evidence-schema.md)
and [companion](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/superpowers/plans/2026-10-18-node-agent-hotspot-companion.md)
execution targets are likewise retained as immutable approval history. Current
guidance uses the [shared ownership contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md#shared-contract-and-ownership)
and [release gates](../../plans/2026-10-19-hotspot-bridge-release.md#ordered-gates),
not local execution plans.

Source inspection additionally confirms storage's current callstack aggregation
reconstructs frames from FileID/Lineno strings and drops FrameType; new Symbol/
Evidence data would also be lost. The storage subplan includes a discriminating
typed-value preservation regression and the real protobuf/OpenAPI/API gates.
Keep this issue open: plans, old fork inspections and completed allocator checks
do not constitute node-agent integration, ordinary-JVM admission or storage rollout.

**Read-only integration checkpoint (2026-10-19):**
The [six-case provider candidate](../../plans/2026-10-19-hotspot-coupled-exec-evidence.md)
now qualifies its controlled observational corpus; the actual production CLI still
refuses admission and published bridge v0.1.0 stays immutable. A separate storage
checkout now contains local typed-schema/aggregation/API-gate preparation at
`61f0be2f51c54abac7abd3d405f662c550e0ecae`; its handoff explicitly declares
`b3Accepted:false` with no reviewed live-server/context/release/image identities.
The dirty node-agent prototype is unchanged. These observations supersede the
planning-time missing-checkout blocker, not the real API and operator gates.
The [current review checkpoint](../../plans/2026-10-19-hotspot-integration-gate-checkpoint.md)
records the exact remaining decisions and local isolated SDK packaging evidence.
Keep this issue open; no ready provider handoff or end-to-end acceptance exists.

The operator subsequently approved continuing release/integration, selected the
current dirty node-agent baseline and approved an isolated disposable local kind
environment for the reviewed storage server. [C1 evidence](../../plans/2026-10-19-hotspot-companion-local-evidence.md)
records a scoped local config/projection commit with independent staged-tree race
checks and preservation of all original prototype bytes. SDK release review is
PR540; no pending review, API or end-to-end gate is represented as passed.

The subsequent [local B3 API gate](../../plans/2026-10-19-hotspot-storage-live-api.md)
passes JSON/protobuf Create/Get against exact storage46c8444a image bytes after the
operator-approved full Create-response correction. Both-format cleanup and an
independent empty profile inventory are checked. Original failed captures and
setup failures remain frozen; storage release provenance, C runtime wiring and
final D acceptance still prevent closing this overall integration issue.

**Subsequent companion work:** C1–C5 host-side implementation is committed locally
through node-agent72034696, with independently exported source/race/build receipts
and the original prototype preserved/excluded. The checked publication path and
existing disk-backed save queue retain typed symbols without policy promotion.
The separately approved disposable native VM reaches initial-PID/BTF/cgroupv2
preflight and a CA-verified Ready K3s node, but no final native capture runs ahead
of actual release handoffs. PR540 still has no required review; immutable SDK
v0.2.0 publication, storage release/image identity, hermetic consumer pins, ordinary
node image and final D acceptance remain uncompleted. Host units and Ready
environments do not close this issue. See the updated companion evidence for exact
commits, retained failures and disposable-resource custody/retirement.

The [subsequent shared-review checkpoint](../../plans/2026-10-19-hotspot-bridge-release.md#current-shared-review-checkpoint)
supersedes PR540 routing above: the delegated existing-Apache check is recorded,
shared provider guards and the three local node integration remedies are addressed,
and the prior configured source gate passed at its recorded revision. That pass
does not qualify the subsequent selected-root fixes before their final source gate.
This issue and all six review issues remain open; exact-source SDK release review,
actual SDK/storage publication and consumer pins, released-graph/native qualification
and default-branch merge gates remain blocked.

---
<!-- id: issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an  file: issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an.md -->
