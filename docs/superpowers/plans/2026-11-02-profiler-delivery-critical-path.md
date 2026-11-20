---
document_type: execution_plan
title: "Profiler Delivery Recovery Implementation Plan"
base_revision: b2c9b335720fd0491a8d86917a3b0593b818a4ec
status: "open"
---

# Profiler Delivery Recovery Implementation Plan

> **For agentic workers:** Use the selected scoped implementer/reviewer workflow, sequentially. Steps use checkboxes. This document does not launch implementation, live operations, commits, pushes or worktrees.

**Goal:** Make the NEW profiler work end to end, then deliver four qualified fork-backed images with immutable provenance, real imports, API/CRI checks and persisted acceptance.

**Architecture:** Finish the existing I1/I2/I3 and E1–E4 critical path, not a new architecture. One checked entry owns compilation, namespaces, execution, accounting and the retained session. Recovery establishes actual epoch5 before new host continuation can be implemented.

**Tech Stack:** Python 3.14, existing JVM/Kotlin orchestrator, Linux native directory provider, supported Multipass guest and existing OCI tooling; no new dependencies.

**Spec:** [Approved live delivery plan](../../../.superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/LIVE-DELIVERY-PLAN.md) and [approved design](../../../.superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/DESIGN.md). Their guards, schemas and effect ordering remain binding. This is a recovery schedule over that approved scope, not a replacement authorization.

## Route review — 2026-11-03

The [profiler route and repository review](../../internals/reviews/2026-11-03-profiler-route-and-repository-review.md)
recommends retaining one shared collector/protocol with separate mazewall and
Kubescape adapters. Track collector capability, product integration and historical
recovery/release as separate outcomes. At the current cold-runner checkpoint,
report the actual result, remaining recovery work and measured effort before
expanding into another major recovery subsystem. The review records a concrete
Kubescape storage-schema/version gap and dual-product acceptance criteria.
It does not change existing authority, recovery ordering, source ownership or
qualification requirements, and does not authorize a repository split.

**Operator clarification — 2026-11-03:** `tools/orchestrator` was intentionally
removed because the operator considered it unnecessary. The profiler and its
product adapters do not acquire an orchestrator runtime dependency from this
historical recovery plan. The M2 controller paths below are now historical,
and their removal does not identify or authorize a replacement control plane.
Finish the in-flight bounded persistence corrections, then reassess the next
product-delivery step before extending the recovery framework. Existing held
authority, UNKNOWN state, q5/epoch ordering and live acceptance requirements
remain binding for the historical recovery route. No recovery milestone is
completed or waived by the removal.

## Operator-selected next deliverable — standalone profiler first

The operator selected **Standalone profiler first**: after the current bounded
persistence fixes and scoped re-review, add and compile an ordinary-JVM
`observeHotSpot` example and runbook using the existing collector/API. Historical
recovery and four-image delivery are paused. This is a separately scoped product
outcome, not completion of M1–M5 or a substitute publication route.

The candidate scope is the new `HotSpotObservedExample.kt` beside the existing
strict tooling example, its focused host-safe test and `profiler/HOTSPOT_AGENTLESS.md`.
Change the existing observed integration fixture only if demonstrated necessary.
Keep the public API, collector, dependencies, target ownership and observational/
non-policy guarantees. Resolve actual tooling/test visibility before dispatch;
any necessary build-wiring delta must be stated explicitly. Verification includes
the actual tooling compiler target, meaningful host-safe checks and the required
final `./gradlew build`; fresh native execution is a separate later decision.

The retained controller-removal adjustment and standalone implementation brief
are kept in this plan's `.superpowers/sdd` directory. Native-preflight/recovery
expansion is not the automatic next task. Existing historical authority and
acceptance conditions remain preserved while that route is paused.

## Status and execution map — 2026-11-02

**The NEW profiler is NOT end-to-end usable.** No actual q5/epoch5 or new four-image acceptance exists. Completed components are not delivered functionality.

Reuse the single entry producer, startup/gate/bootstrap/P custody and catalog work, shared accounting/budget, reviewed APP acquisition→compile→owned execution, pure selection tests, and standalone adapter evidence. Latest entry evidence is 35 passing tests, but adapter admission still lacks capture revalidation, class/Enum/method/default and post-pop callback checks. There is no usable adapter handle, actual E/member/session binding, shared R parse/projection/consumer, Reader/module-factory/nested execution or live-v2 runner connection. Do not reopen closed reviews merely to count activity.

The dirty-checkout baseline is entry SHA256 `c919cb1d89987e993234b857e6dd3e375192e492ae920da0634e9e591e99fd41` and entry-test SHA256 `adb5e907943a6229a3353ac6632dd3d5640b993870e2201c4dc10e022e602392`. Their verified 35-test run took 121.233 seconds; this proves the current component revision, not profiler readiness. Retained copies and the test-output observation are under `/tmp/opencode/profiler-resume-dfx7y2wp/delivery-plan-baseline-7zaswhqe/`. HEAD alone does not identify these uncommitted sources.

Paths below are exact aliases, relative to the checkout:

```bash
N=.superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3
T="$N/recovery-bootstrap-correctness-1"
C="$N/candidate-observation-1"
D="$N/image-batch-3-diagnosis-1"
R="$N/recovery-bootstrap-v3"
L="$N/live-profiler-delivery-1"
```

Frozen `R/checkpoint_content.py` retains its selected original identity; do not replace it with a new module. The implementation references are `/tmp/opencode/profiler-resume-dfx7y2wp/i1c-integration-handoff.md`, `i1c-adapter-emitter-contract.md`, `i1c-envelope-acquisition-seam.md` and `i1c-application-emitter-seam.md`. Read those contracts for exact producer/grant interfaces; do not turn their internal guard lists into another delivery plan. Current source, not an older report's pins, governs final qualification.

## Global constraints

- Fail closed; preserve origin, phase, schema, class and actual code/namespace ownership checks. No external-module adoption, AST/code registration bridge or generic validation framework.
- One entry producer, accounting/budget and same-process qualified native provider. Fixture success and empty builtins are NOT ACE containment.
- E acquisition uses an EXTERNAL bounded SETUP control grant, never a self-hash inside E. Debit members before body access. BODY parent has exactly two roles: full-copy 1 and identity 1, the latter zero-read. Reserve checkpoint-role provider authority globally before exposing generic routes.
- Positive unused ancestry allowances are `NEW_SELECTED_LIMIT`, not observed historical usage or retroactive debit. Obtain exact operator selection BEFORE activation, with evidence that generic application acquisition/compilation routes cannot reach reserved TCB targets even through another selected input or a known inode alias. Unknown launcher-inode aliases cannot be claimed denied before qualification records that identity.
- Preserve old4 spent68/68, protected holds and UNKNOWN assignment/journal/T3 state. No UNKNOWN save replay, withdrawn nonce reuse or hold reuse. Guest PID911/start1280 (`unattended-upgrades`) is untouched; boot metadata is not process or hold authority.
- Fresh publication69 needs legitimate existing-control-plane authority and approval. Only validated q5 delivery creates `C/preparation.py`, embedding actual control5 SHA EXACTLY once. Actual epoch5 precedes host-continuation implementation. Preserve frozen C/D/R wherever required.
- Reuse accepted acquisitions/builds/caches/custody; no repeat 83-source acquisition, expensive build ceremony or timestamp restoration. Refresh source pins LAST, from actual final changed bytes.
- Preserve all unrelated dirty/staged work, including staged `CLAUDE.md` deletion and stash `98a473c61a11b5693b6a374ec312771f2361363f`. No staging, commits, pushes or worktrees under this plan's preparation authority. Never touch `GITHUB_TOKEN` or credentials.
- One implementation writer. Only read-only readiness research may overlap it; no Paperclip/orchestrator worktree collision. Primary coordinates; implementer owns scoped changes; reviewer owns one bounded connected-code review per milestone. Batch operator-dependent decisions once an exact reviewable packet exists.

## Milestone 1 — actual cold entry reaches retained checked history

**Owner:** implementer; primary supplies current contracts; reviewer gates integrated boundary. **Dependency:** existing component evidence only. **Files:** modify `T/bootstrap_entry.py`, `T/startup.py`, `T/checkpoint_adapter.py`, `T/live_inputs.py` and their existing tests, especially `T/test_entry.py`. Implement the approved pre-publication files `T/native_provider.py`, `T/live_preflight.py`, `T/epoch_transition.py`, `T/test_epoch_transition.py`, `T/delivery_unbound.py` and their focused tests where required by the cold runner. Modify bootstrap/gate and existing gateway/session tests for retained `advance` composition. Frozen `C/checked_ancestry.py` and original R are inputs, not edit targets. Do not create host-continuation or acceptance code here.

**Interfaces:** existing `load_runtime()`, `build_runner(request, envelope, modules, acquisitions, *, directory_provider=None)`, `main(argv=None, *, directory_provider=None)`, `serve_live(runner, binding, incoming, outgoing)`; `_LiveExecution` owns emitted code. Preserve `live_inputs.prepare(selection, expected_selection_sha256)` and adapter `project_checkpoint`/`_CheckpointLoader.invoke` contracts. Route Reader compilation/module execution through the shared producer rather than enrolling externally created code.

- [ ] **RED:** extend `T/test_entry.py` with one top-level cold-start acceptance case using its disposable `envelope_fixture`, real entry subprocess boundary and selected live-v2 contract. It must reach real checked ancestry/history and return retained-session status through the runner protocol. Use actual selected R projection/export and Reader/module factories, including a nested call. Initial failure must identify the unconnected admission/v2 route, not assert private `answer() == 7`. Fixtures must not address the live store or claim native qualification.
- [ ] **GREEN:** finish remaining adapter admission guards, then connect actual externally funded E acquisition, pre-body member charging, leaf/session binding and reserved checkpoint roles. Connect actual R acquisition→parse→projection→AST compilation→consumer emission; Reader/helper factories and nested callbacks use the SAME producer/accounting. Wire the current `build_runner` v1-only branch to the selected live schema while preserving existing v1 callers. Implement any required native-provider/preflight core against disposable qualification targets before demanding runner success: a missing provider cannot be replaced with a fixture-qualified flag or deferred past the test that needs it. No second compiler or permissive fixture branch.
- [ ] **Pre-publication transition code:** implement `advance(action, owned_request, expected_sha256)` on the same runner for `freeze/install5/deliver5`, including exact phase/action writer permits. Fixtures reject advance before VERIFIED recovery, wrong control/seed/session, duplicate/out-of-order actions and early preparation emission; a positive fixture exercises the retained sequence. This is I3's metadata/session checkpoint only, not actual epoch5. All recovery and transition implementation must exist before M2 final qualification and immutable request construction.
- [ ] **Vertical negatives:** mutate E/leaf identity, substitute module/class/default/callback after emission, request an unselected import/source or exhaust a compile/member grant, and attempt role reuse. Each must fail before the corresponding effect, preserve acquisition owners and prevent retained-history success. Include a nested-call accounting assertion and identity-role zero-read assertion.
- [ ] Run the focused entry suite RED, then GREEN, including real fixture-owned full-copy custody and a zero-byte identity recheck under the same two-role parent. Run all selected Python tests once the pre-publication sources are final. Reviewer checks the actual cold path and these security boundaries, not another isolated emitter unit. Disposable fixture/provider qualification is not qualification of the live guest or its runtime child.

```bash
/usr/bin/python3.14 -I -S -B -m unittest discover -s "$T" -p test_entry.py -v
/usr/bin/python3.14 -I -S -B -m unittest discover -s "$T" -p 'test_*.py' -v
```

**PASS:** one retained cold-start transcript demonstrates the actual NEW entry/runner path, checked history, same-session status and all vertical denials. **STOP:** any adoption bridge, second accounting/producer, missing security check or unexplained effect. An inert adapter handle or more component tests is not this milestone.

## Milestone 2 — qualify the exact live route before spending69

**Owner:** implementer, with read-only readiness researcher; reviewer gates E1. **Dependency:** M1, including the provider/preflight and I3 metadata/session code required by the runner. **Files:** qualify the approved `T/native_provider.py`, `T/live_preflight.py` and matching tests; make only demonstrated qualification fixes in bootstrap/gate/entry. Create the approved `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/RecoveryCommand.kt` and corresponding `src/test/kotlin/io/mazewall/orchestrator/RecoveryCommandTest.kt`; modify existing main/test `RecoveryProcessGateway`, `RecoveryOperationAdapter`, and main `OrchestratorDaemon.kt` for dedicated routing. Read `tools/orchestrator/AGENTS.md` first. All code and focused checks precede the final qualification/request checkpoint below.

**Interfaces:** approved `native_provider.bind(selection, expected_sha256, budget)`, `live_preflight.calculate(selection)` and dedicated `recovery prepare`/`recovery qualify`, not a generic retry route. Runtime child proves its own provider, interpreter/launcher identity, inode and native mappings; another process's receipt cannot qualify it.

Read-only readiness inventory may overlap M1. Actual final qualification and immutable request construction cannot precede the completed code they qualify.

- [ ] **RED→GREEN:** exercise real qualification on disposable targets, then reject wrong child/provider/ABI, unknown launcher aliases, insufficient physical/journal capacity and mismatched authority before live admission. Admit bounded copies, scanner frontier, encoding/readback/fsync and failure reserves against actual resources; enforce the exact funding extension, not raised constants. Preserve original physical and native failure evidence.
- [ ] Consume `T/LIVE-READINESS-2026-11-02.md`, which supersedes the older transport stop for its observed context. Refresh only expiring facts through supported access: guest `mw-hotspot-t5-20261024-d943df28`, boot/DMI/archive/backing identities, control-plane/store configuration and protected outputs. Do not infer T3/hold closure from successful transport or inspect/kill PID911.
- [ ] **Seal only after code is final:** complete all I1/I2/I3 pre-publication implementation and review; refresh measured helper/source pins, then qualify that exact runtime revision and produce the exact selection and immutable qualification/request packet. Include `NEW_SELECTED_LIMIT` decisions and reserved-TCB-target denial proof. Reviewer checks native/process/resource/authority coverage. Submit outstanding operator choices together; do not activate guessed limits. Any subsequent source change invalidates affected qualification/request bindings and returns to this checkpoint before approval or dispatch.

```bash
/usr/bin/python3.14 -I -S -B -m unittest discover -s "$T" -p 'test_*.py' -v
./gradlew :tools:orchestrator:test -PincludeOrchestrator=true
```

Execute E1 through the **registered launcher and exact path/SHA handoff** from the readiness/control-plane packet: `recovery prepare`, then `recovery qualify`. Do not substitute a bare shell command or invented launcher identity. Retain real `T/combined-envelope-69.json.gz` and `T/operation-request-69.properties` exclusively at approved fixed paths.

**PASS:** actual runtime/native/provider/physical qualification and independently reviewed immutable inputs; operator-selected limits admitted before activation. **STOP:** unknown alias/target/authority, inadequate resources or unsupported access. The old Multipass failure is not automatically today's blocker; current evidence decides.

## Milestone 3 — one retained recovery yields actual q5/epoch5

**Owner:** primary/control-plane operator for authorized effects; reviewer gates irreversible dispatch. **Dependency:** M2's final exact-source qualification/request packet. **Files:** execute the already-tested `T/epoch_transition.py`, `T/delivery_unbound.py` and retained gateway/session path; retain the actual fixed-path request, publication and transition receipts. This milestone introduces no new application code. A demonstrated defect returns to M1/M2 review and affected qualification before a fresh legitimate request; it cannot be patched beneath existing approval. No host-continuation implementation yet.

**Interface:** `advance(action, owned_request, expected_sha256)` on the SAME session; closed `freeze/install5/deliver5` actions, exact prior outcome/request/control bindings.

- [ ] Consume M1/M2's exact-revision transition and selected-suite evidence; do not repeat unchanged fixtures merely for ceremony. Check its source/request pins and independent review, then use the existing delegated control plane: `recovery approve-delegated` → `recovery dispatch` → `recovery status`. Revalidate permanent cumulative69 lineage/slot and funding immediately before dispatch; durably spend69 before the one old4 save. Retain complete readback/File9/fsync and VERIFIED then STATUS/LIVE; keep the owning daemon/session alive.
- [ ] Issue separately bound `recovery advance` requests in order freeze→install5→deliver5. Record actual frozen parent, seed, installed control5, delivered q5-only authority and actual epoch5 before opening M4. Only validated delivery creates `C/preparation.py` once with the real control5 digest.

Transition regressions run in M1/M2 with `/usr/bin/python3.14 -I -S -B -m unittest discover -s "$T" -p test_epoch_transition.py -v`. M3's completion evidence is the real control-plane/retained-session transcript and receipts, not another synthetic test run.

**PASS:** actual durable publication69, retained live context, validated q5 delivery and observed epoch5 evidence. **STOP:** timeout, lost session, uncertain claim/save or partial transition is UNKNOWN: preserve everything, no replay/reconstruction. Successful metadata fixtures cannot unlock host implementation.

## Milestone 4 — first useful NEW profiler result

**Owner:** implementer, then authorized execution owner; reviewer gates native execution. **Dependency:** actual M3 evidence, not a predicted control5 hash. **Files:** create approved `L/host_continuation.py`, `L/test_host_continuation.py`, `L/acceptance.py` and `L/test_acceptance.py`; consume `N/batch.py` action sequence and existing host contracts without invoking frozen `Batch.execute`/`host.run` globals or nonces. Complete both host and acceptance code before sealing the fresh workload grant and before the first native workload.

**Interface:** inject actual q5 custody/writer, selected fresh action/image identities, bounded clocks/resources and qualified same-process native authority. Use the approved host-continuation contract; do not create a new service or public API.

- [ ] **RED→GREEN:** fixture the existing qualification fork→observed OCI descendant→stop/cleanup→production fork sequence. Reject stale nonce/attempt4 provenance, absent descendant proof, incomplete captures and unclosed cleanup; keep raw failures as owned evidence. Acceptance fixtures reject a missing image, wrong immutable digest/import target, absent fork proof, failing API/CRI check and unpersisted receipt. Require the four exact image labels and no attempt4 substitution. Finalize/review both code paths and measured identities, then admit a fresh q5 workload grant for all four images, captures, imports and cleanup before native effects. Recovery/D preparation funding is insufficient.
- [ ] Under legitimate fresh authority, run the real qualification/production fork and first `node-final` collect using retained accepted inputs. Retain actual descriptor, independently observed descendant/fork provenance, complete raw captures, archive SHA/File9 and cleanup results. Do not rebuild the accepted 1136-module graph.

```bash
/usr/bin/python3.14 -I -S -B -m unittest discover -s "$L" -p test_host_continuation.py -v
/usr/bin/python3.14 -I -S -B -m unittest discover -s "$L" -p test_acceptance.py -v
```

**PASS — first useful profiler:** the NEW retained q5/epoch5 route produces a real qualified fork-backed `node-final` result with complete provenance/capture/cleanup. This is explicitly NOT four-image delivery or API/CRI acceptance. **STOP:** missing native authority, physical admission, descendant proof or complete capture; do not relabel a file/zero exit as qualification.

## Milestone 5 — all four images accepted and persisted

**Owner:** authorized execution owner for effects; reviewer checks final evidence. **Dependency:** M4's first accepted collection and unchanged, already-reviewed host/acceptance code and workload grant. **Files:** execute the approved `L/acceptance.py` and host continuation; retain actual archives/import/check/acceptance receipts. Acceptance consumes actual M4/q5 receipts and readiness-selected immutable image identities. Code fixes require affected regression/review and grant revalidation before further effects, not a silent change under stale authority.

- [ ] Confirm the M4 acceptance evidence and the exact four labels: `node-final`, `storage-builder`, `storage-final`, `hotspot-overlay`; reuse valid same-revision tests rather than replaying them as delivery evidence. None of the three remaining collects may substitute accepted attempt4.
- [ ] Finish real remaining collects under the admitted four-image grant, preserving the accepted first result. Verify each archive/provenance chain, actually import the exact identities, run API/CRI checks against those imports, and persist acceptance/result receipts through q5. Retain cleanup and failure evidence independently.
- [ ] Reviewer checks complete image→archive→import→API/CRI→persisted-receipt linkage. Report remaining failures honestly; partial output is not delivery.

```bash
git diff --check
./scripts/adkw check-backlog
```

**PASS — delivered:** all four real fork-backed images, immutable provenance, actual imports, successful API/CRI checks and persisted q5 acceptance are linked by exact identities. No test-count, guard completion or synthetic receipt can close this milestone.

## Budget, checkpoints and recovery decision

Planning allocation, **not measured throughput or a completion ETA**: M1 two focused engineer-days; M2 two; M3 one; M4 one to two; M5 one to two. Total **7–9 active engineer-days**, low confidence until M1 and M2 pass. Operator decisions, external service availability and live workload duration are separate waits, not hidden inside that estimate. These allocations are decision ceilings, not permission to leave required checks out.

At M1's first day, report whether the top-level entry fixture reaches the actual runner and its exact first blocker. At two days without PASS, stop serial micro-packets: report the failing boundary, remaining integration work and a revised bounded estimate to the operator. Apply the same rule at each later allocation. If a gate needs new architecture, scope, authority or security weakening, stop and present the exact change needed rather than silently expanding the plan.

Progress reports contain only milestone PASS/FAIL, executable evidence, blocker and remaining work, plus actual effort versus allocation. “35 tests passed” alone is not delivery progress. Batch genuine approval needs with exact immutable artifacts; no routine per-step permission questions.

A delegated return with no changes or pending verification is not task completion. Await the existing check without polling or duplicating it; an unchanged baseline pass proves no new feature. Repeated no-delivery returns trigger reassignment or a bounded integration review, not another indefinite chain of smaller guard tasks.

Reuse the valid JVM full gate for unchanged sources: 233 tasks (104 executed, 4 cached, 125 up-to-date), 13m1s. Run final changed Python suites after source stabilization. If orchestrator/JVM/build wiring changes, run its focused checks and one completion `./gradlew build -PincludeOrchestrator=true`; otherwise do not replay that expensive gate. Preserve project-required verification, exact-source review and final diff/backlog checks. No additional bug issue is needed for already-known remaining delivery work.

**Out of scope:** redesign, dependency upgrades, replaying accepted builds, worktree creation, repository publishing and unrelated cleanup. Authorized recovery/image publication remains within the gated delivery scope above. This document itself performs no BCA, guest, store, approval, save or live operation.
