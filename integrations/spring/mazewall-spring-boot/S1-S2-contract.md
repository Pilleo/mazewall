# Explicit synchronous Spring contract (S1/S2)

## S1 binding and ownership

`DomainLifecycle` accepts an application-bootstrapped `ManagedRuntime`, a trusted
`ReviewedOperationArtifact`, finite `DomainLimits`, and a positive drain budget.
Call `start()` explicitly before constructing the binder. It prepares through the
public runtime API, never installs a process baseline or selects a fallback.
`isAutoStartup()` is false. A failed or retired lifecycle requires a new explicit
owner; an incomplete stop retains the old owner and exposes its `DrainReport` for
subsequent draining. A stop callback means the bounded stop returned, not that
unfinished executions physically ended.

Create a `SpringOperationBinder` per reviewed operation, and install its immutable
`dispatchAdvisor()` in the application's existing `ProxyFactory` before publishing
the bean. Retain the transaction, diagnosis, and authorization advisors after it,
freeze the factory, and register that exact proxy as a singleton. Binding inspects
that registered proxy; it does not replace it, clone its target, or invoke a raw
target. It requires the already-reviewed S0 chain and rejects nested proxy topology.

Application-owned `Approval` pins the actual registered bean reference, defining
classloader, runtime, resource-generation metadata and both BODY and
TRANSACTION_COMPLETION phases. The reviewed document separately pins bean name,
implementation class name, bridged JVM method descriptor and class-resource SHA-256.
`@MazewallOperation` is a required marker, not policy approval. Call
`validateRequiredBindings` on the explicit protected bean scope before traffic.
Unmatched required annotations/overloads, lazy/scoped/uninitialized beans, final
methods, missing reviews, substituted objects/loaders/bytes and advisor drift fail.
Discovery inspects declared implementation/interface methods across the hierarchy,
including inherited private/protected/package declarations. Required non-public,
static, final or non-interface-mappable declarations reject; they are never silently
optional infrastructure. Unannotated private superclass shadows remain unmarked.
Only the existing scalar/string/byte-array synchronous result allowlist is supported;
erased generic entries are checked against their actual bridged target result.

Each binder accepts one exact operation descriptor; a bean containing another
required annotated operation is rejected rather than partially protected. Multiple
contexts own independent binders/lifecycles. Direct object access, constructors,
initializers, unrelated executors and unbound infrastructure are outside this scope.

For independently reviewed beans in a common domain, use the explicit
`SpringOperationBinder.inApprovedCommonDomain` factory with the SAME lifecycle and
shared JDBC guard. It requires identical canonical fixed policy/runtime and resource
generation. Each operation still validates its own exact bean, method, hash and
review. This is trusted application grouping, not automatic policy combination.

### Evidence limits and required core seam

The defining loader's class resource is checked against the reviewed digest. This
does not prove that arbitrary custom loaders return the bytes used to define a
class, nor pin a finished implementation JAR or filesystem object. Core task 5
owns actual implementation/resource resolution and descriptor retention through
worker preparation. Required minimal seam: preparation must accept/validate the
resolved implementation identity plus defining classloader and actual prepared
resource generation, retaining the owned resources for the domain's physical
lifetime. The existing metadata-only `prepare(artifact, limits)` is consumed, but
is not represented as that proof. Covered phases and expected object/loader are
explicit trusted application configuration, not fields inferred from the document.

Host characterization factories are explicitly unprotected. Their tests prove
Spring behavior and state-machine ownership, not native readiness. S3 starter,
method Security and packaged/native release acceptance require subsequent review.

Core task 5 is adding `PreparedOperationResources` and a typed preparation overload.
Once integrated, the lifecycle must consume that actual owner, and production binding
must compare its finished-JAR identity with the trusted review instead of substituting
the current class-resource digest. The first task-5 resolver rejects exploded/nested
origins; Spring Boot/exploded support is a separate explicit blocker. An application
using the current metadata-only path has not closed that release gate.

[TASK5-ADOPTION-PLAN.md](TASK5-ADOPTION-PLAN.md) specifies the exact follow-up,
transfer/cleanup, selected-Class/common-origin limits and coordinator/root update.
It is gated on core review, not adoption of unreviewed source or resource/JAR proof.

## S2 qualified synchronous JDBC advice

Register one qualified `DataSourceTransactionManager` or `JdbcTransactionManager`
singleton. Construct a `SpringTransactionGuard(beanFactory, "jdbc", effects)` and
use its `transactionAdvisor()` and `diagnosisAdvisor()` in that order after dispatch.
The transaction advisor is a final subclass of Spring's real interceptor: Spring
retains propagation, savepoint, suspend/resume, commit and rollback behavior; the
selection override pins the actual qualified manager. A normal interceptor's public
manager getter is insufficient because hidden BeanFactory qualification can select
another manager. Unsupported existing transaction advisors are rejected explicitly.
The exact existing configured advisors/proxy are retained at binding and invocation.

Every approved method must use effective annotation-based `@Transactional` metadata
with the exact configured manager qualifier. All seven propagations are admitted;
NESTED requires enabled JDBC savepoints and a driver that actually supports them.
An unsupported driver fails before the nested target body. Effective manager,
datasource reference/configuration, source, attributes, rollback matchers and advisor
identities are rechecked before dispatch and on the worker. Concurrent configuration
mutation/hot reload remains unsupported. JPA, XA and reactive managers are rejected.

External active transactions, synchronization and bound resources are rejected before
the hop. Nested approved calls share the exact physical worker, dispatcher and manager;
cross-domain calls reject. No connections or arbitrary ThreadLocals are transferred.
Production JDBC entry also checks the public invocation evidence's prepared generation
inside the owned worker before transaction entry.

Diagnosis runs inside transaction advice and records the first diagnosed failure for
the logical invocation. An unchecked rollback bridge prevents a diagnosed checked
permission failure from receiving Spring's ordinary checked-business commit behavior.
The original Throwable is restored after transaction completion in host execution;
production core may classify/wrap it while preserving the original cause/evidence.
Fatal Error identity is restored ON THE WORKER before the runtime sees it, preserving
fatal-generation handling. Effective no-rollback rules conflicting with protected
failure semantics reject at binding. Ordinary checked/runtime business semantics
remain Spring's normal semantics.

Catching a library-diagnosed inner failure does not erase it. The guard checks retained
diagnosis plus `Mazewall.currentInvocationEvidence()` and registers a `beforeCommit`
gate at lowest precedence. It prevents commit of still-active supported transactions
when that evidence is observed. `beforeCompletion` is not a rollback veto. Same-order
or later callbacks and unobserved swallowed ERRNO cannot be universally diagnosed;
there is no such detection claim. Generic AccessDenied/EACCES evidence remains inferred.

`SpringTransactionEffects.lastCompletion()` retains ONE immutable report for the most
recently completed supported worker transaction chain, with an invocation ID, worker
ID, transaction/savepoint outcomes, original execution failure and diagnostic. It is
not a complete audit stream, a caller-delivery receipt, or the runtime's physical
`executionEnd()`/cleanup/drain result. Database outcomes come from actual
`afterCompletion` callbacks: COMMITTED, ROLLED_BACK or UNKNOWN. Independently committed
REQUIRES_NEW work stays committed when the outer fails. A savepoint callback indicates
rollback INTENT before JDBC rollback; it does not stamp success/completion. The
exact-manager interceptor brackets Spring's real held-savepoint rollback path and
confirms an unambiguous intent only after successful return. Failed/unobserved/
ambiguous attempts remain UNKNOWN if the outer commits or is uncertain. Confirmed
whole-transaction rollback also confirms undone nested work. Confirmed ancestor
savepoint rollback covers later-created descendants in that same physical transaction;
independent REQUIRES_NEW work has a separate physical owner/map. Savepoint rollback is
separate from outer commit; driver commit uncertainty remains UNKNOWN. AfterCommit failures
remain COMMITTED. Nontransactional/autocommit database work has no fabricated rollback
receipt. Files/network effects have no JDBC rollback guarantee.

### Host evidence

Actual H2 and both supported manager implementations exercise all seven propagations,
normal checked-business commit, runtime-business rollback, nested savepoint rollback,
unsupported savepoints, caught diagnosed checked failures in REQUIRED/REQUIRES_NEW/
NESTED/NOT_SUPPORTED, earlier beforeCommit callback diagnosis, driver commit failure,
afterCommit failure, hidden manager substitution and datasource identity drift.
Caller interruption while the host worker remains active demonstrates why result
delivery and database completion cannot be conflated. This fixture is explicitly
uncontained; native owned-runtime cancellation and kernel denial are separate gates.
Fault-wrapped JDBC tests use the EXACT same datasource for JdbcTemplate and manager,
and assert its transaction resource is bound before transactional SQL. A real
rollback(savepoint) fault caught before outer commit leaves the write present and
its receipt UNKNOWN; combined commit uncertainty remains UNKNOWN. Full outer rollback
and ancestor/descendant rollback use actual SQL-state assertions, preserving independent
REQUIRES_NEW commit. These are real connection/manager tests, not mock assurances.
