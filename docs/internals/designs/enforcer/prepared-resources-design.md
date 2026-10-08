# Prepared core resource and implementation identities

Task 5 C4/C6 slice; ordinary finished-JAR binding is implemented. This is not a
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
generations retire on the trusted manager before explicit recreation. Cleanup attempts
all independent releases, retains failures, and uses existing `ResourceRetirement` /
`CleanupFailures` fatal-first ordering. Closing never removes a living worker's filters.

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
   The method's declaring implementation must originate in the same selected JAR.
   Successful claim transfers cleanup to the runtime, once only.
4. Keep using the reviewed adapter's actual target and chain. Core cannot introspect
   arbitrary submitted `Callable` bodies or prove that an adapter invokes its selected
   bean/tool. Bean instance identity, advisor chains and target selection remain adapter
   contracts. The process Koog binding rejects here and belongs to its process adapter.

Unconsumed resource owners must be closed by their caller. Closing a transferred
owner does not revoke its live generation. Public identities remain immutable snapshots;
after retirement, validation rejects the closed owner. Resource generation includes
requested/resolved paths, filesystem object keys, grant kinds, strict-file content
hashes, selected class name, and selected implementation object/hash. It is an opaque
deployment-generation identity, not a portable content-only release identifier.

## Implementation origin and exact limits

The supported implementation is an ordinary finished file JAR, maximum 256 MiB,
loaded by the application loader or an ordinary, unsubclassed `URLClassLoader`.
The selected class's code source and class-resource URL must agree with the pinned
JAR. Bounded loader resource bytes (maximum class 16 MiB) must match its JAR entry.
JAR bytes are hashed through the pinned descriptor, and hashes are rechecked before
setup and dispatch. The selected class object and loader are retained; ownership of
the application's loader is not transferred or closed by the runtime.

Nested Boot JARs, exploded implementation origins, versioned class-resource origins
not represented by this ordinary-entry check, and custom loaders reject explicitly.
Exploded *read directory grants* still work through ordinary preparation; they are not
exploded actual-implementation attestation. Supporting other packaging needs a scoped
extension and native/packaged acceptance, not a hash of a claimed label.

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
- Disposable `ExactClasspathGrantNativeTest`, `CanonicalPolicyArtifactNativeTest` and
  `PreparedOperationNativeTest`: sibling denial, exact pinned artifact/replacement,
  actual reviewed selected JAR, template mutation/removal/rename denial, reviewed output
  create/truncate/cross-directory rename/remove, retirement, and preopened write capability
  remaining outside the boundary followed by stale-snapshot rejection.

Focused ABI and architecture/Detekt checks accompany the slice. Full-build/container,
combined framework, packaged Boot and distribution gates remain coordinator-owned.
