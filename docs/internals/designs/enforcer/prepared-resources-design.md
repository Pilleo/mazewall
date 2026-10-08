# Prepared core resource and implementation identities

Task 5/8 C4/C6 slices; ordinary JAR, bounded exploded and supported executable Boot
origin binding are implemented. This is not a
claim that the combined integration release gates or physical bytecode closure pass.

## Ownership and preparation

`ManagedRuntime` retains its captured `InstallationEnvironment`. For filesystem
policies, the manager resolves every explicit grant, `java.home`, and every nonempty
classpath entry before constructing workers. Missing grants or runtime entries fail;
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
worker, then acquires the public operation owner's validation monitor before releasing
its shared resource set and ruleset. External/manager validation and a stalled close can
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

## Actual operation binding API

The existing `prepare(policy, limits)`, `prepare(reviewed, limits)`, `submit`,
`Mazewall.callContained` and `InvocationHandle` signatures remain unchanged. The
two-argument reviewed overload validates configured review metadata and prepares the
policy; it is **not** actual implementation-byte proof. Framework adapters using it
must not advertise the stronger contract without adopting the additive resource owner.

The stronger sequence is:

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
prepared Landlock ruleset. Fatal-first attempt-all cleanup includes partial acquisition.

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
