# Issue 042 Forward Decision Plan

> **For agentic workers:** Plan only. Execution needs separate approval. Use `executing-plans` for the approved offline phase; delegation is optional and requires an explicitly selected delegated workflow. Keep implementation and native execution behind the later gates below. Checkboxes record actual completed work, not intentions.

**Goal:** Find a defensible end-to-end path to issue 042's unchanged all-source acceptance, or terminate this research branch with a precise constraint decision before spending on implementation.

**Architecture:** Observation feasibility is the critical path; process custody is a dependent component, not the solution. First identify a complete combination of permitted observations and enforceable invariants for the same consumer Q and every original source. Only an accepted combination may proceed to custody design, implementation planning and separately approved native qualification.

**Tech Stack:** Existing Go collector, Kotlin client, pinned C/BPF inputs, authenticated stock Linux evidence and installed offline analysis tools. No new production dependencies.

**Spec:** [Original production acceptance](2026-11-08-hotspot-production-accounting-proof-plan.md); [reviewed blocked decision](../../.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/evidence-enforcement/feasibility-decision.md); [current observability matrix](../../.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/evidence-enforcement/observability-matrix.md); [custody assessment](../../.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/evidence-enforcement/process-custody-assessment.md).

## Global constraints and authorization

- This request authorizes writing this plan, not executing it. No application edits, probes, VMs, container runs, builds, security-policy installation, commits, publishing or worktrees follow automatically.
- Stock Linux 6.12+; no custom kernel/module, instruction-offset probes, new production dependencies, broken APIs, timing retries or weakened deadlines.
- Same actual consumer Q, every original source, independent identities/admissions/terminals, immutable first evidence, actual-return ownership and strict finality. Never infer admission from delivery, aggregate success, SEQNUM or arithmetic residuals.
- Issue 042 stays OPEN and global production completeness UNKNOWN until the original conjunction is proved. Preserve existing narrower qualifications without relabeling their scope.
- Preserve all existing user WIP, staged/unstaged distinctions, intent-to-add entries, both recovery stashes and retained failures. Do not repeat the already completed master merge or overwrite historical reports.
- The process-wide security floor remains mandatory. No thread or cgroup becomes an ACE boundary by assertion. New external-root/process-control premises must be explicit approval decisions.
- The agent owns evidence recovery and setup. Missing cached artifacts require agent recovery or a documented technical blocker, not a request for the operator to find obscure fixtures.

## File and output scope

This plan is the only file created now. When the offline phase is approved, create its reports under:

`.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/forward-decision/`

| File | Responsibility |
| --- | --- |
| `baseline.md` | Frozen acceptance, consumer identity/topology and delta from prior evidence |
| `candidates.md` | Finite mechanism search and supported/rejected claims |
| `contract.md` | One complete candidate contract, or explicit unfilled obligations |
| `review.md` | Scoped adversarial review findings and disposition |
| `decision.md` | Go/no-go result, exact remaining constraint and next authorization |

Use a unique directory under `/tmp/opencode/` for bulky evidence and record its hashes in the reports. Existing acquisition artifacts are inputs, not disposable scratch space. Historical reviewed reports stay unchanged. Update the existing progress ledger only when the phase reaches a real checkpoint. Do not invent an implementation file list before a viable contract exists.

## Task 1 — Freeze the problem and resolve the original-consumer topology

**Files:** Create `forward-decision/baseline.md`; read the spec and the canonical collector patch inputs, without editing them.

**Consumes:** Existing reviewed matrix, compiled extracts, acquisition manifest, original acceptance and collector preparation/activation paths.

**Produces:** A source-grounded identity/lifecycle diagram and a finite list of genuinely new questions.

- [x] Record current HEAD, index fingerprint, working-tree delta fingerprints and locations of retained evidence. Do not stage, stash or normalize user work.
- [x] Trace the actual profile's choice between legacy `Symbolizer.StartMonitor` / `kobject.New()` and the owned activation path. Name the socket actually polled, its creating process, owner transfer, joins, Cut and retirement. Patch text is candidate source, not proof of deployed topology.
- [x] Resolve the exact `kobject.New()` implementation from the already pinned dependency source. Record creation/bind/options, goroutines, aliases and close semantics. If absent, recover the pinned source with provenance; do not substitute a similarly named current dependency.
- [x] Identify shared address spaces, authorized tracers, descriptor passing and any pending references. Distinguish receiving the seccomp listener from possessing Q; the former alone does not prove a Q alias.
- [x] Carry forward all unresolved matrix rows without rerunning the old audits. Label facts as source-supported, exact-build-supported, deployment-unverified or unsupported.

**Exit check:** Every ownership statement names its source and its evidence level. An uncontrolled pre-existing Q cannot become solely owned by copying it. A future invocation may create Q in its intended owner from the outset, but must retain the original consumer role and all sources; historical Q identity cannot be retroactively substituted.

## Task 2 — Search once for an observation route that closes the missing facts

**Files:** Create `forward-decision/candidates.md`.

**Consumes:** Task 1 baseline and existing exact-build boundary map.

**Produces:** A finite candidate table, with each obligation either supported by a mechanism or explicitly unsupported.

- [x] Evaluate permitted compositions of whole-function observations and enforceable state invariants on the already matched kernel. Look for new distinguishing evidence, not another confirmation that the known helpers are inline.
- [x] For every proposed composition, name the actual observation point, values available there, coverage of callers/clones/callbacks, and synchronization or lifetime argument. A field snapshot before a mutable branch is not its runtime result.
- [x] Consider another distribution-shipped stock 6.12+ build only if there is a concrete source/build reason its boundaries differ. Before acquiring debug packages, record that reason and its expected distinguishing symbols. Examine at most one such alternative in this phase. No rebuilding a kernel to prevent inlining.
- [x] A different build is a candidate supported platform, not qualification of all 6.12+ kernels. Any narrowing of a previously promised support envelope must be identified for approval, never silently applied. The matched 6.12.117-1 result remains blocked unless new evidence actually changes it.
- [x] For mutable callback or namespace decisions, evaluate whether a complete registration/lifetime ledger plus observations could distinguish actual outcomes without freezing or excluding original producers. If it cannot, retain the blocker; do not assert a closed callback universe from a symbol grep.
- [x] Reject helper-replacement sockets, delivery-based denominators, root-return inference, residual terminals, global pause assumptions and forbidden offset instrumentation explicitly. Custody may be a conditional invariant, not a completed mechanism.

**Research bound:** No native execution. No whole-kernel graph expedition unless a named missing edge can decide a live candidate. Record each examined candidate once. Stop when all candidates fail or one covers every row; do not restart the same search under a new name. New evidence acquisition beyond the one alternative build requires a specific scope decision.

**Exit check:** An inline function's absence does not prove universal impossibility; conversely, finding one useful boundary does not make a complete candidate. Unsupported facts remain unsupported.

## Task 3 — Try to assemble one complete contract before designing custody

**Files:** Create `forward-decision/contract.md`.

**Consumes:** Supported candidate facts, not implementation aspirations.

**Produces:** A row-by-row contract with no hidden acceptance changes, or a minimal separating counterexample that rejects it.

- [x] For each original environment root and relay message, specify independent admission, identity/nesting, actual return and deferred-descendant linkage. Include suppression, kset/callback/subsystem outcomes, construction, tagged/untagged routing, every namespace iteration, no-listener decisions, allocation failures/retries and skb reuse.
- [x] Specify actual traversal B, independently established E(b,Q), every Q terminal and their correlation. Show how eligible inherited clone failure and another recipient's success remain distinguishable; filtering must not reduce the denominator.
- [x] Specify a custody slice only where the candidate needs one: actual original owner, seal-before-alias timing, existing/pending references, required operations, external-privilege premises and all kernel membership mutators. Do not blindly deny operations needed for join, receive, Cut or owned retirement.
- [x] Explain precise protected traversal/Cut ordering, all-CPU/hotplug coverage, missed/recursive observations, checked counters and observer lifetime. Final sampling uses original live resources after actual covered borrowers return; certification follows physical retirement. No global producer drain is assumed from a userspace join or membarrier.
- [x] For each required fact, construct two executions that differ in that fact while making the proposed observations identical. If such a pair remains possible, the contract fails that row. Examples: suppression versus masked loss; missing iteration versus allocation failure; eligible Q failure versus another recipient's success; drop/rejoin between equal snapshots; pending callback versus completed retirement.
- [x] Specify rejecting controls for missing/duplicate returns, wrong Q/generation, alias creation before sealing, kernel mutation during traversal, hidden CPU, observer miss/overflow, held borrower and expired deadline. These are test designs only, not executed evidence.

**Exit check:** GO requires a concrete mechanism and coverage/lifetime argument for every row. One unsupported mandatory row is NO-GO. Do not implement an otherwise attractive custody component to compensate for a failed observation row.

## Task 4 — One scoped review and a terminal decision

**Files:** Create `forward-decision/review.md` and `forward-decision/decision.md`; update the progress ledger after the decision.

**Consumes:** Tasks 1–3 reports and cited evidence.

**Produces:** Exactly one of the following outcomes:

| Outcome | Next action |
| --- | --- |
| Complete permitted candidate survives review | Present its exact platform, process and privilege requirements for approval. Then write a scoped implementation/test plan; no code or native run yet. |
| Candidate would work only after changing a constraint | Name the exact constraint, why it is necessary, what guarantee changes and alternatives. Request a decision; do not assume approval. |
| No supported candidate under current constraints | Stop this branch, retain issue 042 OPEN/UNKNOWN and preserve qualified components. No recurring timing campaign or custody-only implementation. |

- [x] Review the strongest candidate against the distinguishing counterexamples, same-Q requirement and complete source inventory. Use one scoped review; if a delegated workflow is approved, use the configured `reviewer` role without model overrides.
- [x] Resolve concrete findings and recheck only changed claims. Do not duplicate whole-WIP or completed merge reviews.
- [x] Report the recommendation in plain language: what we can build, what it proves, what it cannot prove, and the smallest next approval. Do not present a roadmap as evidence that its missing mechanism exists.
- [x] Validate local links, evidence hashes, document whitespace and backlog consistency. Record source/index fingerprints after the phase; no unexpected mutation may be silently accepted.

## Later implementation and qualification gates — not released by this plan

Only a reviewed GO and explicit approval permit a separate implementation plan. That plan must list actual files and mirrored/generated inputs, regressions that first fail at the intended boundary, pure accounting/identity checks, and focused module tests. It must preserve public protocol/API compatibility and the existing owner/deadline state machines.

After host verification and review, any native qualification needs a separately approved finite case list and exact commands, original identities, pinned artifacts, immutable receipts, unchanged budgets, cleanup/actual-return ownership and stop conditions. Missing attachment, EPERM/EACCES, wrong identity, incomplete coverage or uncertain retirement stops the campaign as UNKNOWN; no automatic retry. Setup remains the agent's job.

The final implementation checkpoint must run the repository-required full build in its supported environment, with focused checks reused only for the same revision. Native success on a single kernel is not a universal support claim. Client/global completeness remains the conjunction of all original lanes, delivery, retention and strict retirement; issue archival is a separate operator decision.

## Immediate recommendation

Approve Tasks 1–4 as a **bounded offline decision phase**, not a broker implementation. The useful outcome is either a complete, reviewable mechanism or a stop decision. Neither outcome requires weakening issue 042 or discarding existing useful work.
