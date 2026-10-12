---
document_type: execution_plan
base_revision: 41ed2b999ad316a0f7e5e6548d49bd9b748d28f7
---
# Integration Contract Completion Implementation Plan

> Agentic execution: use subagent-driven-development, fresh task owners and scoped independent reviews. Preserve the supervised agent demo's custom scoping behavior: explicitly approved by the operator on 11 October 2026.

**Goal:** Implement the six approved follow-up milestones and verify a new exact-source candidate, retaining honest feature/operator prerequisites.

**Architecture:** Extend existing owned-runtime and Portal launch owners; preserve the canonical compiler, native installer, process authorization and physical-cleanup authority. Use explicit immutable context/launch contracts, existing JDK executors and existing artifact consumer workflows. No second policy engine or general process backend.

**Tech stack:** Gradle9.6.1; producer Kotlin2.4.20-Beta1; stable consumer Kotlin2.4.20; JDK22 core/JDK25 integrations; coroutines1.10.2; core serialization1.9.0; Koog1.3.0; Boot4.1.1; existing Spring/JDBC/H2 dependencies.

**Spec:** Operator-approved six-step roadmap in this conversation; original normative `integrations/2026-10-06-mazewall-integration-foundation-plan.md` and design in the repository root (operator-owned untracked inputs; read only); accepted gap manifest in the prior checkpoint's task-9-draft; module design documents. Original contract text is authoritative over stale completion prose.

## Global constraints

- Isolated branch `feat/integration-contract-completion-20261011`, worktree `.worktrees/integration-contract-completion-20261011`, base41ed2b9. Root dirty tracked/untracked work is operator-owned; no reset/clean/whole-tree staging or mutation there.
- Prior tested producerdc6f7bf4 and artifact0.1.0-candidate.dc6f7bf4 retain their identities. Changed sources need a new candidate coordinate and new strict locks, never overwrite historical receipts.
- Preserve supervised custom scoping, its launch-compatible process baseline, original cause/fatal identity, required-failure zero-entry and physical retirement.
- Fail closed; no permission unions, hidden baseline, caller-runs/shared-worker fallback, swallowed EPERM/EACCES, replay, hot reload, or claims close removes filters.
- Landlock before Seccomp; exact Linux layouts and immediate errno; never install on virtual/carrier threads; never TSYNC+NEW_LISTENER; preserve JVM coordination floor and linear BPF design.
- Credentials are operator-owned. Never inspect, filter or manage GITHUB_TOKEN. Environment/FD tests use synthetic markers and actual acquired resources; profile authority is explicit rather than credential sanitation.
- Framework types stay outside core. No dependency/version upgrades. Only approved obsolete APIs may be removed after all callers migrate; preserve internal experimental-coroutine dependencies.
- One source writer at a time in the new worktree. Controller owns plan/ledger/root preservation; independent reviewers do not mutate source. No nested delegation.
- All Gradle/native/publication/commit commands use the existing shared verification.lock; internally locking consumer scripts are not wrapped in another flock. Use healthy finite commands/services with logs and actual exit receipts; do not interrupt for context rotation.
- Focused checks first; one coherent full combined completion gate after source changes stabilize. No repeat of unrelated historical suites or gate waivers.
- Task WIP receives scoped review before coherent source checkpoint; commits use Leanid Piliptsevich <pilleo19@gmail.com> and normal scripts/hooks, no bypass. No default-branch push or remote publication without operator-managed release authority.
- Missing hardware/assets/production approvals/remote access/human participants remain explicit prerequisites. Complete locally implementable work independently.

## Task 1: Truthful owned-runtime and profile recipes

Files: README.md, GETTING_STARTED.md, enforcer/README.md, profiler/README.md, docs/internals/reviews/2026-10-10-integration-final-acceptance.md; the four resolved issues with eight YAML trailing spaces; existing external snippet fixtures under tests/consumers/.
Consumes: current ManagedRuntime.open/prepare/submit/drain, Mazewall.callContained Callable facade, ProfilingResult.toDsl/toPolicy/toProfileDocumentJson.
Produces: compiled canonical recipes and accurate diagnostic/lifecycle/coverage guidance; no borrowed quickstart or incomplete-profile enforcement recipe.

- [ ] Inventory actual snippets and capture representative pre-change compilation failures against existing owning coordinates, including nonexistent public allowIncomplete signatures.
- [ ] Make owned runtime/explicit bootstrap/Callable the primary synchronous recipe; describe admission, timeout versus physical end, bounded drain and permanent process restrictions.
- [ ] Preserve measured coverage via result-level export; select HOTSPOT_JIT before restrictive composition, retain incomplete refusal and empirical limitations.
- [ ] Correct observed versus inferred diagnostics and unsafe unconditional thread-local executable-mapping claims; distinguish a host that launches Portal workers from a no-process-creation application.
- [ ] Correct historical applied-state prose without rewriting producer labels; remove eight YAML trailing spaces.
- [ ] Compile actual corrected snippets using published module artifacts, stable Kotlin and actual JDK22/25. Use existing consumer mechanisms; no source substitution/compiler skip flags. Validate links, whitespace and backlog.
- [ ] Create/reuse one narrowly scoped backlog issue and report exact changes/commands/results for scoped review.

## Task 2: Required operator-owned Portal launch profiles

Files: portals/runtime PortalRuntimeConfig.kt, ProcessBroker.kt and launch/readiness owners; platform/process JvmChildProcess.kt and ProcessLauncher.kt; portals/worker startup owner/tests as required; owning design docs.
Consumes: existing required generation, cgroup-before-permit authorization, actual child owner and strict readiness wire contract.
Produces: explicit immutable supported launch profile, required unsupported-profile rejection and real marker/descriptor readiness/cleanup proof.

- [ ] Read P3 and nearest guides; choose the minimal operator-owned environment/descriptor contract at existing launch boundary. Do not implement a credential sanitizer or general external-process API.
- [ ] Add meaningful failing host controls for missing/unsupported/mismatched required profiles and setup ordering; approved default semantics must be explicit.
- [ ] Capture launch authority before child launch, enforce setup before parent authorization and guest construction, bind profile identity with independently checked evidence where required. Version changed wire schemas; no permissive old-record fallback.
- [ ] Prove named approved synthetic marker present/host-only marker absent; descriptor canary checked by resource identity. Negative control first proves unwanted acquired descriptor observable; JDK default closure alone is insufficient.
- [ ] Test cancellation/expiry/setup failure and actual child reap/pump/socket/cgroup cleanup, including retained owner on retryable failure. Preserve genuine cgroup ordering and fail unsupported required profiles before guest entry.
- [ ] Run focused owning unit/native tests, no unexpected required skips; keep production launch/cgroup approval separate. Update P3 guidance/backlog and provide scoped review package.

## Task 3: Framework-neutral declared invocation metadata

Files: enforcer/api/execution ManagedRuntime.kt and a minimal explicit bridge/snapshot type; internal/execution OwnedInvocation.kt only if necessary; OwnedRuntimeTest.kt/OwnedRuntimeNativeTest.kt; existing Spring bridge conformance tests and design docs.
Consumes: exact registered nesting, finite admissions, owned Callable execution/evidence and finally physical end.
Produces: explicit opt-in capture-before-admission immutable declared metadata and finally restoration across dispatch/inline invocation.

- [ ] Read C8 and existing Spring bridge; keep existing Callable signatures and no-transfer default. Capture outside lifecycle locks, never on manager threads; no automatic ThreadLocal copying.
- [ ] RED tests alternate A/B on one real owned worker, queue then mutate caller, exact nested outer/inner/outer with one admission, and queued cancellation without body/context application.
- [ ] Decorate existing owned Callable with captured snapshot; framework types stay outside core. Avoid another executor, registry or lifecycle engine and avoid double capture in Spring.
- [ ] Verify success/failure/cooperative cancellation restores before reuse; held restoration retains physical end/admission; fatal and restoration failures preserve cause/fatal identity and prevent unsafe generation reuse.
- [ ] Prove undeclared ThreadLocals do not transfer and reuse actual Spring transaction/resource/Security controls. Preserve Security's context-reference qualification; do not advertise deep immutable authentication capture.
- [ ] Run focused core/native/affected Spring checks, intended ABI checks and documentation/backlog validation; scoped review.

## Task 4: Borrowed caller migration and controlled public cutover

Files: ContainedExecutors.kt, ContainedExecutor.kt/view and necessary internal wrapper; CLI/web/agent demos and shutdown/profile lifecycle owners; enforcer host/kernel tests, enforcer.api, external Java/Kotlin consumers and owned-runtime design docs.
Consumes: canonical owned runtime/context contract and corrected recipes; preserved custom supervised policies.
Produces: all migrated synchronous callers and retirement of obsolete public wrap/wrapTyped/single/fixed/cached factory APIs; experimental coroutine dependencies remain supported.

- [ ] Inventory callers/ABI and run blast-radius before public changes; map bootstrap, admission, result delivery, cancellation and shutdown owners.
- [ ] Preserve agent web/analysis custom StacktraceScopingPolicy handlers and deliberately process-launch-compatible baseline. Add the smallest explicit captured scoping seam to owned preparation if required; no default-policy substitution.
- [ ] Migrate ordinary callers and lifecycle owners without adapting ManagedRuntime into ExecutorService. Move execution-contract tests to owned runtimes; installer/supervisor controls retain actual acquired disposable owners.
- [ ] Preserve RequiredTaskEntry zero-entry controls, native cause identity/quarantine and supervisor physical cleanup; run custom agent allowed/denied scenarios as real proof.
- [ ] Remove approved obsolete public signatures/types after last caller migration; retain internal wrapper still used by SandboxDispatcher and separately gated coroutine adapters.
- [ ] Review intended updateKotlinAbi/checkKotlinAbi changes and exact bytecode; add per-signature external negative compilation diagnostics alongside uncast Callable positive JDK22/25/stable Kotlin probes.
- [ ] Run focused changed demo/core/native/affected Spring controls; update docs/backlog and scoped review.

## Task 5: End-to-end native proof and deterministic triage fixture

Files: profiler integration owning PortalWorkerPolicyNativeTest.kt (new if appropriate), existing PortalWorkerPolicyTest.kt/ProfilingResult.kt only if a proven defect requires it; enforcer OwnedRuntimeNativeTest.kt and adjacent fixtures; buildSrc RootVerificationFunctionalTest.java; relevant design/evidence docs.
Consumes: real profiling result coverage and selected-runtime conversion; owned runtime and independent acquired executor; current configuration-cache-safe triage collector.
Produces: real profile-to-install-to-post-install-JIT evidence, independent-helper/late-callback boundary examples, deterministic delayed-event control.

- [ ] Profile real workload and retain measured coverage in a disposable JVM. If selected backend is incomplete, preserve refusal and diagnose actual supported scope rather than manufacture completeness or waive conditions.
- [ ] Convert with HOTSPOT_JIT selected before composition, install restrictive generated worker policies, perform actual post-install JIT/lazy work, and record required installation/result/physical end. Preserve Native Image mapping-denial and incomplete-profile negative controls.
- [ ] Acquire separate JDK executor and explicitly late callback; prove body denies the same filesystem operation they perform outside its restricted domain. Record actual placement and end clocks; release every acquired owner.
- [ ] Replace Thread.sleep(1500) delayed-event fixture with bounded wait-entry/event-delivery handshake; prove first/reused configuration-cache paths and visible handshake timeout, retaining genuine failed Test/collector and primary failed exit.
- [ ] Run scoped host/native/build-logic controls, update evidence/docs/backlog and independently review each independently rejectable proof slice.

## Task 6: Equivalent-work measurements and new exact candidate

Files: benchmarks/integration-boundaries bounded standard-library harness/raw samples; existing consumer scripts/coordinate-namespaced locks; compatibility/new acceptance records and task issue closures.
Consumes: stable reviewed Task1–5 source, existing ManagedRuntime/PreparedPortalOperation/PortalInvocationTiming and publication workflows.
Produces: raw equivalent-work data and a fresh verified source-bound local candidate, without overwriting previous artifacts.

- [ ] Implement small bounded harness comparing identical results/work/concurrency for direct, dedicated JDK and owned runtime/Portal execution. No added dependency, thresholds or negligible-overhead claims.
- [ ] Self-check equivalent result digests and timing accounting; retain preparation/install/queue/dispatch/task/startup/IPC, throughput, memory/threads/rejection/drain raw data, environment/artifact identities and physical cleanup.
- [ ] Freeze reviewed sources and perform one coherent full combined Koog/Spring build using existing two-stage Koog bootstrap workflow. Use actual exit/XML/coverage; required skips are prerequisites, not passes.
- [ ] Create normal-hook source checkpoint on isolated branch with required identity, obtain actual producer SHA and use its new candidate coordinate.
- [ ] Publish locally and verify strict actual Java/Kotlin/JDK22/25 consumers, independent packaged/native Koog and finished Boot/native JDBC; run exact-coordinate Security workflow where claiming that feature. Generate new locks then verify them unchanged.
- [ ] Bind all artifacts/results to exact producer; record raw benchmark samples and caveats; review final complete diff and close only evidence-supported task issues.

## Task 7: Feature/operator prerequisite reconciliation

Files: new acceptance/prerequisite checklist and compatibility record; no credentials or operator configuration mutations.
Consumes: actual local capabilities/assets, required-feature test conditions and final candidate.
Produces: executed applicable feature proof and explicit unavailable prerequisites with concrete operator handoff.

- [ ] Inspect only documented noncredential prerequisites: pre-enabled SHSTK runtime, installed TierE/HotSpot assets/native opt-ins, delegated cgroup root and exact-coordinate Security applicability.
- [ ] Execute genuinely available selected feature gates under required conditions; never late-enable CET or weaken unsupported-feature checks.
- [ ] Record production reviewer/launch authority, remote repository access and independent human exercise as fulfilled only when actual operator-provided authority/participants/receipts exist. No remote publish, credential inspection or fabricated human review.
- [ ] Complete remaining locally implementable work despite unavailable prerequisites; final report states each exact blocker and next operator action.
- [ ] Preserve evidence workspace and worktree until operator integration/review path finishes; no push/default-branch merge or destructive workspace cleanup.
