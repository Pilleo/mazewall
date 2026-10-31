# Spring: explicit synchronous binding and JDBC placement

This optional integration establishes **typed ordinary-JAR Spring binding, lifecycle,
advisor placement and real embedded JDBC effects** (S0–S2), with a disposable published
ordinary-JAR native fixture. It has explicit programmatic construction and a thin
optional normal-JAR starter, with no automatic runtime/baseline installation. It uses Spring Boot **4.1.1** dependency management and
Spring **7.0.9**, on JDK **25**. H2 **2.4.240** is test-only. Spring remains outside core.

See [S1-S2-contract.md](S1-S2-contract.md) for the explicit reviewed binding/lifecycle
and qualified JDBC entry. Historical phase-specific evidence is supplemented by
the current ordinary-JAR and finished-Boot consumer workflows linked below.
The entry below is the narrower retained S0 placement API.

See [S3-contract.md](S3-contract.md) for optional per-invocation supported Security
delegation, narrow upstream method authorization, conditional class absence and the
[normal-JAR starter](../mazewall-spring-boot-starter/README.md). Security **7.1.1** is
compile-only in this integration and is never forced on a plain consumer.

## Programmatic entry

Bootstrap and prepare a `ManagedRuntime` and `InProcessDomain` explicitly using
the existing core API. Reuse **one** `ManagedRuntimeDispatcher` instance across
approved same-domain bean operations. Construct a `BoundInvocationInterceptor`
per bean, then call:

```java
var boundary = new BoundInvocationInterceptor(dispatcher);
Operation proxy = (Operation) boundary.createProxy(
    existingSingletonBean,
    transactionAdvisor,
    diagnosisAdvisor,
    authorizationAdvisor,
    List.of(Operation.class.getMethod("run")));
```

The production constructor requires an actual `ManagedRuntimeDispatcher` (the
final owned-runtime adapter), even though its argument is typed as the dispatch
interface. It rejects arbitrary executors/dispatchers before protected work.
Owned worker admission and execution authority come from the prepared core
runtime/domain; a different thread or a thread-local marker is not ownership proof.

Host characterization explicitly opts in using
`BoundInvocationInterceptor.forTrustedHostCharacterization(dispatcher)`. This
trusts the supplied synchronous dispatcher and proves no ownership or protection.
Thread checks cannot distinguish a shared platform executor in this mode; it
must never be used as a protected-production fallback.

The bean is the existing singleton instance; no per-worker copies are created.
The public entry constructs a fresh frozen JDK proxy, validates its **effective**
chain, and publishes it only after structural proof. It cannot adopt an arbitrary
already-used proxy with an unknown cached chain. Existing nested proxy targets
are rejected, rather than unwrapped or invoked directly.

The worker executes `ProxyMethodInvocation.invocableClone().proceed()`. The clone
resumes after dispatch, retaining transaction, diagnosis, authorization, and
Spring's target invocation. No raw target invocation occurs. The dispatch slot is
immutable because freezing `Advised` does not freeze mutable advisor objects.

## Narrow supported chain

1. Immutable MazeWall dispatch advisor, outermost.
2. Exact Spring `TransactionInterceptor`, with one explicit synchronous
   `PlatformTransactionManager`, exact `MatchAlwaysTransactionAttributeSource`,
   and exact `RuleBasedTransactionAttribute` using unqualified `REQUIRED` metadata.
3. Explicitly reviewed synchronous diagnosis interceptor.
4. Explicitly reviewed synchronous authorization interceptor.

The final three advisors must be exact `DefaultPointcutAdvisor` instances with
`Pointcut.TRUE`. Diagnosis and authorization must each proceed once without
retrying, scheduling child work, or changing the invocation arguments. Their
internal behavior is a trusted collaborator contract, not something inferred
from an advisor class name. Post-authorization executes before diagnosis exits
and before transaction completion. The host tests demonstrate rollback for
business failure and a post-authorization denial using a real Spring transaction
interceptor and an instrumented host transaction manager.

Only explicitly listed interface methods whose actual bridged implementations return primitives/void, primitive
wrappers, `String`, or `byte[]` are admitted by S0. Qualified JDBC additionally admits
the bounded conventional records described below. Unknown methods, suspend signatures,
lazy/reactive/opaque shapes, CGLIB, nested proxy topology, dynamic pointcuts,
additional advice, and mutable/unproved chains are rejected. Advisor/advice,
pointcut, transaction manager/source/definition, and target-source drift are
checked before dispatch and again on worker entry. Transaction snapshots compare
explicit propagation/isolation/timeout/read-only/name/qualifier fields, labels,
descriptor and timeout-string metadata. Labels are copied as an immutable
structured list rather than formatted text. Ordered rollback matcher identities
are pinned; only exact immutable Spring rollback/no-rollback matcher classes
are supported, and replacing a matcher requires a new binding. Configuration collaborators
must remain immutable after publication; concurrent hot reload is unsupported.

An external caller transaction, synchronization, or bound resource prevents
dispatch. A worker with unrelated transaction state is rejected. Proved nested
bean calls using the identical dispatcher and transaction manager remain on the
same worker through the core's canonical inline transition; cross-binding and
different-manager calls reject. Every nested callback must match the captured
physical worker and exact active context before transaction/body entry. Nested
offload to a different platform or virtual worker rejects, even with the same
dispatcher and manager. A dispatcher cannot replay a clone, use the caller thread,
or enter on a virtual worker.

## Evidence and boundaries

`AdvisorPlacementTest` uses a dedicated **uncontained** platform worker via the
explicit trusted-host `InvocationDispatcher` factory. It records dispatch, begin, body,
authorization, diagnosis and commit/rollback thread IDs. It verifies registered
singleton identity, exact-once body entry, one-worker nesting, failure identity,
and rejected drift/order/topology/resource cases. Declared `IOException` and a
specific `LinkageError` retain original identity through the actual adapter's
Callable bridge executed by the host worker; Spring's default checked-exception
commit and Error rollback are asserted independently. A virtual **caller** can
offload to the platform worker; separate top-level and nested virtual **worker**
cases reject before their transaction/body chains. These tests are **not kernel
protection proof**, actual JDBC effect evidence, or Spring Security integration.

`ManagedRuntimeDispatcher` compiles against and delegates to the existing public
`Mazewall.callContained(runtime, domain, Callable)` API. It does not install native
restrictions or bootstrap a runtime. This S0 host slice does not execute its
native path or fatal generation retirement. Host execution of its extracted
Callable adaptation is evidence for that bridge only. Kernel failures remain
failures; there is no fallback or replay.

`OperationBindingTest` and `DomainLifecycleTest` exercise S1 exact registered bean,
defining loader/class-resource digest, bridged descriptor, required annotations,
review metadata and bounded retained lifecycle ownership. `JdbcTransactionTest`
uses real H2 connections and actual qualified Spring JDBC advice for all seven
propagations, rollback/commit/unknown outcomes, savepoints, caught diagnosed failures,
beforeCommit gating and caller-delivery separation. `ReviewedJdbcBindingTest`
composes reviewed bindings and independently committed inner JDBC work on one worker.
These are host regressions, not native ownership/protection/resource pinning proof.
The separate [published ordinary-JAR consumer](../../../tests/consumers/spring-jdbc/README.md)
uses genuine typed resources and a trusted external test-review step. It proves actual
owned-worker JDBC data, denied template mutation/sibling opens, transaction rollback,
virtual-caller cooperative cancellation, retained incomplete drain and actual physical
worker/resource retirement. Generic permission diagnostics remain inferred. Its
multiple-origin negative fixtures reject uncaptured Classes from the same and another
actual JAR despite repeated fixed-policy/generation metadata. The optional ordinary-JAR
Security native variant additionally proves clean per-call context, alternating principals,
actual post-denied JDBC rollback, ordinary error/cancel and physical end. Executable
Boot/exploded origin support has its own exact bounded contract and finished-JAR
fixture. It is not inferred from these host or ordinary-JAR tests; unsupported
origins continue to reject.

Direct references/self-invocation, shared heap, preopened descriptors, independent
executors, construction/init and late work are outside the proxy body boundary.
In-process containment is not an arbitrary-code isolation boundary. Closing a
runtime or receipt does not uninstall permanent restrictions from live threads.

## Bounded Java record results (qualified JDBC only)

A public, nongeneric record in the reviewed target's exact defining loader and
code-source URI may have1–16 components of primitive, boxed primitive or `String`.
Arrays (including byte arrays), collections, nested records, entities, lazy graphs,
Kotlin data classes and arbitrary DTOs remain rejected. Binding inspects actual
record bytecode and accepts only ordinary own-field getters; custom executable
accessors and serialization hooks reject without invoking them. This reuses Spring
ASM and adds no parser/serialization dependency or public API.

Aggregate nonnull strings must be valid Unicode and at most65536 UTF-8 bytes. Null
reference components and a null whole record are allowed. Validation runs on the
protected worker **inside diagnosis and before physical JDBC commit**. Invalid
results cause rollback and retained failure evidence, even when an outer approved
call catches a nested invalid-result exception. The local failure is an ordinary
invalid-result cause, not invented kernel-denial evidence. Conflicting rollback rules
and nontransactional propagation modes reject at binding. Existing scalar/String/
byte-array behavior is unchanged; S0 without the JDBC guard does not admit records.
This is a closed materialized-result contract, not a serializer or arbitrary-code/
shared-heap isolation. Immutable reviewed deployment/no-redefinition assumptions apply.

## Calling an approved service from Koog

The [independent Koog/Spring consumer](../../../tests/consumers/koog-spring/README.md)
calls the actual bound singleton proxy through a consumer-local upstream Tool.
It does not add another protected executor. The interruptible synchronous wait
stays on the original application-selected caller, so the existing transaction
guard and per-call Security capture remain effective. Business exceptions unwrap
outside that wait; cancellation requests the original owned invocation without
claiming physical end. The published-JAR gate proves both platform and virtual
callers with real pre/post authorization, JDBC effects, filesystem denial and
complete worker/resource retirement. It is not a general nonblocking, Boot-web,
reactive or suspend-method adapter.

## Focused verification

```bash
./gradlew -PincludeSpringIntegration=true :integrations:spring:mazewall-spring-boot:build \
  :integrations:spring:mazewall-spring-boot-starter:build --configuration-cache-problems=fail
# Repeat unchanged to prove configuration-cache reuse.
# Exact-publication/native acceptance owns its lock internally:
./gradlew optionalIntegrationCheck --console=plain --no-configuration-cache
```

Serialize ordinary build/native commands with the shared verification lock when
needed. Do not externally wrap `optionalIntegrationCheck` with that same lock;
the owning harness acquires it internally.

## Read-only lifecycle and operation diagnostics

`DomainLifecycle` implements `ExecutionDiagnosticsSource`. Its `operationId()` is
the externally reviewed binding identity; `diagnostics()` samples the existing
prepared domain without starting it, submitting work, validating resources or
draining. Polling also stays nonblocking while preparation/drain holds the lifecycle
monitor. Explicit uncontained characterization reports unavailable readiness.

```java
var snapshot = lifecycle.diagnostics();
System.out.println(lifecycle.operationId() + ": " + snapshot.getState());
System.out.println("cancel requests=" + snapshot.getInvocations().getCancellationRequests()
        + ", unfinished=" + snapshot.getUnfinishedInvocations());
```

Cancelled delivery and completed results are not physical retirement. A captured
Incomplete observation never upgrades when a worker subsequently exits; canonical
drain remains necessary. See the [diagnostics contract](../../../docs/internals/designs/core/execution-diagnostics.md)
and [optional Actuator setup](../mazewall-spring-boot-starter/README.md#optional-read-only-actuator-diagnostics).

The dedicated optional CI lane selects actual adapter builds, strict locks,
configuration-cache reuse and published Koog/JDBC/Security/finished-Boot acceptance.
The existing required core job and master package publication are gated by that
lane. Optional library publications use the same operator-managed credentials as
the existing publication boundary; there is no application credential discovery.
