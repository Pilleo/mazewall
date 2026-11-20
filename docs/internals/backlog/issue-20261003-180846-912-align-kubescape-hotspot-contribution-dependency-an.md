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

**Current source/release checkpoint (2026-10-23):**
The [four-lane runtime qualification handoff](../reviews/2026-10-23-hotspot-runtime-qualification.md)
supersedes the earlier local missing-schema/aggregation descriptions, not their
dated history or outstanding release/runtime obligations. Bridge v0.2.0 is now
published and unchanged; it does not release storage or qualify the node graph.

Actual33-path storage review found three Important defects in this same contract:
persisted Create response-read failure suppressed Added notifications; unrestricted
legacy keys could collide with typed-frame keys and lose a path; decoded bools
could not detect missing explicit-false fields in original JSON API responses.
Five uncommitted code/test corrections and permanent RED/GREEN regressions are
scoped-review accepted (3 addressed,0 new Critical/Important). Notifications now
precede the fallible response read without hiding EACCES; private tagged,
byte-length-prefixed path identities preserve typed/legacy bytes and old equality;
original JSON false-key proof retains acquired-UID cleanup and protobuf checks.
Owning race, actual tagged helper/gogo and ordinary `make test` receipts pass at
the source hashes recorded in that handoff; no new API/runtime pass is claimed.

The separately authorized two-page publication-doc correction labels the retained
2026-10-19 local API pass as historical and its environment as retired. Excluded
receipt paths are nonpublic operator-custody references, not publication links.
Dates, first failures, retirement/custody obligations and no-release limitations
remain; protected outputs/resources were not reopened or revalidated. The new
docs delta awaits controller review. S identity is live HEAD
`4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f` **plus the five code/test and two docs
corrections**, not HEAD alone. New33-path hashes/full patch and updated sequential
proposal are retained in the handoff's fresh Task8 package; all31 nondocument paths
match accepted fix1. Four IgnoreRule paths then32 stack paths overlap in three
generated files (33 unique); the proposal/intermediate is not approved publication
or a tested intermediate release. No schema/generated/module/fixture was changed.

Keep **open**: publication owner/repository/target/base/action, explicit IgnoreRule
acceptance and package grant, actual remote `STORAGE_REVISION`/resolver provenance,
matching server/image, Task2 standalone graph/builds and Tasks4–7 current inputs/
permissions/custody/case grants remain missing. Task3 host-source retention/proof
is complete/review-clean after its small cause/suppressed correction, but native
acceptance is not run. Historical strict-eight FAILED/INCOMPLETE and separate
full-quality/Detekt gates remain unchanged. No issue archive or runtime authority
follows from host evidence or this checkpoint.

**Partial persisted-profile checkpoint (2026-10-24):**
The [current partial handoff](../reviews/2026-10-24-hotspot-persisted-profile-acceptance.md)
supersedes the then-missing publication/remote-consumer/source-request gates above,
not their dated evidence. The operator selected a temporary fork after upstream
discussion455. Exact33-path source is actually published at
[Pilleo/storage6815ab7d489114310ecb8e22b45909f8c86e944f](https://github.com/Pilleo/storage/commit/6815ab7d489114310ecb8e22b45909f8c86e944f),
with full source readback and clean scoped review; canonical main remains untouched
and canonical upstream acceptance/release pending. Actual remote replacement
`github.com/Pilleo/storage v0.0.0-20261024002608-6815ab7d4891`, resolver Origin/sums,
all33 source hashes and preservation of the1136-module graph are verified.
Three standalone builds, two race commands and vet exit0; the SYSLOG-only opt-in
runner correction and host regressions are reviewed clean. These are fork-backed
source/host results, not images or effective container capabilities.

Attempt4's bounded public-native evidence is independently accepted, with standalone
native exit null and global completeness/policy false. Task5 safe preparation is
reviewed, but actual matching images/dedicated direct-node UID1000 authority/
initial-proc provenance/original trust inputs remain **BLOCKED**; compiler/runtime
bases, normal tracer archive and qualified builder caches are missing. Current
Task6 API and Task7 persisted positive/recreation/controls are **NOT_RUN**, exits
null. This checkpoint's single offline `:profiler:detektTest` actually exited1 with
16 current errors and520 warnings; separate issue528 maintenance and issue752
retirement obligations are not waived by the prior configured228-task build.
Keep **open**: final Task8/integration/release review, canonical acceptance, broader
strict/dense qualification and historical unknown-owner limits remain incomplete.
No native replay, live grant, issue archive or status/count change follows.

---
<!-- id: issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an  file: issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an.md -->
