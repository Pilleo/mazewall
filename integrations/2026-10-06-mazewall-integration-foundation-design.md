# MazeWall integration foundation — consolidated design

**Date:** 6 October 2026\
**Status:** Approved design under inline implementation; adapter/release gates pending\
**Source baseline:** MazeWall API usability and security-contract review, 1 October 2026, source snapshot `98da39f7f71b64a14633ab3f19046f6bc2afc0e3`\
**Scope:** Consolidate the existing API, establish minimal shared integration contracts, ship narrow Koog and Spring adapters, and leave larger capability features behind separate gates.

## 1. Goal, motivation, and evidence boundaries

The goal is **correctly understood, demonstrably active restrictions**. A developer should select a reviewed resource contract, bind it to an operation, and either run with its required guarantees or receive a precise failure before protected work begins. A shorter expression or successful configuration call is not sufficient. This adopts the supplied review's executive recommendation. [R, Executive recommendation]

The source review is not a complete vulnerability audit. It did not run the MazeWall build, kernel tests, packaged consumers, or end-to-end exploit tests. Its independent Java overload probe was not a MazeWall build. Each A01–A12 finding must be reconciled with the implementation branch and reproduced or marked already fixed with evidence. “P0” retains the review's release-priority meaning; it is not a claim that every finding independently establishes exploitability. [R, Scope and confidence]

The numbered API findings are source-derived. The execution-domain model, migration choices, adapter scope, task boundaries, capacity examples, release gates, and future-feature ordering below are **this plan's design decisions**, informed by the conversation and the primary references. They are not claims that those APIs already exist. The Koog feature roadmap is the user's pasted proposal, not measured product demand or evidence of novelty.

### Motivation by audience

For MazeWall users, remove silent rule loss, unexpected privilege breadth, and false protected readiness. For Koog, make selected first-party JVM tools run through the existing process Portal with bounded input/output and explicit failure handling. For Spring, keep existing bean instances and transaction semantics while moving supported operations to statically restricted platform workers. For maintainers, reuse the policy compiler, receipts, evidence taxonomy, JDK executors, and Portal instead of creating parallel engines.

## 2. Global constraints

- Keep all work in the MazeWall monorepo as ordinary Gradle subprojects, not Git submodules.
- Preserve JDK 22+ core compatibility unless a separately reviewed compatibility change proves necessary; use JDK 25 as the initial integration test runtime.
- Initial Spring consumer target: Spring Boot 4.1.1 BOM on JDK 25, a planning choice within the currently documented supported Java range. This is not a tested MazeWall compatibility claim. Record exact resolved Kotlin, coroutines, Koog, Gradle, and JDK distributions in the baseline task; do not opportunistically upgrade the root compiler or vulnerable demo. [S8]
- First Koog release: selected first-party, bounded data-in/data-out JVM tools through the existing process Portal. No credential broker, staged workspace, general shell/MCP runtime, or arbitrary hostile/generated code in that release.
- Prefer upstream Koog dependencies. Fork only after a minimal reproducible missing extension point has been identified.
- Spring, Spring Security, Reactor, and Koog dependencies stay outside core enforcement and Portal implementation modules.
- Ordinary enforcement uses reviewed static kernel policies. Runtime stack authorization and USER_NOTIF approval are not prerequisites.
- Reuse dedicated JDK-backed executor factories, policy compilation, assessments, installation receipts, profiling coverage, and diagnostic evidence. Do not build a custom ExecutorService or a universal scheduler.
- Required protection failure prevents the affected invocation from starting. Optional hardening is separately labeled; explicit unprotected development execution never reports protected status.
- Never install restrictions from a virtual thread or incidentally on shared, direct, event-loop, or caller-runs execution resources.
- Process-wide installation is an explicit application bootstrap action. It is not a starter side effect. Scope applicability is not a guarantee of runtime safety.
- Installed kernel restrictions are not reversible call scopes. Closing an invocation, runtime, or receipt never claims to remove them from a living worker.
- A stricter policy update uses new resources or rejects the transition; the first release has no hot policy reload. A future reload must replace a domain generation, not mutate permissions in place.
- In-process worker restrictions are not heap isolation, tenant isolation, arbitrary hostile-code isolation, hard per-invocation CPU/RAM isolation, or guaranteed termination.
- No automatic retry after a timeout, transport error, denial, or ambiguous external effect.
- No implementation or verification was performed while preparing the original plan. Current implementation and measured evidence are tracked separately in `2026-10-06-inline-execution-status.md`.

### Approved execution amendments

The revised C0–C9/P0–P5/S0–S3/R0–R1 plan is authoritative. Subsequent operator
decisions require inline execution on the existing branch without worktrees/subagents
and approve breaking migration/removal of obsolete facades instead of compatibility
shims. The first managed contract is synchronous Callable bodies and lexical cleanup.
Fatal in-process worker failure fails its generation; recreation is explicit after
retirement from trusted bootstrap. Stateful Portal loss is terminal, while stateless
capacity replacement never replays failed work. Initial Koog uses the required static
worker baseline. Initial Spring supports singleton proxyable synchronous methods and
approved materialized results; coroutine/WebFlux/JPA remain separate extension gates.

## 3. First-release execution contracts

### 3.1 In-process restricted domain

A fixed static policy is installed on dedicated platform workers. Supported calls use the existing Spring-managed object; worker count does not replicate the bean. Permission applies to code actually executing on those workers and resources covered by the selected backend. Independent helper executors, already-open descriptors, direct object access, and shared memory remain explicit limits. [R, A05 and A12]

One logical operation can include several beans and framework callbacks. Its reviewed policy covers the complete included operation. Using one policy for a transaction is intentionally coarser than independent per-bean policies; the parser inside that transaction receives that domain's available permissions. Never manufacture a permission union at runtime to make an unexpected call succeed.

### 3.2 Process-backed JVM service

Reuse `:portal`, `:portal-codegen`, and `:portal-worker`. The implementation is constructed in the worker, with an explicit lifetime and replication policy. Host/worker classpaths are distinct; host credentials and unrelated application internals are not copied wholesale. Inputs, results, errors, and logs are bounded values. Required process and filesystem coverage must be established before a protected-ready response.

A separate process is useful separation, not sufficient evidence of a complete malicious-code sandbox. The first adapter targets first-party JVM code and makes no general plugin/shell claim. Verify all required worker threads, descriptors, environment entries, and lifecycle operations. Where an all-thread filesystem guarantee is required, establish it using a supported bootstrap mechanism or verified synchronization; do not assume a dispatch-thread rule covers pre-existing JVM helper threads. [S1]

### 3.3 External process backend

Reserve no implementation obligation in the first release. Shell, Python, local MCP, descendants, cgroup budgets, and generalized launch contracts are a later project. Share a few lifecycle/evidence concepts when the backend exists, not an unimplemented universal interface now.

### 3.4 Host process-baseline compatibility

A host-wide process-creation prohibition conflicts with creating or replacing child Portal JVMs. The initial integration must diagnose a known conflicting MazeWall baseline and reject that configuration. It must not exempt arbitrary callers using stack tricks. Applications combining both features either select a compatible explicit host baseline or, in a later separately implemented design, use a trusted launcher established outside that prohibition. Worker-local process-creation restrictions can be installed at the appropriate worker bootstrap point. No hidden baseline weakening is permitted.

## 4. Canonical policy model

### 4.1 Construction and composition

Use fixed-scope mutable process and worker builders that produce immutable definitions. Retain one canonical compiler. Process construction does not expose thread-only filesystem methods. The worker builder never promotes itself to a new scope after a filesystem method. Fluent, imperative, and Kotlin `apply` usage must agree. Preserve the already repaired Java builder's internal retention behavior while migrating it. [R, A01–A02]

Expose explicitly named allow-list and deny-list entry points. Do not change every legacy default to deny-all. Report the JVM/runtime floor and additional argument constraints rather than pretending an empty syscall allow-list contains no runtime exceptions. Template selection and `intersection`/`restrictFurtherWith` remain distinct. False-after-true settings must either be real pre-install setters or be replaced by clearly named operations. [R, A01, A04, A07]

Filesystem intent has three distinct states: `Unrestricted`, `DenyAll`, and `AllowResources`. Empty configuration does not ambiguously switch between the first two. The restrictive intersection of active policies stays active even if no resource remains allowed. Metadata and diagnostics must not accidentally participate in permission intersection; algebra tests compare effective restrictions. [R, A04]

### 4.2 Filesystem details

Compile exact artifact grants where supported, explicit class/resource directories where intended, and a visible runtime-read closure. Never silently widen `/app/parser.jar` to `/app`. Packaged Spring JARs and Portal worker packages need separate packaged tests; cannot represent a grant precisely means an explicit failure or reviewed different grant. [R, A03]

Friendly read/write recipes compile to explicit rights for content access, truncation, creation, removal, and replacement. Read-only means the required data-mutation operations are denied, not that all metadata access is hidden. A broad writable ancestor is not canceled by a narrower read-only grant in the same layer. Detect/report contradictory intent. Already-open descriptors and mappings are not retroactively revoked. Unsupported required rights cause preparation failure. Ordinary DAC and external LSM restrictions still apply. [S1]

Future/nonexistent output files are authorized through explicitly reviewed existing output directories. Freeze resolution and binding generation for a prepared domain; renamed/replaced artifacts and symlink-switch deployments require revalidation, not silent rebinding to a different object.

### 4.3 Reviewed artifacts and bindings

Preserve the source model: observed behavior → candidate → reviewed policy → installed effective restrictions. Collection completeness, future-path coverage, authorization approval, and enforcement coverage are different fields. Preserve specific reviewed exceptions; never use a global `allowIncomplete=true` as automatic authorization. [R, A08]

A versioned operation binding connects logical operation ID, implementation artifact identity, adapter-specific bean/method or tool identity, fixed execution domain, runtime profile, resource bindings, and covered execution phases. The Spring adapter resolves actual target methods, overloads, and bean identities. Core does not import Spring metadata types. A digest identifies bytes; it is not proof of a trusted reviewer. Approval provenance and policy storage access remain explicit trust assumptions.

The initial entry points cover supported invocation bodies and declared completion, not arbitrary constructors, `@PostConstruct`, late stream consumption, or unrelated asynchronous side effects. Required unsupported bindings fail explicitly. Optional intentionally unbound infrastructure is visible as outside the contract, not misleadingly “protected.”

## 5. Minimal managed runtime

### 5.1 Responsibilities and conceptual API

`ManagedRuntime` owns application-scoped domain resources, admission, readiness, drain, and completion accounting. It is a coordinator around existing backends, not an implementation of ExecutorService. `InProcessDomain` references an immutable prepared policy and dedicated JDK executor. Process domains delegate transport and worker behavior to Portal.

`InvocationHandle<T>` distinguishes caller-visible result from actual execution end, and allows cooperative cancellation requests. `InvocationLease` keeps resources alive through supported child work, suspension, cancellation, and cleanup; these are internal lifecycle mechanisms, not a second kernel receipt. An `ExecutionTransitionGuard` can approve a dispatch, require inline same-worker execution, or reject. `InvocationContextBridge` captures/restores explicitly supported metadata, never every ThreadLocal.

Proposed internal seam names may be mapped to existing equivalents during baseline reconciliation, but there must be only one implementation of each responsibility. Backend capabilities remain explicit: a process can sometimes be terminated; a thread cannot promise equivalent semantics. Completion callbacks outside a protected operation are not covered merely because they are attached to its Future.

### 5.2 Lifecycle and capacity

Lifecycle: `NEW → PREPARING → READY → DRAINING → CLOSED`, with `FAILED` a terminal no-admission outcome. Individual workers do not execute application work before required installation succeeds. Failed/partially restricted workers are retired, not put in general circulation. Domain resources cannot be evicted while an invocation lease exists.

Preparation happens through an explicitly bootstrapped manager under the known application baseline. Initial workers are started there; replacement paths must preserve the exact domain policy and avoid inheriting unrelated narrower domains. Test replacement after a worker fails, not only prestarted threads. Never attempt permission expansion on a reused thread. [S1]

Per-domain worker count and logical admission/queue limits are finite and explicit in production configuration. No implicit cached-thread growth; no CallerRuns policy. The example uses four platform workers and a 64-invocation admission limit; these are demonstration settings, not benchmark-derived production defaults. In-process admission bounds do not establish a hard memory bound for arbitrary object allocation or coroutine child fan-out.

A caller timeout stops waiting and requests cancellation; it is not execution termination. Keep the lease until execution/known cleanup actually ends. Mark a still-running invocation accordingly and stop expanding capacity to hide stuck work. A runtime that cannot drain reports that condition; it cannot safely force-stop arbitrary Java code. [S9]

### 5.3 Coroutine policy

Separate new-operation admission from delivery of continuations for already admitted operations. Do not use an expiring/LRU executor cache as a coroutine's lifetime owner. Do not apply a rejecting bounded queue to continuation scheduling and then accept Kotlin's unrestricted fallback. [S2]

Prefer the smallest dispatcher/lifecycle adapter using JDK executors. Continuation completion during normal cancellation/overload/drain must remain in the same restricted domain. If bounded admission plus an internal continuation queue is used, explicitly document that arbitrary fan-out is not hard-memory bounded. Infrastructure failure must never dispatch protected user code unrestricted; catastrophic JVM failure cannot promise successful cleanup. Do not drop a live continuation and claim clean cancellation. [S2, S3]

No streaming/reactive return shape is advertised as protected until its consumption, cancellation, and lifetime are supported. A synchronous transaction inside a coroutine is one nonsuspending block on one worker; the coroutine may await it outside that block.

## 6. Spring adapter

### 6.1 Binding and packaging

Create an opt-in Gradle integration with a normal integration JAR, a thin starter, and a clean example. Use conditional auto-configuration; do not force MVC, WebFlux, Spring Security, or JPA onto all consumers. Add core-required libraries deliberately. Do not use vulnerable-demo dependency overrides or disabled quality checks. [S4]

Bind an explicit protection scope and approved manifest. Use Spring's effective target method/transaction metadata, not reflection annotations alone. Reuse existing bean instances and proxy advice. Validate actual advisor chains, including nested proxies. Reject ambiguous, unproxyable, unsupported lazy/scoped bindings before their protected target is invoked. Do not call the raw target to bypass security or transactions. Spring proxy limits remain documented and tested. [S10]

### 6.2 Transactions

Supported first transaction path: synchronous Spring JDBC `DataSourceTransactionManager`/`JdbcTransactionManager`, one transaction manager per protected operation. Add JPA only after its own gate. XA/JTA, multiple-manager coordination, reactive transactions, and suspend-spanning imperative transactions are outside this first contract.

Ordering: dispatch boundary outside transaction boundary; supported security checks and failure classification that must influence rollback inside transaction completion. A protected write with `@PostAuthorize` is either correctly ordered and tested or rejected; a denial after commit is never described as rollback. [S5, S6, S13]

Inspect active transactions, synchronization, and relevant thread-bound resources before dispatch. Do not move an existing external transaction. Same-domain nested invocation executes inline on the same physical worker. Different-domain transitions inside the transaction are rejected unless the entry point was already approved for a common domain. `REQUIRED`, `REQUIRES_NEW`, `NESTED`, `SUPPORTS`, `MANDATORY`, `NOT_SUPPORTED`, and `NEVER` keep the supported Spring manager's semantics without implying a thread hop. Unsupported combinations fail explicitly. [S5, S11]

A recorded containment violation makes the protected invocation unsuccessful and prevents commit of still-active supported transactions once the adapter observes it. Normal business exceptions retain normal Spring semantics. Explicit incompatible rollback overrides are rejected for required protected operations. Independently committed transactions remain committed. Evidence from an inner operation is not erased by a catch block, but the adapter cannot discover an unobserved swallowed ERRNO merely by consulting a boolean. Generic permission failures may conservatively cause failure without being relabeled confirmed MazeWall denials. [R, A09]

Use existing diagnostic classification inside the transaction boundary. `beforeCommit` is a gate for recorded evidence at that moment; `beforeCompletion` is not a rollback veto. Do not promise detection of all later/silent callback failures. Commit, rollback, and unknown results remain separate from method result and delivery outcome. [S6]

Open EntityManager in View, stale lazy entities, and transaction callbacks need explicit compatibility tests in the JPA extension. Initial examples disable Open-in-View and return bounded materialized results. SQL authorization, DB read-only enforcement, and transaction atomicity across files/network are not supplied by seccomp/Landlock.

### 6.3 Security and web execution

Reuse Spring Security's supported context capture/restore facilities, captured per invocation. Authentication does not widen a domain's kernel policy. Restore clean context before the worker services another invocation. Do not copy connections or entire servlet request objects. [S7]

Servlet callers may be virtual threads as long as installation and protected execution use dedicated platform workers. Support Kotlin suspend/WebFlux entry-point adapters only after coroutine lifecycle tests pass. A CoWebFilter or coroutine controller context is a possible integration seam, not evidence that all HTTP transport, codecs, WebClient engines, or database I/O are now controlled by that policy. Report coverage boundaries.

## 7. Koog adapter

Consume Koog as a dependency and adapt one class-based tool to an existing typed process Portal. Host code selects the policy, implementation, contract, size limits, and timeouts; model-supplied policy IDs are data, never authority. Use explicit bounded arguments/results, one declared worker-side instance by default, cancellation propagation, terminal failed sessions, and no replay after ambiguous failure. [S12]

The first example is a deterministic first-party parser/transformer with no network or process creation requirement. A fake model/test executor can exercise dispatch without model-service costs. Required worker-wide checks, parent-safe packaging, cleanup, and malformed-message tests are release gates. No process-to-in-process fallback.

Host and worker may differ in capabilities and classpaths, but use a reviewed matching protocol/artifact generation. The first package does not include an entire Spring application context or Koog runtime in the worker unless its implementation actually requires it.

### 7.1 Thread extension — 18 October 2026

The historical first-release Portal scope above remains unchanged. The additive
`ThreadToolAdapter<A, R>` explicitly binds an application-owned `ManagedRuntime`
and its already-prepared fixed `InProcessDomain` to trusted synchronous `(A) -> R`
code. The application installs its reviewed process-wide Tier 1 baseline at
platform-thread bootstrap and retains preparation, finite admission, drain and
close. The adapter does not create another backend, choose policy from arguments,
intercept arbitrary suspend tools, retry/replay or fall back from Portal.

Submission stays on the original coroutine caller so ownership and nested guards
remain effective. Only the synchronous action runs on dedicated restricted
platform workers; JSON, validators and continuation delivery remain in the host.
Waiting occupies one host IO thread per concurrently waiting admitted invocation.
Cancellation requests the original handle cooperatively, not forced termination;
only canonical drain and actual worker retirement establish complete shutdown.
Shared heap, helper executors, open FDs, detached/lazy work, hard CPU/RAM budgets
and arbitrary Java/ACE isolation remain outside this contract.

The [Koog guide](koog/README.md#explicit-thread-backed-tools) provides the bounded
strict UTF-8 reader and explicit owner/domain example. The
[published consumer](../tests/consumers/koog-portal/README.md#separate-thread-backed-qualification)
qualifies real filesystem/socket/caller boundaries, ownership, finite admission,
cooperative cancellation and retirement, then emits the required exact thread
summary. It runs in a separate fresh JVM after the unchanged Portal qualification:
its permanent `Policy.NO_EXEC_HOTSPOT` host baseline forbids subsequent Portal
child launches. Neither policy weakening nor closing the owner reconciles that
bootstrap incompatibility. Permission-failure classification is inferred, not
authenticated kernel provenance; passive diagnostics add no permission authority.

## 8. Monorepo layout and migration

Keep existing core modules. Put minimal framework-neutral coordination in `enforcer/.../execution/` initially; do not introduce an extra runtime Gradle module until dependency or artifact isolation requires it. Continue using existing optional coroutine support, avoiding new mandatory Reactor/Spring dependencies.

Proposed modules:

- `:integrations:koog` — optional Koog adapter.
- `:integrations:spring:mazewall-spring-boot` — Spring integration and conditional optional bridges.
- `:integrations:spring:mazewall-spring-boot-starter` — thin dependency starter.
- `:demos:koog-contained-tool` and `:demos:spring-boot-sandbox` — clean, reproducible consumers.

Expose `-PincludeKoogIntegration=true` and `-PincludeSpringIntegration=true` during incubation; CI explicitly enables and tests each plus the combined build. This protects default core work from unrelated integration resolution while preventing the optional modules from going untested.

Repair misleading documentation immediately. Introduce canonical fixed-scope construction,
migrate callers and remove obsolete facades under the operator's breaking-change approval.
Do not silently redefine legacy permission defaults. The incompatible pre-alpha release
requires migration fixtures and reviewed ABI/packaged consumers. No facade may compile
while silently ceasing to enforce. [R, A10–A11 and comparative lessons]

## 9. Later features: separate project gates

After useful integrations and public-consumer gates pass, add staged workspaces and an explicit operation broker as independent projects. Workspaces provide baseline-aware, conflict-detecting apply/discard, not a distributed transaction. Brokers keep secrets in an appropriately isolated host/service and bind grants to real sessions and normalized operations. Task attenuation/budgets and audit evidence belong to these projects; coroutine metadata alone is not unforgeable authority.

A general external process runtime follows only with launch, descendant, environment, descriptor, and resource-control guarantees. cgroup availability/delegation and process-tree cleanup are explicitly verified. Keep unknown side-effect outcomes and revocation limits honest. Do not block the first Spring/Koog releases on any of these projects.

## 10. Success criteria

The initial engineering release passes A01–A12 regression coverage, required-install failure injection, exact resource-grant tests, owned lifecycle tests, packaged Java/Kotlin consumers, a Koog process tool demonstration, and a Spring transactional operation demonstration. Coroutine support adds its own rejection/cancellation/suspension gate rather than inheriting synchronous success.

Independent usability exercises measure the policy people construct **and what they believe it protects**. No exercise may end with silent widening or false protected readiness. This is an engineering check, not certification or statistically representative proof. [R, Usability validation]

Performance reporting separates policy preparation/installation, queueing, dispatch, task time, syscall/FS checks, process startup, and IPC. Compare baseline, ordinary dedicated executor, and MazeWall with equivalent work and concurrency. Report distributions, throughput, resources, retries, and failures; never invent speed targets or claim overhead is negligible without measurements.

## Sources

**R.** User-supplied review, `../../../sources/mazewall-api-findings.md` (in this archive: `sources/mazewall-api-findings.md`); review date 1 October 2026, pinned snapshot above. Refer to findings A01–A12 rather than treating them as newly reproduced results.

Primary references checked while preparing the plan (6 October 2026). These support framework/kernel constraints; they do not verify MazeWall implementation:

- **S1:** Linux Landlock userspace documentation — https://docs.kernel.org/userspace-api/landlock.html
- **S2:** Kotlin `asCoroutineDispatcher`, rejected-execution behavior — https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/as-coroutine-dispatcher.html
- **S3:** Kotlin `CoroutineDispatcher.dispatch` contract — https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/dispatch.html
- **S4:** Spring Boot auto-configuration and testing — https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html
- **S5:** Spring `@Transactional` semantics — https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Transactional.html
- **S6:** Spring `TransactionSynchronization` callbacks — https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html
- **S7:** Spring Security context concurrency support — https://docs.spring.io/spring-security/reference/servlet/integrations/concurrency.html
- **S8:** Spring Boot 4.1.1 system requirements — https://docs.spring.io/spring-boot/system-requirements.html
- **S9:** JDK 25 `Future` cancellation — https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Future.html
- **S10:** Spring proxy semantics — https://docs.spring.io/spring-framework/reference/core/aop/proxying.html
- **S11:** Spring thread-bound resources — https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronizationManager.html
- **S12:** Koog class-based tools — https://docs.koog.ai/tools/class-based-tools/
- **S13:** Spring method-security/transaction ordering — https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- **S14:** Spring `SmartLifecycle` — https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html

The user-pasted Koog roadmap and this conversation provide the product scope and motivations. The detailed design decisions above are proposals, not additional findings attributed to the uploaded review.
