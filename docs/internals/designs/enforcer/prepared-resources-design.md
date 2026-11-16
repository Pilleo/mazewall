# Prepared core resource and implementation identities

Task 5/8 C4/C6 slices; ordinary JAR, bounded exploded and supported executable Boot
origin binding are implemented. This is not a
claim that the combined integration release gates or physical bytecode closure pass.

## Ownership and preparation

`ManagedRuntime` retains its captured `InstallationEnvironment`. For filesystem
policies on the original route, the manager resolves every explicit grant, `java.home`,
and every nonempty classpath entry before constructing workers. The direct-owner route
captures those resources before the manager claims the result. Missing grants or runtime entries fail;
a nonexistent writable leaf never implicitly authorizes its parent. Future output
uses an explicitly reviewed, existing directory grant.

`PreparedPath` obtains a genuine `O_PATH | O_CLOEXEC | O_NOFOLLOW` descriptor through
the captured `NativeEngine`, consumes its native acquisition receipt, and compares
Linux `/proc/self/fd` metadata with the selected real path and filesystem file key.
There is no invented closeable descriptor or new native layout/downcall. Linux proc
descriptor metadata and stable file keys are required; unavailable or denied metadata
fails preparation. User-requested symlinks resolve before acquisition; later target
switches, final symlink replacement, or replacement of the selected inode reject.

The manager builds one Landlock ruleset using the existing native rule operations.
Every required worker restricts itself using that same owned ruleset and its pinned
objects, through the canonical installer, Landlock before Seccomp. Pathnames are not
reopened separately on each worker. All workers must finish required installation
before preparation returns. Identity validation runs before and after setup and on
the manager before each external dispatch. Validation does not hold the runtime lock
while waiting on the manager, so concurrent preparation cannot deadlock dispatch.
Filesystem dispatch waits for bounded resource validation using the runtime preparation
timeout before admission; non-filesystem domains retain their direct admission path.

Descriptors and the ruleset remain owned until workers physically retire. A failed
generation stops admission; cooperative cancellation or caller-visible completion
does not release its resources. Incomplete drain retains live owners. Stopped failed
generations must finish prepared cleanup before explicit recreation. Each filesystem
generation has one retained `PreparedRetirement` platform thread, started by the trusted
manager before validation/worker setup. It performs no target execution or installation.
On failure/drain it waits for actual executor termination and joins every physical
worker. The original route then acquires the public operation owner's validation monitor
before releasing its shared resource set and ruleset; the direct-owner route uses its
counted validation and dependency order described below. External/manager validation and a stalled close can
block this cleanup owner, but never the drain caller: drain only joins it within the
same absolute deadline and reports Incomplete while cleanup lives. The liveWorkers
count includes this physical cleanup owner, as it already includes the runtime manager.
No descriptor is closed underneath validation. Caller interruption cannot forget a live
cleanup owner. Cleanup attempts all independent releases, retains failures, and uses
existing `ResourceRetirement` / `CleanupFailures` fatal-first ordering. Native thread-start
failure keeps rollback on the registered trusted manager; a blocked rollback remains
visible as incomplete manager/cleanup ownership. Closing never removes living filters.

Retirement publishes one immutable `PreparedRetirementOutcome` after release and
failure retention, carrying release-attempt status and exact failure together. Physical
thread exit remains a separate prerequisite: publication alone is not retirement.
Domain drain observations establish worker/cleanup physical completion before sampling
retained outcomes; manager physical end is established before collecting domain snapshots.
The pure `DrainLifecycle` decision only permits Complete from terminal successful
snapshots. A captured pending state cannot be upgraded by later zero-live metrics. Late
ordinary/fatal failures keep the runtime DRAINING and remain visible on every later
drain/close; no earlier cached null may permanently mark the runtime CLOSED. All
observation/decision work is nonblocking and adds no wait under the runtime root lock.

## Rights and explicit read-only recipes

Ordinary read allowances, directory reads, and automatic runtime grants are **positive
allowances**, not global read-only denial promises. Existing read/write overlap remains
valid in those policies. Strict read-only intent is an explicit `readOnlyPaths` input
to `PreparedOperationResources.resolve` and participates in its resource generation.

| Grant | File rights | Additional directory rights |
| --- | --- | --- |
| read | `READ_FILE` | `READ_DIR` |
| write | `WRITE_FILE`, `TRUNCATE` on ABI 3+ | `MAKE_REG`, `MAKE_DIR`, `REMOVE_FILE`, `REMOVE_DIR`, `REFER` on ABI 2+ |
| automatic runtime | read plus `EXECUTE` | `READ_DIR` |

An exact file grant does not receive directory creation/removal/rename rights. File
replacement needs the explicitly reviewed parent directory; it cannot be represented
as a promise to create or replace only one future filename. Directory permissions
apply recursively and allow other files within that directory. The Seccomp policy,
DAC and other LSMs still intersect with these rights; a grant is not a guarantee that
an operation will succeed. Strict read-only/read-write operation recipes require ABI
3+, including coverage for truncation through `open(O_TRUNC)`, not merely the named
`truncate` syscalls. Unknown or unavailable required support fails before worker setup.

Strict read-only resources must have an explicit read allowance. Writable ancestry,
writable descendants, resolved aliases and directly granted hardlink aliases reject.
With any writable grants, strict read-only resources must be singly-linked regular
files; this conservatively rejects a hardlink hidden under an otherwise disjoint output
directory and is revalidated before dispatch. Strict read-only directories plus
writable grants reject because unknown descendant aliases cannot be excluded. Ordinary
read directories retain their positive-allowance semantics. The trusted deployment
must also control preexisting bind-mount aliases and mount topology; this is not a
global inode immutability guarantee.

## Caller-owned canonical runtime acquisition

`ManagedRuntimeAcquisitionOwner` provides a separate acquisition route for the fixed
`HOTSPOT_JIT` runtime and `Policy.NO_EXEC_HOTSPOT` process baseline. `create()` allocates
an inert owner. Before calling `start(preparationTimeout, closeTimeout)`, the actual caller
must store that same owner in an application-owned location that remains reachable if
startup throws. The owner captures one environment and retains the original runtime during
startup; it does not accept a caller-selected environment or policy. On success, `start`
returns that runtime and the owner remains its retirement authority.

`status()` is an observation; `retire(timeout)` attempts eligible physical cleanup of that
original runtime. `Active` means startup completed, not that retirement occurred.
`Pending(history)` means physical retirement is not yet proved. `Unknown(history)` retains
an ambiguous installation or cleanup outcome; an ambiguously dispatched operation is not
replayed. Keep the original owner reachable after Pending or Unknown. Installation Unknown
does not prevent an eligible first physical cleanup pass. `Complete(history)` is the
physical retirement receipt for the original runtime, manager and owned work on this route;
it does not remove a process baseline or any already installed filter. Repeated observation
or retirement after physical completion does not repeat the completed drain. `close()` uses
the configured close timeout and reports unresolved Pending or Unknown; a newly encountered
cleanup `Error` is retained and delivered by identity. Kotlin `use` and Java
try-with-resources can suppress cleanup failures on an existing body failure.

A local `create().use { it.start(...) }` whose failure escapes while the caller discards the
owner does not provide a recoverable startup handle. The actual application must establish
and retain its own bounded lifetime before startup, including on failed startup; no generic
registry is supplied by this API. The report application now implements a retained
caller-owned startup root; scoped host review and selected finished-JAR native tests
passed with synthetic test authority. Final delivery remains pending.
Existing `ManagedRuntime.open` declared public behavior remains available, including its
previously identified pre-return recovery gap when failed startup does not return the runtime.

The implementation reshapes private compiler-generated JVM internals; it adds no native API.
Private or package visibility alone is not an authority boundary: partial effectful operations
require the unexported original permit together with the original runtime/state pair and the
appropriate actor and phase. An ordinary whole startup operation applies the same public
bootstrap checks and installs its genuine baseline before opening admission. Access-override
reflection is outside this authority claim, and thread-scoped containment is not a complete
arbitrary-code-execution boundary.

The focused canonical-owner kernel oracle observed successful baseline installation and
real worker Landlock denial, followed by physical retirement. It did not independently
assert that a process-baseline-denied operation fails. The fixed canonical route does not activate Live or confined
operation, helper authority, or other unsupported paths. The remaining issues 338, 461,
507 and 752 and separate B6 and 20+20 qualifications retain their existing scopes; this
route does not by itself close them.

## Caller-owned direct resolution for Legacy

`PreparedResolutionOwner` is the additive caller-owned route for resolving a reviewed
Legacy operation. Create it before calling `resolve`, and keep it reachable until the
result is retired or transferred. Its factory captures one ambient native-engine snapshot
before acquiring a descriptor. The single resolution retains every acquired owner in the
same carrier, including when resolution fails before returning a result; the caller can
still observe and retire that carrier. This removes the pre-return ownership gap for this
route. The existing `PreparedOperationResources.resolve` overloads and their callers remain
unchanged and still have the previously identified pre-return gap. Direct Live and confined
runtime associations are unsupported and refuse before their effects.

A trusted caller can use the new route as follows, with the policy, selected class and
read-only paths matching an independently reviewed artifact. The artifact must be loaded
from an external review of the actual policy and identities; the measured values alone do
not grant review authority.

```kotlin
PreparedResolutionOwner.create().use { owner ->
    val resources = owner.resolve(runtimeProfile, workerPolicy, selectedClass, readOnlyPaths)
    // Load the independently reviewed artifact for these exact resource identities.
    val domain = runtime.prepare(reviewedArtifact, limits, resources)
    // Submit the reviewed operation and await its result.
    check(runtime.drain(timeout) == DrainReport.Complete)
}
```

`owner.retirement()` only observes state; `retryRetirement()` attempts at most one eligible
caller-owned cleanup pass. `Complete(history)` means that aggregate physically settled;
its optional history remains available even after successful cleanup. `Pending(history)`
means work is still active or an eligible pass remains, and does not prove quiescence.
`Unknown(history)` records a dispatched cleanup without an authoritative successful
receipt: it and dependent pins are retained and the dispatched close is never replayed.
Closing an unconsumed owner or its result throws `PreparedResolutionPendingException` for
Pending, or a fresh `PreparedResolutionUnknownException` whose cause is the retained
Unknown history. A newly encountered cleanup `Error` is recorded before its first direct
propagation. If a body has already thrown, Kotlin `use` or Java try-with-resources may
suppress a new cleanup error on that body failure instead of making the cleanup error
primary.

After the exact result is claimed by a Legacy runtime, observation and retry report
`Transferred(history)`; owner and result close are inert. Transferred does **not** mean
physical retirement. Runtime drain is the authoritative physical receipt, and a Pending
or Unknown prerequisite retains dependent resources. For this route, the actual workers
and the Landlock owner retire before dependent grant and artifact pins; incomplete or
unknown prerequisite cleanup cannot release those pins. Direct Legacy setup dispatches
actual Landlock create, rule-add and worker-apply operations through the captured native
engine; retaining the existing native backend does not mean this route has no native effects.
The original Legacy, Live and confined routes keep their existing behavior and release ordering. This direct route does
not close the separately tracked native-Live, broader issue 338/812 or loaded-bytecode
attestation gaps.

## Owner-bound Spring lifecycle

The seven-argument `DomainLifecycle` constructor accepts the original active
`ManagedRuntimeAcquisitionOwner`, its exact returned runtime, the reviewed artifact,
limits, prepared resources, timeout and invocation context bridge. It rejects a foreign
or inactive owner/runtime pair before preparation. Identity and the Active observation
are snapshots, not an exclusive lease: an independently retained owner can retire after
the observation, and runtime admission still fails closed.

Preparation runs on the calling thread. A stop that wins while preparation is active
prevents READY and returns without draining resources still in use. Once the original
preparation actor has returned or thrown from all resource-dependent work and recorded
its result, it hands off the lifecycle boundary. The caller thread need not terminate for
a later eligible retirement pass, but this handoff is not itself a worker-exit or
physical-retirement receipt. A separate retirement that wins without a lifecycle stop
cannot produce a successful READY result.

The lifecycle retires only through the same original acquisition owner. `retirement()`
is passive; `retryRetirement(timeout)` requests one eligible bounded pass. A Pending
stop callback reports only that bounded stop returned. The application must retain strong
references to the lifecycle, acquisition owner and resolution owner outside the Spring
context until actual Complete receipts permit release; retain the originals while
Pending or Unknown, including after context close. An owner-reported Unknown may be passed back to the same owner's guarded retry, which does
not replay an ambiguously dispatched cleanup. An opaque lifecycle failure blocks another
lifecycle dispatch. The owner-bound constructor's `lastDrain()` is null; it does not
invent a drain report from the owner's status.

The original Legacy acquisition now records the exact cleanup failure and its
conservative disposition before checking a retained direct-pending result or merging
failure history. Raw cleanup, proof, and metadata-failure slots retain their original
objects; reconciliation checks suppression by identity before allowing another attempt.
If history cannot be represented or reconciliation fails, retirement remains Unknown
and does not replay the affected close. The existing outward report carries one
Throwable; when suppression is disabled, a distinct retained private failure is not
necessarily visible through that report. The bounded slots do not establish universal
recovery from allocation failure. A post-dispatch retained-pending proof is not claimed
as independently exercised by this host test, and earlier native-acquisition frontiers
remain open.

The Spring lifecycle boundary alone does not provide an application startup root,
failed-main recovery, or independent kernel-containment proof. The report application
adds its own retained caller-owned lifetime, subject to its separate verification gates.

## Report application startup owner

The report sample anchors one original application startup handle before path, review,
resolution or runtime-start effects. `measure` retains the direct resolution owner;
`serve` retains that owner, the canonical acquisition owner, its exact runtime, the
owner-bound lifecycle and the context captured by the actual initializer. Startup is
one-shot for that application classloader, including after a completed or failed run.

A same-JVM caller that catches a failed `ReportApplication.main` on the same original
platform thread can call `failedStartup()` and, when a handle is present, observe its
passive state or request eligible cleanup with `retryRetirement(Duration)`. A foreign
thread cannot use that capability. Pending and Unknown retain the original owners and
any actual captured context; a Spring stop callback or a context that merely appears
inactive is not a physical receipt. If Spring fails before the application can prove
its own context close returned, the context outcome remains Unknown and is not replayed.
The runtime budget is not a total wall-clock bound: the direct resolution owner's close
has no duration. No keepalive thread, process-exit guarantee or generic registry is added.

The app's native tests use separately labelled synthetic fixture review, not an operator's
independent approval. The Legacy rollback boundary retains the limitations described
above; these changes do not extend Live/helper support. The selected finished-JAR native and HTTP tests passed under the recorded test fixture;
the held-child logs were asserted by JUnit but not separately retained.

## Actual operation binding API

The existing `prepare(policy, limits)`, `prepare(reviewed, limits)`, `submit`,
`Mazewall.callContained` and `InvocationHandle` signatures remain unchanged. The
two-argument reviewed overload validates configured review metadata and prepares the
policy; it is **not** actual implementation-byte proof. Framework adapters using it
must not advertise the stronger contract without adopting the additive resource owner.

The existing stronger sequence remains available below; the direct-owner alternative is
described above. Neither route makes a self-generated digest an independent review.


1. Resolve `PreparedOperationResources.resolve(runtime, workerPolicy, actualClass,
   readOnlyPaths)` during trusted platform-thread bootstrap. It holds genuine resource
   descriptors and the actual selected `Class`/classloader. Unsupported origins reject.
2. Compare its measured `implementationSha256` and `resourceGeneration` with external
   trusted expectations. Build/load a reviewed operation document whose exact canonical
   policy, runtime, identities, coverage and approved exceptions match those expectations.
   A digest or a freshly generated document supplies no reviewer authority.
3. Call `runtime.prepare(reviewedArtifact, limits, resources)`. The manager validates
   actual acquisitions, canonical policy/runtime, captured native-engine identity and
   ABI, and resolves the reviewed public JVM method descriptor on the selected class.
    The method's declaring implementation must use the same defining loader and selected
    deployment origin (same nested library for Boot, not merely the same outer JAR).
   Successful claim transfers cleanup to the runtime, once only.
4. Keep using the reviewed adapter's actual target and chain. Core cannot introspect
   arbitrary submitted `Callable` bodies or prove that an adapter invokes its selected
   bean/tool. Bean instance identity, advisor chains and target selection remain adapter
   contracts. The process Koog binding rejects here and belongs to its process adapter.

Unconsumed resource owners must be closed by their caller. Closing a transferred
owner does not revoke its live generation. Public identities remain immutable snapshots;
after retirement, validation rejects the closed owner. Resource generation includes
requested/resolved paths, filesystem object keys, grant kinds, strict-file content
hashes, selected class name, selected implementation object/hash, deployment-tree
descendant identities or selected nested origin, and ordinary URL/Boot loader inputs. It is an opaque
deployment-generation identity, not a portable content-only release identifier.
The strict recipe set is frozen once before acquisition; capture, hash/generation and
ABI3 presence checks all derive from that same snapshot, never from later mutable input.
Standalone validate/unconsumed close are synchronous operations without a deadline API;
managed drain does not synchronously invoke their blocking retirement operations.

## Implementation origin and exact limits

An ordinary finished file JAR, maximum 256 MiB, remains supported,
loaded by the exact bootstrap-defined OpenJDK `jdk.internal.loader.ClassLoaders$AppClassLoader`
that is the JVM system loader, or an ordinary, unsubclassed `URLClassLoader`. Identity
as the system loader alone is insufficient: a configured custom system loader rejects
even if it supplies matching ordinary-JAR URLs, resource bytes and CodeSource metadata.
The selected class's code source and class-resource URL must agree with the pinned
JAR. Bounded loader resource bytes (maximum class 16 MiB) must match its JAR entry.
JAR bytes are hashed through the pinned descriptor, and hashes are rechecked before
setup and dispatch. The selected class object and loader are retained; ownership of
the application's loader is not transferred or closed by the runtime.

### Exploded ordinary-loader deployments

The same exact ordinary loader categories also support a directory CodeSource and file
class-resource URL. The root requires an explicitly reviewed **existing directory** read
grant; exact selected-file grants or automatic classpath grants cannot silently authorize
its directory. A reviewed ancestor-directory grant is accepted with its existing recursive
breadth. No resolver grants the parent of an unresolved leaf or widens policy permissions.

Capture inventories the complete root-relative deployment tree: at most 4096 descendants,
depth 64, and aggregate 256 MiB regular-file content. Descendant symlinks and special files
reject, including empty symlinked directories. Root symlink aliases may resolve through the
existing PreparedPath contract and are revalidated for target switches. Every directory and
file receives its own genuine O_PATH owner; capture rejects changed paths/objects. This uses
existing captured NativeEngine acquisitions and no new FFM layout/downcall. Descendant
owners live in the same retained operation-owner retirement hierarchy as the root and
prepared Landlock ruleset. The original route's fatal-first attempt-all cleanup includes
partial acquisition; the direct-owner route retains dependent pins behind an Unknown close.

`implementationSha256` for exploded roots hashes UTF-8 records sorted by root-relative
Linux path, joined by newline (no final newline): `directory\0<path>` or
`file\0<path>\0<size-in-decimal>\0<lowercase-SHA256-of-file-bytes>`. Paths use `/`;
entry paths may not contain NUL, backslash, absolute/traversal/empty components.
The selected class file must exist in this inventory and its pinned bytes (16 MiB maximum)
must match the actual selected loader resource at the expected root-relative file URL.
Revalidation checks the full inventory, every retained inode identity and all file content;
new/removed resources, identical-byte replacement, mutation and symlink switches reject.
Resource generation separately includes root/descendant object identities and loader inputs.
The hash is a deployment snapshot, not a promise that directory descendants cannot mutate.

### Precisely supported executable Boot deployments

The modern Boot **4.1.1** executable JarLauncher packaging is supported without a Spring
core dependency. The actual class must be defined by the exact
`org.springframework.boot.loader.launch.LaunchedClassLoader` →
`org.springframework.boot.loader.net.protocol.jar.JarUrlClassLoader` → `URLClassLoader`
hierarchy. The first two classes must be defined by an ordinary trusted loader from the
same finished outer JAR; their original supported class-byte SHA-256 values are pinned in
BootArtifact and compared with both the pinned outer entries and defining-loader resources.
A classloader name, system-loader identity or matching claimed URL alone never permits it.
Subclassed, counterfeit/modified loader bytes and launcher definitions from another artifact
reject. Original release fixture hashes/provenance are retained in test resources.

Only actual modern `jar:nested:<outer>/!BOOT-INF/classes/!/` and
`jar:nested:<outer>/!BOOT-INF/lib/<library>.jar!/` origins are represented. Every launcher URL
input must be a distinct supported root in that same finished outer JAR and exist in its
bounded unambiguous entry table. Selected code source, resource entry URL and defining
loader must agree. For application classes, bounded selected bytes come from the pinned
outer entry. For a library, the selected stored inner JAR is read from the pinned outer,
then its bounded unambiguous entry table and selected class bytes are checked. Inner archive
expansion is bounded to 256 MiB and 4096 entries; selected classes are at most 16 MiB.
Traversal/duplicate entries and multiply nested or versioned resource origins reject.

Selected inner libraries additionally require a consistent bounded ZIP32 central directory
and local entry table. The central namespace used by Boot must have unique legal names,
bounded declared sizes/counts and exact one-to-one contiguous local-header mappings.
Local/central names, flags, methods, CRCs, sizes and data descriptors must agree; streamed
content is checked against central metadata as well as the JDK's local CRC validation.
Central-only duplicate/traversal names, aliased offsets, unindexed records, inconsistent
counts/metadata and out-of-bounds structures reject. ZIP64/split/encrypted archives and
alternate Unicode-name extra fields are unsupported and reject rather than acquiring a
second interpretation of the namespace. Validation operates on the same bounded owned
inner bytes; no temporary artifact, loader dependency or extra descriptor is introduced.

`implementationSha256` is the digest of the **entire actual finished outer JAR**, including
launcher, application/resource and inner library bytes. It is not the selected class/inner
JAR hash, manifest label or sidecar echo. Resource generation additionally binds the actual
selected nested origin and loader input snapshot. The outer's genuine owner, not its parent
directory, supplies the exact read grant unless an operator explicitly reviewed a directory.
Outer mutation, replacement and symlink switches reject before setup/dispatch. Selected
method implementations from another nested library reject even within the same outer.

Other Boot versions/loader bytes, Boot's exploded launcher file inputs, custom loader
hierarchies, versioned origins and unsupported resource grammars remain explicit rejections.
PropertiesLauncher-specific deployment recipes are not independently qualified: any external
or unsupported loader input rejects even with original launcher classes. Exploded ordinary-loader
applications are distinct from Boot's exploded launcher. Combined Spring advisor/JDBC,
clean publication/distribution and release-native
gates remain coordinator-owned; core fixture acceptance does not claim those gates closed.

O_PATH pins the object used for rules, **not immutable content**. Hashes detect observed
changes but cannot prevent a trusted sibling from modifying an inode between validation
and execution. Replacement cannot silently widen worker access: rules remain bound to
the old objects. A selected `Class` and matching loader resources are not an attestation
of already-defined JVM bytecode after instrumentation/redefinition or in-place changes
before resolution. Trusted immutable deployment/no redefinition is a prerequisite for
the supported first-party contract; stronger loaded-bytecode/physical-content closure
remains an unclosed C6/C4 gate. Directory object identity is not a recursive immutable
snapshot of all descendants. Already-open descriptors/mappings, shared heap and unrelated
executors remain outside this thread-scoped boundary.

## Evidence

- `OwnedRuntimeTest`: required missing read/writable leaf preparation rejects; existing
  readiness, cancellation, fatal generation, admission and captured-engine tests retained.
- `PreparedResourceIdentityTest`: genuine descriptor identity/retirement, symlink and
  directory replacement, EPERM/EACCES acquisition failures and fatal-first rollback.
- `PreparedResourceRecipeTest`: ordinary positive allowances, explicit writable-ancestry
  and hardlink contradictions, strict-file content/generation mutation.
- `PreparedOperationResourcesTest`: real compiled JAR and selected loader, actual SHA,
  identical-byte object replacement, stale configuration/member and ABI2 rejection,
  inherited method from a different implementation artifact rejected.
- `PreparedSystemLoaderReviewTest`: a disposable custom system loader defines different
  bytes while serving original reviewed JAR resources; rejection plus ordinary JDK
  application-loader acceptance. Mutable strict-set clearing during genuine native
  acquisition remains rejected on ABI2 despite blocking named truncate syscalls.
- `PreparedRetirementTest`: actual worker/validator lifetime, interrupted cleanup,
  retained genuine descriptors and fatal-first close outcome without false completion.
- `DeploymentOriginTest`: real compiled complete exploded roots and executable Boot4.1.1
  `java -jar` packaging (application classes and stored inner libraries), selected external
  member rejection, all-resource mutation/add/remove, identical-byte replacement, symlink
  switch, URI-escaped deployment paths, real modified-loader-byte rejection and byte bounds.
- `ExplodedOwnerTest`: genuine leaf/directory/root acquisition, fatal close and fatal
  replacement rollback preserve original failures and retire every independent actual owner.
- `DeploymentOriginNativeTest`: executable Boot application/library and ordinary exploded
  children; both installed workers read selected resources and deny a sibling secret,
  incomplete drain retains actual O_PATH/ruleset owners, complete physical retirement
  closes them. No invented FD or restriction installation in the host JUnit worker.
- Disposable `ExactClasspathGrantNativeTest`, `CanonicalPolicyArtifactNativeTest` and
  `PreparedOperationNativeTest`: sibling denial, exact pinned artifact/replacement,
  actual reviewed selected JAR, template mutation/removal/rename denial, reviewed output
  create/truncate/cross-directory rename/remove, retirement, and preopened write capability
  remaining outside the boundary followed by stale-snapshot rejection.
  Review fixtures additionally cover bounded external/manager validation stalls,
  retained entire FD/ruleset hierarchy, blocked genuine close/fatal outcome, and both
  required workers' EPERM installation failures followed by real-owner retirement.
  A fixture-only barrier on actual worker-reference metric observation additionally
  forces genuine cleanup failure/physical exit between the old null-failure sample and
  the completion predicate; ordinary/fatal cases require truthful first/later drains and
  failing close. Pure lifecycle tests cover conservative pending and terminal errors;
  atomic terminal tests distinguish release failures from an unreleased wait-abort owner.

Focused ABI and architecture/Detekt checks accompany the slice. Full-build/container,
combined framework Boot consumer and distribution gates remain coordinator-owned.
