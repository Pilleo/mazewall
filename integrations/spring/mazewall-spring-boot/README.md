# Spring S0: synchronous advisor placement

This optional integration establishes **host-tested Spring advisor placement**.
It is the S0 foundation, with programmatic construction and no Boot starter or
automatic installation. It uses Spring Boot **4.1.1** dependency management and
Spring **7.0.9**, on JDK **25**. Spring remains outside core.

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

Only explicitly listed interface methods returning primitives/void, primitive
wrappers, `String`, or `byte[]` are admitted. Unknown methods, suspend signatures,
lazy/reactive/opaque shapes, CGLIB, nested proxy topology, dynamic pointcuts,
additional advice, and mutable/unproved chains are rejected. Advisor/advice,
pointcut, transaction manager/source/definition, and target-source drift are
checked before dispatch and again on worker entry. Configuration collaborators
must remain immutable after publication; concurrent hot reload is unsupported.

An external caller transaction, synchronization, or bound resource prevents
dispatch. A worker with unrelated transaction state is rejected. Proved nested
bean calls using the identical dispatcher and transaction manager remain on the
same worker through the core's canonical inline transition; cross-binding and
different-manager calls reject. A dispatcher
cannot replay a clone, use the caller thread, or enter on a virtual worker.

## Evidence and boundaries

`AdvisorPlacementTest` uses a dedicated **uncontained** platform worker via the
injectable `InvocationDispatcher` seam. It records dispatch, begin, body,
authorization, diagnosis and commit/rollback thread IDs. It verifies registered
singleton identity, exact-once body entry, one-worker nesting, failure identity,
and rejected drift/order/topology/resource cases. These tests are **not kernel
protection proof**, actual JDBC effect evidence, or Spring Security integration.

`ManagedRuntimeDispatcher` compiles against and delegates to the existing public
`Mazewall.callContained(runtime, domain, Callable)` API. It does not install native
restrictions or bootstrap a runtime. This S0 host slice does not execute its
native path. Kernel failures remain failures; there is no fallback or replay.

S1 must bind approved manifests, bean identities/descriptors, lifecycle and
effective annotations. S2 must prove JDBC propagation, containment diagnosis and
commit/effect gates. S3 must integrate optional Security context, actual method
security, starter and packaged Boot evidence. No S1–S3 completion is implied here.

Direct references/self-invocation, shared heap, preopened descriptors, independent
executors, construction/init and late work are outside the proxy body boundary.
In-process containment is not an arbitrary-code isolation boundary. Closing a
runtime or receipt does not uninstall permanent restrictions from live threads.

## Focused verification before root integration

Use the task-owned init fixture documented in
`tests/consumers/spring-advisor/README.md`. The coordinator owns the optional root
include patch; default root settings and compiler remain unchanged.
