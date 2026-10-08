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
Only the existing scalar/string/byte-array synchronous result allowlist is supported;
erased generic entries are checked against their actual bridged target result.

Each binder accepts one exact operation descriptor; a bean containing another
required annotated operation is rejected rather than partially protected. Multiple
contexts own independent binders/lifecycles. Direct object access, constructors,
initializers, unrelated executors and unbound infrastructure are outside this scope.

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
