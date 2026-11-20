# Issue 042 Recovery Implementation Plan

> **PLAN ONLY.** This document does not resume implementation or authorize compilation, preparation, tests, native execution, downloads, commits or publishing. After explicit execution approval, use `executing-plans` with the gates below. Default to one accountable implementer in this checkout; do not restart the multi-agent queue automatically.

**Goal:** Establish a finite, evidence-based route to the original 042 acceptance criteria—or report an explicit feasibility block before spending more implementation days.

**Architecture:** Consolidate the existing WIP into one coherent candidate. Complete one production lifecycle slice, using the existing owners, reader, scope jobs and immutable receipts; then qualify that exact candidate. No new diagnostic framework, alternate accounting pipeline or weaker definition of completion.

**Tech Stack:** Kotlin/JDK 22+ with JDK 25 idioms; collector Go 1.26.10; pinned Gadget/profiler/Cilium sources; Linux eBPF and existing native ownership machinery.

**Spec:** `docs/plans/2026-11-08-hotspot-production-accounting-proof-plan.md`, especially its proof denominators and conservative completion matrix. Original issue: `docs/internals/backlog/issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md`. Existing implementation history: `.superpowers/sdd/2026-11-08-hotspot-production-accounting-proof-plan/progress.md`.

## Global constraints

- Preserve every original selected/definition/report/PID/enrichment/wire/retention obligation. Global completeness stays false/UNKNOWN until the original conjunction is proved.
- Historical 17/17 selected qualification is not current-candidate aggregate or all-source qualification. Do not modify its historical evidence body.
- Work in the current checkout; preserve all WIP. No worktrees, destructive cleanup, dependency changes, public API breaks, BPF scan redesign, commits, pushes or merges.
- Preserve strict64, four classic pairs, three fentry/fexit pairs, fourteen attachment slots, independent socket Q, CAS64/OR64, actual multicast Cut, ordinary global membarrier, active-nohz_full rejection and default-invalid 80-byte kw_layout.
- Keep original absolute budgets, first-result/error semantics, actual-return ownership, nonrenewable consumed/quarantined scopes and immutable committed receipts.
- Optional diagnostics do not delay or replace native outcome publication. No new metadata workers, contexts, scopes or timers.
- The one approved exceptional parked worker applies only to a scope-less actual uncertain failed-startup cleanup graph. Scoped uncertainty uses existing jobs/finalizers. No global registry or FD retry.
- Unknown authority, unsupported counters, missing receipts, held work, overflow, permission failure or uncertain retirement cannot become successful proof.
- Existing acquisition restrictions remain: only the approved `inspect.py code` STDIN driver, whole-EOF reads, full retained absolute artifacts and one preasserted ASCII JSON display of at most 1800 bytes. No runtime/environment probes during source audit.

## Working rules that change the trajectory

1. **No more feature work before the feasibility and baseline gates.** Existing source-only approvals are inputs, not milestone completion.
2. Keep one acceptance table in the existing progress ledger. Do not add a new report for every helper or getter.
3. Review and test complete production transitions, not individual metadata leaves. Reuse valid checks on the same revision.
4. A reviewer finding must name a concrete broken invariant or a discriminating missing control. A new abstraction must serve an actual production callsite or that control.
5. After one focused correction/re-review round, an unresolved blocker triggers a concrete trace and decision—not another larger delegation prompt.
6. Each checkpoint must end in **PASS with its evidence**, **BLOCKED with the exact prerequisite**, or **FAIL with the next bounded correction**. No inspection-only checkpoint is presented as implementation progress.

## Current baseline—not acceptance

| Original obligation | What is available | Still required |
| --- | --- | --- |
| Selected production conservation and independent per-attempt reconciliation | Historical selected qualification; source changes and controls | Current candidate, original map/CPU/config identities, production denominator and independent reconciliation |
| Definitions, including every enabled auxiliary/report producer | Source-reviewed local diagnostics, transport/projection, preparation and residual controls | Reader drain, ordinary producer retirement, real stop/readout integration, producer snapshot/forwarding and genuine authority |
| Enrichment | Reported source-reviewed admission/delivery corrections | Current-candidate successful delivery and zero-pending evidence, including rejecting sink-failure controls |
| Wire | Existing framing/receipt work | Independent exact prefix/byte/hash receipt, one summary, EOF and rejecting truncation/pairing controls on this candidate |
| Client retention/global predicate | Reported source-reviewed retention/document work | Actual admitted/kept/discarded reconciliation and all five lanes' conjunction |

Compilation/tests/native execution remain UNRUN for the accumulated source changes. Preparation identity and final composed-source manifests are stale. Later constructor failures and C2c remain unfinished; source review does not waive them.

## Task 1 — Decide feasibility before another implementation batch

**Files to inspect:** original spec/issue/progress; `profiler/hotspot-agentless/patches/profiler-additions/tracer/kobject_witness{,_native,_socket}.go.txt`; the retained pinned counter/provider sources cited by the existing native briefs. Inspect source through the approved driver only.

**Produces:** one compact feasibility table in the existing progress ledger: requirement → actual available primitive/evidence → missing fact → permitted next action.

- [ ] Reconfirm the classic underlying int32 miss representation against the unchanged strict missWidth64 predicate. An int64 cast or bounded 32-bit lifetime is not a 64-bit observation.
- [ ] Identify a concrete, contract-preserving way to obtain the required counter capability. If none exists within the approved kernel/profile scope, mark this a structural blocker immediately.
- [ ] Classify Open/Fence/ordinary-RCU provenance, observer lifetime/nonwrap, verifier/stack/attachment/loss and accepting observation authority separately as implemented, unproved or structurally unavailable. Do not group them under a vague “needs testing.”
- [ ] Map every original completion-matrix cell to existing evidence or an explicit remaining task below; preserve all lanes.

**Gate A:** proceed to new feature implementation only if there is a credible route to every unchanged hard gate. If not, retain issue 042 OPEN with a precise blocked handoff and ask for the specific additional kernel/profile/evidence scope needed. Do not silently weaken predicates, drop classic pairs or close a reduced version of 042. Finite WIP coherence/verification may still be approved independently; it does not resolve the feasibility block.

## Task 2 — Establish one coherent candidate before adding code

**Files:** `profiler/hotspot-agentless/internal/tooling/prepare.go`; registered `profiler/hotspot-agentless/patches/`; exact `experiments/hotspot-agentless/` mirrors; existing capture/Kotlin source and tests.

**Produces:** one immutable source fingerprint, exact text-composition result and an explicit verification approval packet.

- [ ] Freeze the current source revision by retained file hashes; account for all existing WIP without deleting or reverting anything.
- [ ] Compose the actual registered patch order against the original retained pinned sources in memory. Require exact old-side contexts and correct hunk counts; no fuzzy matching or later-overlay baseline. Retain the final member texts and addition inventory.
- [ ] Verify canonical/mirror equality and identify actual callsites for every new owner/controller/receipt. List disconnected code explicitly.
- [ ] Fix only concrete composition defects in this task. Do not refactor unrelated EOF warnings or add functionality.
- [ ] Present the exact prepared candidate and command/side-effect list for operator approval. This plan is not that approval.

**Gate B:** all registered members compose exactly and mirrors match. A broken chain stops subsequent work; source-review labels do not override it.

### Separate execution permission gates

The documented `go run ./cmd/mw-collector-tools prepare` is **not read-only**. `prepare.go` runs module verification/download, copies source, applies patches, invokes the native rebuild script and writes generated modfiles/manifests. Do not run it without explicit approval covering those effects; missing caches/images/toolchains require another explicit decision, not an automatic download/install.

After preparation is approved and its exact output verified, the standard modfile is `profiler/hotspot-agentless/build/experiment.go.mod`. Example compile-only approval commands, from `profiler/hotspot-agentless`, are:

```bash
GOPROXY=off GOTOOLCHAIN=local go test -modfile=build/experiment.go.mod -c -o build/issue042-scope.test go.opentelemetry.io/ebpf-profiler/periodiccaller
GOPROXY=off GOTOOLCHAIN=local go test -modfile=build/experiment.go.mod -c -o build/issue042-tracer.test go.opentelemetry.io/ebpf-profiler/tracer
GOPROXY=off GOTOOLCHAIN=local go test -modfile=build/experiment.go.mod -c -o build/issue042-capture.test ./internal/capture
```

Compilation is distinct from executing those binaries. Before any host test run, audit package initialization and the exact selected cases for native effects. `go test -run '^$'` is not a no-execution substitute because package initialization still runs. Extend the compile/test packet to changed Gadget/operator, accounting/identity/tooling and Kotlin code; do not pretend the three example packages cover the whole candidate.

**Gate C:** after explicit permission, the current candidate compiles and selected existing nonnative controls pass. Use one build/fix loop. If permission stays closed, label the candidate UNCOMPILED/UNTESTED and stop at that boundary rather than continue speculative source expansion. The repository full-build gate also requires explicit execution permission and does not replace native qualification.

## Task 3 — Finish one integrated production lifecycle slice

**Files:** `profiler-auxiliary-ownership.patch` (actual events/Tracer hooks), `profiler-kobject-witness.patch` (ordinary construction/owner transfer), `profiler-definition-drain.patch` (existing producer contract, not a permission to relax native gates); existing tracer `reader_health`, `definition_native`, `definition_owner`, `auxiliary_stop`, `pid_mailbox`, `bootstrap_completion` additions/tests; existing periodiccaller `owned_scope` APIs. Mirror every actual source change.

**Consumes:** the one current candidate from Task 2, original owned links/readers/maps, and the existing scope/covered-owner APIs.

**Produces:** the actual connected stop/readout/disposal path—not another controller with no caller.

**Private interface boundary:** complete `(*reportReaderControl).requestDrain(context.Context) error` and the `func() error` owned-closer adapter in the existing controller. Reuse `OwnedScope.WithStopReadoutStep`, `OwnedScope.CommitStopReadout`, `definitionOwner.WithCoveredReadout` and `(*Tracer).readPIDResidual() (pidResidualObservation, error)`. No exported issuer or replacement scope interface is needed. A drain error cannot be converted into successful EOF, and a closer cannot return as safely disposed while its resource graph is unknown and unrooted.

- [ ] Seal ordinary activation/rearm admission and inventory each actual report-producing attachment/resource from the existing C2c handoff. Do not add report maps to the fourteen-slot native witness graph.
- [ ] Retire those actual producer resources under the original first budget. Preserve native errors and retain uncertain/hot resources; link Close or active=0 alone is not genuine quiescence.
- [ ] Complete the existing controller's private original-budget drain request: one Flush on the same reader; requested ErrFlushed from the same loop; actual read/Flush/callback returns; immutable first terminal result. No second reader, worker or renewed budget.
- [ ] Coordinate the existing Scope closer with those actual-return facts. Never dispose under an unresolved borrow; retain unresolved cleanup in the existing job and preserve its cause. Nil-controller ordinary behavior stays unchanged.
- [ ] Wire the lifecycle into the actual native completion path before Scope cancellation. Optional metadata collection still happens after native outcome publication and cannot replace its error.
- [ ] Wire final producer/residual sampling into the existing covered stop-readout finalizer, using preactivation expectations and original map/config/CPU identities. Call the reviewed residual adapter here; do not substitute a literal retired/jobReturned flag.
- [ ] Route remaining synchronous loader and later constructor failure stages through actual retaining cleanup, after borrowers return. Reuse reviewed ownership mechanisms; do not start a separate generic cleanup framework.

### Required ordering (partial order, not invented acknowledgments)

```text
seal new activation/rearm
    -> actual producer retirement attempts/returns
    -> original-budget reader Flush request
    -> same-loop ErrFlushed + actual Flush/read/callback returns
    -> begin existing Scope.Stop outside its own jobs
    -> actual normal GoOwned-job join INSIDE Scope.Stop
    -> existing covered readout finalizers sample original maps/receipts
    -> finalizers/steps return, then lifetime release and gated physical disposal
```

**Important:** readout sampling is after the normal-job join **inside** Scope.Stop, not after Scope.Stop has returned. The current readout reservation is consumed and lifetime released by then. Loop-body return, job join, completed Scope.Stop and genuine native retirement are four different facts.

**Discriminating controls:** held detach/Flush/read/callback prevents premature close; first deadline expired with Done open starts no new effects; rejected startup is not entry/return; requested versus unsolicited ErrFlushed; failure retains the same graph after caller/error discard; real permission errors survive; original references/config metadata checked before/after sampling; a late result never upgrades the first receipt. Use existing private production seams, not copied test orchestration or accepting native fixtures.

**Gate D:** the production path is connected and its controlled end-to-end trace passes on the same compiled candidate after approved execution. One review covers this whole slice and connected failure paths. Local source reviews alone cannot pass Gate D.

## Task 4 — Finish evidence transport and all-lane reconciliation on that slice

**Files:** existing tracer report snapshot/diagnostic producer; OTel/Symbolizer cached envelopes; `internal/capture/definition_accounting.go`, `definition_local.go`, `inventory.go` and tests; existing Kotlin hotspot accounting/retention/document sources/tests and public integration assertions. Canonical and experiments copies must agree.

**Consumes:** the connected original-owner snapshot/return interfaces from Task 3, independent expected identities and wire/retention receipt interfaces. Controlled fake-effect fixtures may test this wiring but cannot supply production authority.

- [ ] Forward actual report-producer counters/bindings/invalid/held/reader facts through the existing bounded cached envelopes into capture. Preserve schema/units and missing versus contradictory states; add no accepting authority.
- [ ] Reconcile every enabled auxiliary source, including mailbox removal/residual facts and actual store consumption. Do not infer runtime coverage from a static source list or a zero count.
- [ ] Recheck selected per-attempt independent identity reconciliation and aggregate CPU/map/config denominators—not just aggregate arithmetic.
- [ ] Exercise existing enrichment delivery, exact wire-prefix/EOF and retention receipt logic with current candidate data. Sink failure, truncation, missing terminal, discards, overflow and an extra unknown source must prevent global completeness.
- [ ] Reuse existing four-leaf/prune/binding/source controls. Add only a missing production-boundary control identified by this integration; no new testability layer without a concrete need.

**Gate E:** the same compiled candidate's connected receipt path passes controlled integration and per-lane rejecting controls. Fixture arithmetic/policy results remain explicitly MODEL-only and cannot produce an accepting native certificate. Definitions/global admission remains false until Task 5 supplies genuine production authority, receipts and retirement evidence. Passing this gate means integration is ready for qualification, not that 042's proof is complete.

After each complete source batch, refresh its fingerprint and required generated inputs before checks. A check on an earlier revision is not evidence for the changed one. Reuse checks only for unchanged relevant inputs; do not repeat the full repository build after every helper.

## Task 5 — One bounded genuine qualification campaign and closure decision

**Files/artifacts:** existing qualification tooling, current source/prepared/native identities and source inventories, current production receipts, original issue ledger. Preserve historical qualification artifacts unchanged.

- [ ] Obtain separate operator approval for the precise native graph, environment/profile evidence, bounds, cases, failure controls and commands from the original Task 4. No live probes or native runs are authorized by this recovery plan.
- [ ] Qualify the exact tested candidate, not stale generated assets or the old selected-only run. Record actual kernel/profile/ordinary-RCU, observer lifetime/nonwrap, strict counter capability, attachment/verifier/stack/loss and original resource identity evidence.
- [ ] Run the project-required final `./gradlew build` once focused checks pass and its complete execution/privilege requirements are authorized. This is not a substitute for the bounded accounting campaign.
- [ ] Run one bounded campaign covering the original positive and rejecting oracles. Missing qualification or a structural counter mismatch is a hard stop, not permission for synthetic positive witnesses or another unbounded campaign.
- [ ] Publish one acceptance table: original criterion → current source/native identity → actual artifact → rejecting control → PASS/BLOCKED/FAIL.

**Close 042 only if every original completion-matrix row passes with current production evidence.** Otherwise leave it OPEN and name the exact unresolved criterion. Do not relabel source acceptance, selected-only qualification, or UNKNOWN as completion.

## Stop conditions and next action

- A structural hard gate without an authorized solution stops new feature work at Task 1.
- A composition/build failure stops the sequence at Task 2; repair that candidate, not a parallel feature branch.
- A blocked native operation retains ownership and the original failure/unknown outcome; never force-close/unlock to finish a milestone.
- If a batch still cannot produce its connected deliverable after one correction round, return its concrete failing transition and permission/architecture decision. Do not spawn replacement workers repeatedly.

**First execution checkpoint, if approved:** Task 1's compact feasibility table plus Task 2's read-only exact composition result. No implementation restarts and no execution-permission request until those reviewable results exist.

## Self-review against the original spec

- Original Tasks 1/2 (aggregate and independent reconciliation): Tasks 2, 4 and 5 above; no selected-only substitution.
- Original Task 3a: Task 3 lifecycle and Task 4 complete-source transport, with Task 1/5 authority gates.
- Original Tasks 3b/3c/3d: Task 4 current delivery/wire/retention integration and rejecting controls.
- Original Task 4: Task 5 one bounded campaign on one candidate.
- All native invariants, historical evidence, permission boundaries and immutable first receipts remain required. No original lane is dropped or declared disabled to make this plan smaller.
