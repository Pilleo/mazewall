# HotSpot production proof recovery implementation plan

> **For agentic workers:** Use `subagent-driven-development` or `executing-plans` only after the applicable gate opens. Those workflows do not authorize commits, worktrees, cleanup or native actions.

**Status: PLAN ONLY. Implementation and native qualification remain PAUSED.**
The operator approved the high-level reset, not code resumption, source exclusion, native queries, instrumentation, builds or qualification. This document changes sequence and gates only; it does not replace the original Definitions, completeness or security contract.

**Goal:** Recover a finite path to issue 042's independently reconciled production proof, delivering honest intermediate lane evidence without confusing it with full completion.

**Architecture:** Resolve source feasibility and the lifecycle contract first; then reuse the original enrichment, wire and client tasks as one useful vertical slice. Make only necessary Definitions corrections, and certify one explicitly selected candidate graph.

**Tech Stack:** Existing Go collector, pinned Gadget/OTel patches, existing wire extensions and Kotlin client. No new dependencies, public API design or accounting framework.

**Spec:** The [original approved plan](2026-11-08-hotspot-production-accounting-proof-plan.md) and its referenced accounting contract remain binding; this document changes execution order, not the promised proof.

## Global constraints

- Missing admission, identity, quiescence, delivery or retirement evidence remains UNKNOWN/failed; no source exclusion or weaker completeness predicate is implied.
- Preserve EPERM/EACCES, original deadlines, capabilities, output bounds and retirement barriers, including the 262144-byte whole failed-receipt cap.
- Preserve all existing work. No commits, pushes, worktrees, destructive resets, automatic cleanup or unapproved provider fallback.
- This is a plan-only deliverable. Native instrumentation, image rebuild and all-source promotion remain gated below.

## Binding references and path notation

- [Original approved plan](2026-11-08-hotspot-production-accounting-proof-plan.md), SHA256 `c1ba748ffe9de3083f39cd49ca75ba6d7749848b0ede02bc7ad31b8686bd2958`; unchanged. Its Tasks 1–2 and 3b/3c/3d/4 remain the executable scope and detailed test specification.
- [Selected accounting contract](2026-10-17-hotspot-selected-accounting-contract.md) and [production-loss evidence](2026-10-17-hotspot-production-loss-evidence.md) retain their authority.
- [Strategic reset rationale](../../.superpowers/sdd/2026-11-08-hotspot-production-accounting-proof-plan/reviews/task-3a-strategic-reset-review.md), SHA256 `5e30b302ef3ae4180f15970ca983a235e7dee964ac6689434ee19969c8c41d10`.
- `W` = `.superpowers/sdd/2026-11-08-hotspot-production-accounting-proof-plan`.
- `H` = `profiler/hotspot-agentless`; `P` = `H/patches/profiler-additions`; `G` = `H/patches/gadget-additions`. Expand these aliases literally when assigning files.
- `K` = `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot`; `KT` = `profiler/src/test/kotlin/io/mazewall/profiler/hotspot`.
- Existing counterparts under `experiments/hotspot-agentless` remain byte-equal mirrors, not another candidate.

## Milestones and authority

| Milestone | Finite deliverable / owner | Relevant files | Acceptance | STOP condition |
|---|---|---|---|---|
| M0 — preserve baseline | WIP/evidence disposition; parent | `W/reports/recovery-candidate-inputs.md`; existing acceptance/source reports | Accepted, unverified and historical inputs distinguished | Unexplained input or destructive cleanup proposed |
| M1 — feasibility FIRST | Installed source matrix + blocking decisions; planner, reviewer, parent | Existing production-loss evidence; `W/reports/recovery-decision-record.md` | Every required source classified with exact missing witness | Active source lacks defensible admission/closure: no native instrumentation, image rebuild or all-source promotion |
| M2 — lifetime contract | One short resolved contract record; planner/reviewer, parent | Same decision record; original plan and guard files listed below | Fixed safety rules and rejecting oracles; provenance settled | Material contract ambiguity or unsafe return path |
| M3 — useful vertical slice | Independent enrichment/wire/client receipts and interim report; implementer/reviewer | Original 3b/3c/3d file sets below; `W/reports/recovery-interim-proof.md` | Real local regression evidence; Definitions UNKNOWN means global false | False completeness, self-reported delivery or unapproved execution |
| M4 — necessary Definitions work | Minimum matrix-selected correction and regression; implementer/reviewer | Original 3a file set below, exact subset recorded before work | Required source boundaries and integrations demonstrably addressed | Missing capability/scope authority, admission or quiescence |
| M5 — one candidate/certification | One graph-bound proof bundle and lane conclusion; parent, implementer, reviewer | Candidate manifest; original Task 4 evidence/manifests/goldens | Current graph qualified; independent receipts and retirement | Authority failure, unknown retirement, non-triggered control or unexplained failure |

M0–M2 are documentation/read-only preparation after the parent approves that bounded work. M3/M4 require explicit implementation resumption; this roadmap does not grant it. M5 native preparation and execution each require the existing separate authority and prerequisites. Normal scoped technical reviews do not require renewed global design approval per leaf.

## M0 — preserve work and name the candidate honestly

- [ ] Parent records accepted L1–L4 as local/nonfinal infrastructure and accepted C0–C2 as missing-receipt/error preservation, using `W/reviews/task-3a-l5-missing-receipt-acceptance.md` and existing L1–L4 evidence.
- [ ] Mark A1 scope/kind/mailbox/PID cache source **shelved in place, unverified**; preserve `W/reports/task-3a-l5-pid-process-cache-source-report.md` and its combined-diff evidence. Do not delete, stash, roll back or assume acceptance.
- [ ] Record historical 7e 17/17 separately from the modified checkout. Preserve and exclude the ten reviewer root scratch files from candidate inputs; no cleanup is authorized.
- [ ] Establish `W/reports/recovery-candidate-inputs.md`: exact selected source inputs, existing mirrors, prepared-tree provenance, included/excluded WIP and unresolved dispositions. No build is required to inventory inputs.
- [ ] Before any candidate build, explicitly choose **retain and verify**, **rework and verify**, or **remove only our A1 changes**. Preserve recoverable before/after evidence; removal needs operator approval and must not discard user work.

**Acceptance:** no silently included shelved WIP and no alternate prepared tree called “current checkout.” The manifest is updated for each real revision; only its final selected graph may be certified. Do not expand this into a general source-pin catalog.

## M1 — first real deliverable: install the feasibility matrix

**Files:** `docs/plans/2026-10-17-hotspot-production-loss-evidence.md`; new `W/reports/recovery-decision-record.md`. Read current source through the original 3a owners. Use `W/reports/task-3a-l5-feasibility-rows.md` as leads, not runtime authority; historical source-gate analysis predates later changes.

- [ ] Perform **one focused inventory pass**, then install/refresh rows in the existing evidence document, not just another scratch report.
- [ ] Cover every enabled trace/report producer, PID/mailbox source, netlink/kobject source, polling/prune owner, async/coalescing queue and definition-store consumer.
- [ ] Each row names actual owner and exact-profile identity; independent admission denominator; terminal/loss classifications; producer, reader and consumer closure; rejecting oracle; classification; exact missing witness.
- [ ] Classification is **measurable**, **authoritatively disabled for this exact profile**, or **active/unmeasured**. Separate measurable design from observed current-graph proof. Missing proof or a quiet sample is never disabled status.
- [ ] Explicitly expose report emission/loss, suppression/coalescing/overwrite, mailbox coverage, kobject send/receive gaps, sched-hook closure, split quiescence and mapping-retention gaps. Local receives plus successful Stop do not establish kernel admission.
- [ ] Finish the pass with a short decision artifact: what can be proved now, what cannot, and the smallest concrete operator choice for each blocker. Do not start another open-ended inventory loop.

**Gate:** any required active source without defensible admission/closure keeps native instrumentation, image rebuild and all-source promotion STOPPED. M2 and subsequently authorized host-only M3 may progress; no cache work or native campaign precedes this disposition.

**Material operator decisions, in plain English:**

1. For report/PID sources, approve the precise additional admission/closure capability shown necessary by the matrix, or leave those sources UNKNOWN and full proof blocked. Local receive accounting is not the cheaper equivalent.
2. For kobject/kernel-symbol reload, retain the original contract and authorize investigation of a defensible kernel-to-socket loss boundary, or explicitly reconsider that contract boundary. Exclusion requires a separate contract decision and exact-profile authority; this plan neither recommends nor grants exclusion merely to pass.
3. If the full goal is presently infeasible, report the blocker and accept only an interim result, keeping issue 042 open. Do not redesign the definition of completeness to manufacture success.

## M2 — settle the lifetime/deadline rule once

**Output:** one short section in `W/reports/recovery-decision-record.md`, not another framework proposal.
**Evidence files:** original plan constraints/3a; `G/pkg/operators/ebpf/definition_readout.go.txt`, `event_finalization_backend.go.txt`, `event_finalization_owner.go.txt`; `G/pkg/symbolizer/otel/definition_drain.go.txt`; `W/reviews/task-3a-l5-bounded-snapshot-safety-review.md` and the parent’s provenance findings.

- [ ] Distinguish original native rules from the blanket no-live-Go-getter-after-Stop-return condition associated with the rejected callback. Record provenance and the binding interpretation, not a convenient assumed weakening.
- [ ] Preserve: no map access after retirement; no new native queries after window expiry; no success on timeout; original deadline and strict retirement. A held lease protects resource lifetime until its action returns; it neither cancels a blocked action nor certifies success after expiry.
- [ ] Preserve Validate outside the borrowed action/owner mutex. Add no `ctx.Err()` or user error callbacks to previously immediate return paths. Already-entered Go-only work after return is **not authorized by this roadmap**.
- [ ] Review only the minimum clarification or mechanism actually needed. Fix the safety oracles: expired/failed borrow rejects new queries; held action prevents resource retirement; failed Stop returns within its original bound; timeout stays failed; Validate ordering is preserved; late activity cannot upgrade final proof.
- [ ] If post-return Go-only continuation needs a changed interpretation, present two explicit choices: retain the strict prohibition and justify the minimum bounded mechanism, or approve the narrowly described continuation with lifetime/no-success guarantees. Parent/operator resolves any material relaxation; reviewers do not invent authority.

**Acceptance:** one agreed contract with concrete rejecting tests assigned to future code. BS-1/BS-2 are resolved or recorded as blocking, not “fixed” by unexecuted source. A1/A2/B/C remain optional, not sunk-cost obligations.

## M3 — deliver a small, truthful production-path vertical slice

After M1/M2 disposition and explicit code resumption, reuse original **3b → 3c → 3d** as the already-scoped followthrough. Do not create replacement interfaces or a second proof protocol.

**Exact file sets (aliases above):**

- Enrichment: `H/internal/capture/{enrichment.go,enrichment_test.go,collector.go,inventory.go}`; original optional `enrichment_accounting_test.go` only if warranted.
- Wire: `H/internal/output/{jsonl.go,jsonl_test.go}`; capture collector/inventory; `K/{HotSpotWireReader,HotSpotCollectorWorkers,HotSpotSessionInterpreter,HotSpotEvidenceAssembler}.kt`; repository-root `hotspot-bridge/wire/record.go` extension mechanism.
- Client: `K/{HotSpotEvidenceAssembler,HotSpotWireMapper,HotSpotEvidence,HotSpotEvidenceDocument}.kt`; original new internal `K/HotSpotCollectionAccounting.kt`; `KT/{HotSpotEvidenceAssemblerTest,HotSpotStreamingEvidenceTest}.kt`.
- Admission tests: `H/internal/capture/inventory_test.go`, `H/internal/accounting/lanes_test.go`; existing genuine fixture `/hotspot/measured-event-projection.jsonl`.

- [ ] Count enrichment admissions independently; admit successful delivery only after sink success and successful Finish. Trigger rejected terminal delivery, queue/byte skips, submit-after-close and stalled Finish/deadline controls.
- [ ] Reconcile producer prefix against actual receiver bytes and EOF, not helper exit or JSON encoding success. Trigger altered byte, drop/truncation, partial write, missing/duplicate summary and appended-record controls; preserve parser bounds and cancellation.
- [ ] Count client admissions independently of kept/discarded retention. Trigger nonempty retention0/truncation, wrong target/source identity, overflow and each-missing-lane controls. Revalidate serialized proof; legacy/absent proof remains false.
- [ ] Keep source-specific receipts and independently acquired identities. The selected map binding cannot impersonate another source. A helper summary cannot certify its own delivery or client retention.
- [ ] Carry original Tasks 1–2 production/independent-reconciliation receipts alongside the slice; distinguish implemented diagnostics from actual current-candidate evidence, and list any remaining reconciliation gap rather than assuming it closed.
- [ ] Produce `W/reports/recovery-interim-proof.md`: lane, actual boundary exercised, identity, denominator, rejecting control, evidence class and missing gate. Definitions UNKNOWN keeps `collectionLossComplete` false/global completeness UNKNOWN; retention completeness and policy eligibility remain separate.

**Evidence boundary:** host tests and read-only retained fixtures may support partial development after resumption. Label replay/fixture results as such, never fresh current-image proof. A controlled native end-to-end run occurs only when M5 gates permit; partial-lane value is not permission to bypass M1 or rebuild early.

## M4 — correct only the Definitions gaps that matter

**Files:** original 3a subset selected by M1/M2: H capture definition adapter/inventory; `P/tracer/{definition_reader.go.txt,definition_accounting.go.txt,auxiliary_stop.go.txt}`; `P/periodiccaller/owned_scope.go.txt`; H patches `profiler-auxiliary-ownership.patch`, `profiler-definition-drain.patch`, `gadget-auxiliary-ownership.patch`, and their existing owned-scope/reader/auxiliary tests. Native emission corrections, if authorized, use existing prepared `support/ebpf/tracemgmt.h` and `interpreter_dispatcher.ebpf.c` sites, not a parallel program. Any additional exact source file must be selected by the matrix and reviewed for original-scope fit before editing.

- [ ] Select the smallest correction from matrix findings and the resolved contract; do **not** automatically finish A1/A2/B/C. Link each changed boundary to its rejecting oracle and missing proof fact.
- [ ] Surface extra native admission/quiescence capability or source/contract change for operator approval **before** code/instrumentation. If unavailable, keep the row UNKNOWN and stop this branch.
- [ ] Close R’s real handler-to-real-K queue/coalescing witness, not merely separate R/K unit tests. Preserve distinctions between requests, coalescing and execution.
- [ ] Resolve source-level mapping-retention failures. Carry actual original native identity, metadata/name convergence and retained-mapping witnesses as explicit M5 obligations; local M4 acceptance cannot claim them. Balanced counters or cached expected identity are insufficient.
- [ ] Use one scoped implementation/regression/review cycle per meaningful deliverable. Accept only when its specific oracle changes and relevant closures remain safe; otherwise retain the failure and correct the identified cause once.

**Acceptance:** all required Definitions sources have defensible admission and closure designs with local regressions. This enables a native authority request, not a positive native claim. Remaining feasibility uncertainty stops promotion.

## M5 — select and certify one coherent graph

**Files/outputs:** finalize `W/reports/recovery-candidate-inputs.md`; original Task 4 contract/evidence documents, affected canonical/mirror preparation manifests and qualification/golden inputs; `.superpowers/sdd/2026-11-03-hotspot-definition-accounting/progress.md` and issue 042’s existing checkpoint through its established owner.

- [ ] Resolve A1/WIP disposition before a candidate build. Pin actual source, preparation/module inputs, embedded assets, image, goldens and current fingerprint; retain exact command/input hashes. Reuse unchanged binaries only with real graph/provenance equivalence.
- [ ] Request preparation/build authority only after M1/M2/M4 gates permit it; request native execution separately for the exact candidate and bounded cases. Preserve original Task 4’s seven-module control plane, initializer/master-primary gate, fresh closed per-case authority roots and actual metadata/capabilities. Never reuse consumed/quarantined scopes.
- [ ] Qualify only that graph: affected ABI/non-ABI and lifecycle integration, one bounded positive production capture, and predeclared negative controls. Historical 17/17 remains baseline; do not mix old/current receipts or substitute stale ABI fixtures.
- [ ] Require nonzero exercised admissions, independently observed target/owner identities, every possible-CPU row, native conservation, store/enrichment delivery, receiver prefix/EOF, bounded-client accounting and strict owned retirement. Unexercised auxiliaries are not positive coverage.
- [ ] Record each fault’s intended injection, observed trigger, rejecting boundary, errno and retirement/retained-owner disposition. Non-triggered controls are not passes. Stop on authority rejection, unknown retirement or unexplained failure; no rerun without a concrete correction and renewed authority where required.
- [ ] At actual completion, run the original affected integration gates and root-required full `./gradlew build` once after focused checks; if its native prerequisites are not authorized, mark completion blocked rather than bypassing them. Finish original reference/mirror/backlog/diff validation through the owning workflow.

## Execution and evidence discipline — applies after resumption

- One application writer. Parent owns scope, evidence and acceptance; configured implementer/Terra handles implementation, configured planner design and configured reviewer review. No Argon retry, Grok purchase, provider/model fallback or silent routing change.
- Each coding task includes meaningful assertion RED, minimal source change, real regression, applicable checks and **one** scoped connected-code review. Do not restart global planning for each leaf. Reuse unchanged valid checks; rerun changed closures and unresolved concerns.
- Before tests, retain exact source/mirror/replay/host pins and `go list` package/dependency Dirs for Gadget/profiler using the **same flags, environment, cwd and modfile** as testing. No green result from an unused prepared tree.
- Use bounded supervisors and retain invocation, raw output, wall time and EOF evidence, accurate exits and all intended root/subtest events. Preserve the failed raw-receipt cap **262144**. Do not count the three known epoch-host ABI setup failures as green, silently skip them or present them as native proof; resolve or report the 24584/24600 mismatch on the actual candidate.
- Preserve EPERM/EACCES, original deadlines/capabilities/output bounds, pre-retirement readout barriers and strict resource ownership. No extra native probes to make a report look complete.
- A no-implementation handoff is not progress: inspect the blocker and reassign/rescope once, rather than shrinking into endless serial leaves. Stop and reassess if two handoffs do not reduce the same blocking fact, or a new abstraction has no direct rejecting oracle/outcome.
- Report completed proof facts, not session/letter counts. No unsupported calendar estimate, new framework, dependencies, public API expansion, commits, worktrees, destructive cleanup or user-work rollback is authorized.

## Definition of done and immediate next action

- [ ] **Interim milestone:** enrichment, receiver wire and bounded-client claims are individually evidenced at their actual exercised boundaries; Definitions and other missing lanes remain explicitly UNKNOWN, global completeness stays false, and issue 042 stays OPEN.
- [ ] **Original full proof:** Tasks 1–2 independent reconciliation and every required Definitions/enrichment/wire/client lane admitted on the same current candidate, authoritative source coverage, actual final EOF, checked arithmetic/final pairing and strict retirement; applicable checks and rejecting controls pass with inspectable receipts. Known reconciled loss is reported separately from loss-free health and retention completeness.
- [ ] **Otherwise:** preserve failures and exact missing decisions; no completeness claim, automatic issue archival or endless retry. Original Task 4 leaves any later archival to operator assessment.

**First deliverable after parent approval of documentation work:** install the per-enabled-source matrix in `docs/plans/2026-10-17-hotspot-production-loss-evidence.md` and attach the short blocking-decision disposition in `W/reports/recovery-decision-record.md`. Stop after that finite pass for any material operator decision; do not resume caches or native work merely because this roadmap exists.
