# MazeWall Integration Foundation Implementation Plan

> Restored from the operator's revised C0–C9/P0–P5/S0–S3/R0–R1 handoff.
> This execution copy retains the revised acceptance contracts, reconciles current
> owner paths, and applies subsequent explicit operator decisions. Original example
> snippets naming retired APIs are baseline probes, not APIs to restore.

**Goal:** Prepare policy and execution contracts, then deliver one bounded upstream
Koog process tool and one synchronous Spring JDBC integration with demonstrably
active restrictions.

**Architecture:** Retain the policy compiler, native installer, diagnostic classifier,
JDK executors and process Portal. Add application-owned lifecycle coordination and
small adapter seams. Spring in-process and Koog process releases are independently owned.

## Authoritative decisions and overrides

- The operator subsequently approved a recovery-aware isolated fleet. Writers use
  private worktrees; the coordinator owns `fast-master` integration and serial gates.
  Continue until all release gates are ready or 12:00 CEST on 8 October 2026.
  Ordinary task/commit boundaries are not pauses.
- Later explicit approval supersedes the original additive migration decision:
  migrate callers and remove obsolete APIs, aliases and compatibility shims.
- Preserve all unrelated pre-existing changes. Stage task-owned files/index rows
  only. Do not push or merge outside the repository review path.
- Shared foundation, Portal/Koog and synchronous Spring/JDBC are separate gates.
- First in-process contract is synchronous Callable bodies plus lexical cleanup.
- Fatal worker failure fails its generation: stop admission, retain cleanup owners,
  require explicit recreation from bootstrap after retirement. No automatic
  in-process replacement until separately proved.
- Stateful Portal loss is terminal; waiting/new work cannot enter reconstructed
  state. Stateless capacity replacement never replays a failed invocation.
- Initial Koog uses the required static worker baseline, not inferred per-tool
  syscall/filesystem policies from `PortalWorkerProfile`.
- Initial Spring supports singleton, proxyable, synchronous methods with approved
  materialized result shapes. Coroutine/WebFlux/JPA/general external processes,
  workspaces and brokers have separate gates.

## Normative constraints and evidence

The consolidated design's section 2 and repository native invariants remain binding.
One canonical compiler; exact BPF layouts/linear scan; JVM coordination syscall
floor; captured native authority/errno/FFM ownership; Landlock before Seccomp;
no virtual-thread installation or TSYNC+NEW_LISTENER; no hidden baseline, permission
unions, caller-runs/shared-worker fallback, unprotected required entry, effect replay,
hot reload, or claims that close uninstalls restrictions. Credential management is
operator-owned. In-process containment is not heap/tenant/hostile-code isolation,
hard CPU/RAM accounting or guaranteed termination.

Keep ordinary monorepo subprojects. Framework dependencies stay outside core/Portal.
Baseline: Gradle 9.6.1, root Kotlin 2.4.20-Beta1, coroutines 1.10.2, serialization
1.9.0, JDK22 core floor, JDK25 integration runtime, Spring Boot4.1.1 BOM and
`ai.koog:agents-tools:1.3.0`. Published artifacts do not prove build compatibility;
transitive Kotlin/serialization differences remain explicit gates. No opportunistic
root/vulnerable-demo upgrades or compiler metadata-skip proof.

References:
- `integrations/2026-10-06-mazewall-integration-foundation-design.md`
- `integrations/mazewall-api-findings.md`
- `docs/internals/designs/enforcer/containment-design.md`
- `docs/internals/designs/enforcer/process-portal-design.md`
- `docs/internals/designs/portal/local-service-isolation-design.md`
- Inspected source `0edb3d79c3e62b5b2f03b9ff9754d40e6cb0a871`
- Review source `98da39f7f71b64a14633ab3f19046f6bc2afc0e3`

## Execution discipline

Each task: meaningful regression/consumer baseline → focused check → smallest
contract change using existing owners → focused/native/packaged checks → scoped
review/evidence/commit. Preserve already-correct behavior without invented failures.
Before public/core changes use `./scripts/adkw blast-radius <Symbol>`, treating stale
indexes as hints. Record issues with `./scripts/adkw new-issue`; validate backlog.
Review public enforcer ABI with `:enforcer:updateKotlinAbi` and
`:enforcer:checkKotlinAbi`; do not accept unrelated changes just to pass.

Use focused owning-module tests during iteration. Run one `./gradlew build` after
focused checks at each coherent checkpoint. Kernel tests use disposable JVMs;
required-feature unexpected skips are not passing evidence. Host/container builds
in the shared checkout run serially. Kernel/endpoint evidence remains fresh.

Current evidence and partial/completed task status live in
`integrations/2026-10-06-inline-execution-status.md`; the contracts below are not
completion claims. Names map to equivalent current owners rather than duplicate engines.

# A — Shared Foundation

## C0 — Reconciliation and external consumer baseline

Owners: API review reconciliation; Java/Kotlin consumers; design/plan corrections.
- Reproduce original uncast Java overload ambiguity independently of dependency
  resolution; compile quick starts and record obsolete signatures.
- A01–A12 register: current source owner, meaningful assertion, command, evidence;
  caller-thread assertions are not worker-placement proof.
- Record non-ancestor snapshots, renamed owners, exact resolved dependency/JDK
  manifest and scoped backlog. Consumers resolve published artifacts, not sources.
- Verify stable compiler/JDK22/JDK25 and eventual adapters; no prerelease-skip flags.
Gate: source/findings/dependencies/consumers are accountable before adapter claims.

## C1 — Filesystem intent, reconstruction and snapshots

Owners: PolicyDefinition/builders; current `FileSystemPolicy` equivalent of planned
`FileSystemIntent`; policy/serialization tests.
- Unrestricted/DenyAll/AllowResources in the canonical definition, not another model.
- Active-empty intersection remains active through base/template/thread snapshots,
  reconstruction and serialization. Never infer intent solely from nonempty paths.
- Freeze permissions/diagnostics; test Java mutation attempts, unrestricted vs
  deny-all, idempotence, commutativity and metadata-independent permission algebra.
Gate: active deny-all cannot reconstruct as unrestricted.

## C2 — Fixed scopes and truthful setters

Owners: ProcessPolicyBuilder/WorkerPolicyBuilder and canonical compiler.
- Explicit allowList/denyList(runtime), immutable definitions, existing JVM floor.
- Worker filesystem mutators retain the same receiver even when return is ignored;
  fluent/imperative/apply/Java parity. Process scope has no filesystem mutators.
- True pre-install Boolean assignment including false-after-true executable mapping.
- Negative packaged process-scope compile fails for the expected missing method.
- Migrate and remove legacy scope-promoting builders per later operator approval.
Gate: construction forms produce equivalent fixed-scope restrictions.

## C3 — Captured required installation

Owners: ContainedExecutors, assessment/receipt, captured InstallationEnvironment,
Landlock capability/state and native engine.
- Capture native/capabilities/runtime/fallback once; required mode FAIL.
- Require installed receipt and required filesystem coverage before target entry.
- Missing/partial/weaker feature and global-replacement cases have zero target calls.
- Validate final runtime policy/JVM floor; reject impossible required configurations.
- Preserve irreversible progress, original cause/fatal identity and quarantine.
- Receipt close releases registration, never filters. Test virtual rejection and
  native partial failures in disposable JVMs. Correct CET proof needs pre-enabled
  compatible runtime; no unsafe late-enable or invented positive evidence.
Gate: no required protection failure reports protected-ready.

## C4 — Precise prepared resources and filesystem recipes

Owners: runtime resource resolver/PreparedResources equivalents, builders,
ProcessPolicies, Landlock and native boundary tests.
- Exact artifact grants, explicit runtime/class/resource directories; unresolved
  required artifact fails rather than allowing its parent.
- Prepared identity/generation and actual owned descriptors remain pinned through
  every required worker setup; replaced artifacts/symlink switch require revalidation.
- Explicit read/write/truncate/create/remove/replace/rename rights and required ABI.
- Reject writable overlap contradicting explicit read-only recipes. Future files
  use reviewed existing output directories.
- Native sibling-secret, Boot/exploded-class loading, symlink replacement, mutation,
  unlink/rename and preopened-descriptor outside-boundary fixtures.
Gate: allowed artifact works, sibling denied; no silent widening.

## C5 — Profile/runtime conversion and truthful guidance

Owners: ProfilingResult, PortalWorkerPolicyTest and primary README/getting-started
recipes/current facade KDocs.
- Build profiled component using selected runtime before restrictive composition;
  combined HotSpot mapping remains true, Native Image false. Reject contradictions.
- Preserve empirical coverage; use result.toDsl()/toPolicy() signatures.
- Correct defaults, offloading, irreversibility and diagnostic-certainty prose;
  no exception-class-as-proof or exact-future-behavior claims.
- Compile snippets and run fresh-JVM profile-backed JIT work.
Gate: composition preserves runtime requirements and examples preserve coverage.

## C6 — Trusted reviewed artifacts and exact bindings

Owners: current CanonicalPolicyArtifact, OperationBinding, OperationPolicyDocument,
ReviewedOperationArtifact, strict codecs and owned preparation.
- Format1 binds operation/domain/adapter/target/JVM method descriptor, policy and
  implementation SHA-256, runtime, resource generation and covered phases.
- Strict security fields: reject unknown versions/fields, stale hashes, missing
  external approval, wrong descriptors and unresolved required resources.
- Round trip active deny-all, coverage and specific approved exceptions through
  canonical construction. Candidate collection is not trusted approval.
- Resolve actual implementation/classloader/resource identity, not just metadata.
- Reviewer expectations outside hashed implementation. Boot manifests are sidecars
  to finished JARs. Digest is byte identity, not reviewer authenticity.
Gate: no substituted stale implementation/resource generation/unapproved contract.

## C7 — Owned synchronous runtime

Owners: current api.execution ManagedRuntime/InProcessDomain/InvocationHandle/
DomainLimits/DrainReport and internal owned worker/generation/lease owners.
- DomainLimits(workers,maxAdmissions); minimal result()/executionEnd() stages;
  requestCancellation returns REQUESTED or ALREADY_ENDED.
- ExecutionEnd.NotStarted(failure), Finished(failure?); Complete/Incomplete drain
  retains live executions and unfinished cleanup.
- Explicit bootstrap and permanent baseline; prepare(policy,limits,timeout), submit
  Callable, drain(timeout), close. No starter-installed hidden process baseline.
- Fixed JDK ThreadPoolExecutor, finite admission, AbortPolicy, platform threads with
  inherited ThreadLocals disabled. All workers installed before admission.
- Bootstrap failure cannot strand JDK bookkeeping; retire preparing pool.
- Fatal generation fails, replacement forbidden from restricted workers/callers;
  explicit recreation after retirement from retained bootstrap authority.
- Finally-based physical end, not Future.isDone. Cancellation retains lease and
  known cleanup. Owned bounded result delivery outside transaction execution.
- Tests: readiness/failure zero-entry/saturation/independent runtimes/cancellation/
  incomplete drain/fatal generation/native caller placement/recreation.
Gate: no false readiness, caller contamination, growth or premature end.

## C8 — Exact nesting, context and observed evidence

Owners: transition guard, InvocationEvidence/context bridge, ManagedRuntime.
- Dispatch/InlineCurrentWorker/Reject from registered runtime/domain/generation,
  not caller labels. Same domain consumes no new permit; cross-domain rejects.
- InvocationContextBridge.capture(): snapshot; snapshot.run(Callable) restores only
  declared metadata in finally. No connections/request/all-ThreadLocal copying.
- Retain observed/inferred diagnostics and caught recorded inner failures.
- Reject drain/close from runtime's own worker. Separate permission classification,
  caller delivery, actual completion and external effects.
Gate: supported nesting preserves physical placement/evidence without deadlock/unions.

## C9 — Canonical Callable facade and recipes

Owners: Mazewall.callContained(runtime,domain,Callable), external Java/Kotlin probes,
primary guidance; remaining obsolete borrowed wrappers/factories.
- Uncast Java lambda compiles against actual artifacts; checked/fatal causes retain
  identity when execution wrappers are unwrapped.
- Required owned execution, bootstrap/offloading/permanence/capacity/timeout guidance.
- Migrate and remove obsolete overload/facade surfaces per later operator approval.
Gate A: C0–C9 plus applicable native and packaged consumer proof.

# B — Portal and Koog

## P0 — Runtime startup/shutdown ownership

Owners: PortalRuntime, pure runtime lifecycle and broker ownership.
- Barriers after lookup admission/before startup publication; close then release,
  no unowned broker. Register acquisition before construction/start.
- Failed first-close owner remains for exact second-close retry; no premature Closed.
- Pure lifecycle decisions/interpreted effects, fatal-first rollback; runtime-facade
  integration as well as broker fixtures. Focused PortalRuntime tests.

## P1 — Atomic stateful loss and mode-aware replacement

Owners: stateful admission gate, ProcessBroker, PortalRuntime.
- A admitted/B waiting or overlapping; unknown A completion means B never enters
  replacement. New calls reject; completed business errors preserve availability.
- Publish state loss atomically before releasing admission. No automatic singleton
  reconstruction; recreation needs new explicitly prepared runtime/group generation.
- Stateless capacity replacement never replays failed work. Host and real process
  timeout/crash/fatal retirement/cleanup checks.

## P2 — Public bounded prepared operations and cancellation

Owners: PortalRuntimeLimits (equivalent options), PreparedPortalOperation,
PortalRuntimeConfig/Runtime, broker and channel.
- Public configuration proves worker count, logical admissions (separate from idle
  queue), call/startup/close budgets, request/reply bytes and capability counts.
- `PreparedPortalOperation<A,R>.submit(arguments): InvocationHandle<R>`.
- Public consumer tests, saturation, cancellation before checkout and after request
  progress, execution end pending through child reaping and known cleanup.
- One absolute RPC deadline spans waiting/checkout/bytes/FDs/reply. Reject response
  headers before body allocation. Not-started/completed/unknown are distinct.
- Cancellation delivery does not establish termination. Keep failed/cancelled exact
  worker owner and execution-end until reaping/known retirement is accounted for.
Gate: bounded public asynchronous ownership, not only internal broker options.

## P3 — Required generation-bound launch/readiness

Owners: PortalLaunchContract/ReadinessContract equivalents, broker/worker bootstrap.
- Readiness binds session/expected PID/protocol/manifest/artifact generation/runtime/
  policy/required seccomp/filesystem coverage/required parent resource controls.
- Required startup control exchange before guest construction/dispatch; legacy
  sentinel separate, no fallback if required record expected.
- Missing features/wrong generation/early record/helper threads/cancelled startup;
  delayed cgroup attachment cannot precede required parent setup.
- Landlock first; ABI8+ all-thread filesystem. Diagnose known conflicting host
  process-creation policies including arguments; do not reject every external filter.
- Explicit operator-owned launch profile, synthetic host-only environment markers
  and unexpected descriptors; classpath separation is not environment separation.
  Never inspect/filter/manage operator credentials.
- Worker startup host tests and RequiredWorkerReadiness real integration.

## P4 — Portable opaque worker bundles

Owners: PortalWorkerArtifactCatalog, resource materializer, CodegenPlugin and
existing metadata publication task, functional packaging tests.
- Format2 indexed classpath resources plus SHA-256; format1 only explicit local-file
  migration where still required, no silent production fallback.
- Bounded transactional materialization before launch; preserve digest/schema cache
  identity and existing bounds. Worker bundle is opaque data, no loadable host guest.
- Package host/remove original build-directory access/run from fresh directory.
- Missing/duplicate resource/hash/traversal/oversized archives/symlink-cache probes.
- Worker dependencies from service/worker source set, not host Koog/Spring closure.

## P5 — Upstream Koog tool and clean consumer

Owners: integrations/koog PortalToolAdapter/tests; demos/koog-contained-tool/service
contract + portalWorker implementation; settings/version catalog.
- Koog1.3.0 Tool<A,R>/typeToken and actual registry. Thin suspend execute delegates
  prepared Portal operation; await result via suspendCancellableCoroutine and forward
  cancellation. Never run protected worker code on host dispatcher.
- Deterministic first-party text transformer; 64KiB arguments/results, independent
  128KiB wire budgets. Static required worker baseline.
- Actual registry/validation/cancellation/output/terminal-session probes. Test-only
  denied-resource service through same adapter, excluded from advertised registry.
- Host mappings explicitly outside protection; model-supplied operation/policy
  selectors confer no authority. No local fallback or replay.
- Opt-in includeKoogIntegration=true; owning tests and external packaged worker.
Gate B: applicable foundation + P0–P5/publication/native proof.

# C — Synchronous Spring/JDBC

## S0 — Prove actual advisor-chain placement first

Owners: optional spring module BoundInvocationInterceptor/AdvisorPlacementTest.
- ProxyMethodInvocation.invocableClone(), Callable clone.proceed() on owned worker;
  never raw target. Existing singleton target invoked exactly once.
- Record dispatch/transaction begin/body/authorization/diagnosis/completion threads.
- Actual advisor positions including nested proxies; dispatch outermost, transaction
  next, diagnosis/security inside transaction completion. Reject unproved chains.
- includeSpringIntegration=true focused host placement test before starter completion.

## S1 — Exact approved operation binding and lifecycle

Owners: MazewallOperation, SpringOperationBinder, AdvisorOrderValidator,
conditional auto-configuration/imports/properties, DomainLifecycle/binding tests.
- Actual bridged target methods/JVM descriptors and bean identity separately from
  class. Missing manifest/unmatched required operations/two same-class beans/
  overloads/final-unproxyable/lazy-scoped/multiple-context/advisor-drift tests.
- Approved synchronous materialized shapes only. Reject suspend/Future/Publisher/
  Flow/stream/lazy-resource/unapproved opaque results.
- Explicit prepare lifecycle before admission, no global baseline. Required inactive
  protection fails; explicit dev-unprotected never claims protected readiness.
- SmartLifecycle bounded drain; retain/report unfinished owners.

## S2 — JDBC placement and honest transaction effects

Owners: SpringTransactionGuard, TransactionalFailureBridge, SpringTransactionEffects,
placement/outcome host/JDBC/native tests.
- DataSourceTransactionManager/JdbcTransactionManager, one qualified manager.
- All seven propagation modes on same physical worker; NESTED only with savepoints.
- Reject external active tx/synchronization/bound resources before hop. Same-domain
  inline nesting; unsupported cross-domain rejects.
- Classification/evidence inside tx advice; diagnosed failure preserves original
  cause/evidence and fails protected call. beforeCommit gates recorded evidence,
  beforeCompletion is not rollback veto. Reject incompatible effective rollback rules.
- Committed/rolled-back/unknown outcomes distinct from delivery; caught recorded
  failures persist. No detection claim for unobserved swallowed ERRNO.
- After-commit error remains committed; previously committed inner tx remains so.
  Actual JDBC fixture/native denial; generic EACCES remains inferred.

## S3 — Optional Security, normal starter and Boot consumer

Owners: conditional SpringSecurityContextBridge/config, reuse/method-security tests,
thin starter and clean spring-boot-sandbox demo.
- Supported delegation captured per invocation. Alternate principals on one worker,
  restoration after success/denial/exception/cancellation. No linkage without Security
  and no changed authentication defaults.
- Ordered post-authorization rollback; incompatible ordering/rules reject.
- Integration/starter normal JARs, demo executable Boot only. External trusted sidecar
  next to finished JAR. Read reviewed template/write report/JDBC/template-mutation
  denial; virtual servlet caller offloads to platform worker.
- File/network effects separate from JDBC rollback. Optional classpath and native
  packaged Boot tests; owning host suite.
Gate C: foundation + S0–S3/publication/native proof.

# Distribution and Adoption

## R0 — Pinned published external consumers

Owners: advertised module publishing, tests/consumers/{spring-jdbc,koog-portal},
check_integration_consumers script/CI/compatibility manifest.
- Clean local staging prerelease; consumers no sibling sources/test-output/original
  build-directory access. Java/Kotlin core JDK22/JDK25, adapters JDK25 exact graph.
- Default/Koog-only/Spring-only/combined flags, portable bundles and Boot sidecars.
- Native jobs verified TSYNC-capable host; unexpected required skips fail gate.
- Configured release repository verification uses operator-managed credentials.
- Full merge gate once after focused checks with applicable integration flags.

## R1 — Boundary, usability and measured overhead evidence

Owners: integration-release-evidence review, benchmarks/integration-boundaries,
clean demos and primary tested guidance.
- Allowed work/sibling denial/template mutation/unsupported-host/independent owners/
  cancellation/state loss/tx outcomes; outside-boundary preopened FD/shared heap/
  independent executor/initialization/late async/external effects.
- Developers unfamiliar with MazeWall classify real permissions and limitations;
  this human gate cannot be invented or substituted by agent self-review.
- Measure preparation/install/queue/dispatch/task/process startup/IPC/throughput/
  memory/threads/rejection/drain against equivalent direct and JDK dedicated work.
- Retain raw data and environment/artifact identities. No invented thresholds or
  negligible-overhead claims; reject false readiness/widening/unsupported claims.
- Backlog validation and scoped evidence commit. Operator/infrastructure-dependent
  gates are reported explicitly, not marked done from unavailable evidence.

## Deferred gates

Coroutine: leases through suspension; no unrestricted continuation/cleanup fallback;
overload/drain/fatal-worker tests before bounded rejecting continuation executors.
Existing coroutine facade remains experimental pending that separate gate.
Spring suspend/WebFlux: actual suspend completion/event-loop/cancellation/context/
transport-client-engine limits. JPA: provider flush/lazy/Open-in-View/callback/database.
Workspaces: stable diff/conflict/apply-discard/recovery. Credential/operation broker:
session authority/secrets/normalization/budgets/revocation/unknown effects.
General external processes: launch/FDs/environment/descendants/signals/controllers/cleanup.

## Traceability and final acceptance

A01 C0/C2/C5/C9; A02 C2; A03 C4/P4/R0; A04 C1/C2/C6; A05 C7/C8/C9;
A06 C3/C7; A07 C3/C5/P3; A08 C5/C6; A09 C5/C8/S2/R1;
A10 C0/C9/R0; A11 C0/P4/R0; A12 C8/P5/S1–S3/R1.

- Every finding: current evidence and meaningful regression.
- Frozen canonical filesystem intent and actual required install.
- Finite domains/honest execution and drain ownership.
- Portal startup/cancellation/state-loss/cleanup ownership.
- Portable worker metadata and isolated dependency closure.
- One bounded host-approved Koog operation, no fallback/replay.
- Existing Spring beans/advisors/same-worker JDBC/optional identity.
- Actual pinned external Java/Kotlin artifacts and native proof without mock/skip substitution.
- Public guidance matches measured boundaries.
