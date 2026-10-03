---
document_type: execution_plan
base_revision: add37cab
status: implemented_strict_full
---

# Strict Full Capture Definition Finalization Implementation Plan

> Execute inline on `fast-master`; no worktree, delegation, push or intermediate
> prose updates. The operator approved continuing the next finalization gate.

**Goal:** Drain the owned strict HotSpot definition perf stream and finish full
capture enrichment before native resource retirement under one stop budget.

**Architecture:** Reuse the event-ring stop owner. After its writer retirement and
event readout, an optional strict-profile hook retires profiler-native entry hooks,
flushes the original perf reader, acknowledges the final definition handoff and
joins the consumer, then finishes bounded enrichment. Stop-request cancellation
is separate from owned definition/identity/abort lifetime. Errors remain partial.

**Tech Stack:** Existing pinned Go/Gadget/OTel/Cilium graph, Linux x86-64,
interpreter/Serial strict epoch profile. Native layouts, buffers/cache bounds and
production JVM/Tier E/USER_NOTIF remain unchanged.

**Spec:** `docs/plans/2026-10-02-agentless-finalization-design.md`.

## Constraints

- `--drain-full` requires full structured capture and strict epoch identity.
- Keep one two-second stop budget within the remaining 60-second session cap.
- Preserve event BOOTTIME deadlines and all identity/permission/fatal guards.
- Do not cancel the definition consumer or destroy metadata while queued
  definitions or enrichment jobs can use them.
- Flush is successful only with the explicit perf boundary and final consumer
  acknowledgment; cancellation, a read error, missing owner or timeout is partial.
- Final event/definition loss stays absent; transport drain does not prove native
  submit/probe/decode completeness or universal Java fidelity.
- Preserve unrelated work and partial backlog indexing; use no-fuzz authored
  patches on checksum-verified generated modules only.

## Task 1: Owned definition readout

Files: profiler drain additions/patch, reader health/stop capability and tests.

- [ ] Reproduce missing queued perf tail/final callback acknowledgment with held
  channel and genuine native perf controls.
- [ ] Add explicit drain request/boundary/reader error ownership to the existing
  reader; preserve its batch/watermark and interval metrics behavior.
- [ ] Recognize deliberate Flush separately from error/deadline; finish final
  barrier and close the owned producer channel only on successful drain.
- [ ] Retire native profiler entry points without closing process metadata.
  Permission or unsupported-source failures propagate.

## Task 2: Resolver handoff

Files: optional symbolizer drain capability, OTel resolver drain owner/consumer.

- [ ] Consumer exits on acknowledged channel completion, not shared graceful
  request cancellation; retain default cancellation behavior when drain is absent.
- [ ] Optional DrainDefinitions joins reader and final callback/consumer, shares
  duplicate requests and preserves timeout/error ownership.
- [ ] Close/StopDefinitions cannot bypass a failed/pending drain owner.

## Task 3: Full strict coordinator

Files: event finalization hook/options/scope, collector/CLI/models/report/runner.

- [ ] Add strict-profile hook under the event stop owner's existing deadline,
  before collection retirement. Unsupported ownership remains rejected.
- [ ] Separate request context from owned profiler/enrichment/identity lifetime;
  fatal/target-exec/permission errors abort both and never become graceful success.
- [ ] After event admission closes, drain definitions, then Finish enrichment and
  preserve final errors/health/component observations.
- [ ] Gate `--drain-full`; benchmark manifests/reports require actual full
  component evidence without upgrading partial native loss.

## Task 4: Verification and evidence

- [ ] Run focused host race tests and native queued definition/event acceptance.
- [ ] Run pinned strict full workload, genuine lost-definition recovery and strict
  unloading controls; retain rejected pressure/timeout/fault evidence.
- [ ] Run experiment verification, backlog/syntax/whitespace and repository merge
  gate, update evidence/design and commit only scoped files/index membership.

## Execution evidence

The strict full opt-in path is implemented. Original reader batch/watermark and
metric paths are retained with an explicit drain boundary and final handoff.
Native tests pass 8/8 and 5000/5000 definitions under `-race`; host tests cover
permissions, canceled readers, partial callbacks, lifetime/profile and full-report
evidence. Three paced full repeats resolve all 1200 marked segments; three bursts
capture all 1200 with 176 honest pending-limit skips each. Recovery/unloading
fixtures use an explicit finish gate so producer lifetime remains valid through
owned drain; target exit/fatal identity remains an abort, not graceful success.
Complete loss and independent lifecycle review remain open.
