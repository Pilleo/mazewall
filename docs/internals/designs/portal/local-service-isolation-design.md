# Local Service-Isolation Portals

## Scope

The first release is framework-agnostic local service isolation. It keeps an
existing Java or Kotlin service interface on the host and executes its
implementation in a restricted local child JVM. It is not a microservices
framework, a remote-execution protocol, or a dependency-injection framework.

This document defines the framework-agnostic Portal runtime. Optional framework
adapters consume its owned execution contract without adding Spring/Koog
dependencies to `:portals:runtime` or altering the boundary defined here.

The generated boundary currently supports copied primitive values, strings,
byte arrays, `UUID`, `Instant`, `BigDecimal`, enums by constant name, and
validated records/constructor-backed value objects composed from those types.
It rejects streams unless an explicit snapshot adapter is configured. An
inbound `InputStream` opts in only with
`@PortalInputSnapshotAdapter(maximumBytes = ...)`: the generated host adapter
copies at most that many bytes without closing the caller-owned stream, while
the worker receives a fresh finite stream. It never carries mutable identity or
Java serialization across the process edge.

## Service manifest

Each generated service contributes one immutable manifest. The manifest is
data, not a launch command: it names the service contract, worker-only
implementation and dispatcher, value-schema identity, isolation group,
instance mode, policy profile, and worker artifact. Launch-specific classpath
materialization belongs to the runtime and is deliberately not encoded into a
service definition.

## Worker bundles

The host supplies each named worker artifact as a `PortalWorkerArtifact`: a
bundle path paired with its SHA-256 digest. Before a broker starts, the runtime
verifies that digest, extracts the ZIP into a digest-addressed cache directory,
and builds the child JVM classpath only from sorted JARs under the extracted
`lib/` directory. It never substitutes the broker's own classpath.

The runtime derives the cache identity from both the verified bundle digest and
the manifest schema ID. A changed contract schema therefore receives a fresh
materialization even when it refers to the same bundle artifact.
The cache root and every published cache entry must be real directories, never
symbolic links; an application-controlled redirect is a startup failure.

## Resource profiles

`policyProfile` is resolved by the host's `PortalRuntimeConfig.workerProfiles`
registry before any worker is spawned. A profile may declare an explicit JVM
heap maximum, a cgroup v2 memory maximum, and a cgroup v2 CPU quota/period. A
memory-limited profile must reserve at least 32 MiB above its heap maximum for
JVM non-heap memory.

Every manifest profile must resolve. A profile with CPU or memory limits also
requires an application-supplied delegated cgroup v2 root. Portal validates the
requested controllers, creates a runtime-owned child cgroup, writes limits,
and attaches the worker PID before it can publish READY or construct guest
services. Broker launches carry a required session/PID parent authorization frame;
the child waits for it before containment and guest initialization. The parent sends
it only after cgroup setup succeeds. Missing delegation, unsupported controllers, or cgroup write failure
are startup failures; Portal never falls back to an unbounded worker.

The parent session/PID acknowledgement is distinct from final readiness. The host captures
the verified artifact and actual ordered classpath-content generation, exact isolation-group
manifest generation and JVM runtime identity before launch. The child measures its own
classpath, artifact and defining-loader manifests and rejects a changed requested generation
before guest construction. It then installs and validates the actual required receipt
(new process-wide Seccomp and Landlock stages, no fallback, exact static baseline policy),
constructs the group dispatcher and sends a strict bounded format-2 socket ready record.
The host requires the exact session, child PID, protocol and measured generation plus
required protection, checked operator launch-contract and parent resource evidence under
the existing startup deadline. Stdout READY cannot
substitute for this record, even after a matching parent acknowledgement. The receipt
remains strongly rooted for the entire OS process lifetime, even if guest-created non-daemon
threads outlive main, initialization fails, or other shutdown hooks complete. The static
unsupervised baseline has no Java close/clear at hook completion. OS death reclaims native
resources; this does not claim Java receipt retirement or supervised-lease release.

Before launch, the host verifies the complete cached file set and each file's digest against
entries of the unchanged verified archive, then requires the exact archive JAR classpath.
Stable cache replacement, missing/extra files and symlinks fail; this association is not
deferred to C4 concurrent pinning. Generation measurement uses bounded work and the same
absolute startup/cancellation authority. Its physical blocking-I/O task remains owned if
interruption cannot end a filesystem operation; close remains retryable until it actually
ends. Deadline/cancellation returns do not launch a late child when that task later completes.
Cancellation callbacks are also physically owned asynchronous work: interrupting a channel
may wait on native I/O in its close callback. Close joins both the measurement and callback
threads under its budget and retains ownership while either remains alive.

Profiles do not vary the process sandbox in this release. Every worker retains
the existing fail-closed JVM filesystem allowlist, process-creation denial, and
network denial. Per-profile readable paths and arbitrary syscall policies remain
deferred.

## Diagnostics and failure evidence

Prepared operations additionally expose passive immutable execution diagnostics;
see [the shared observation contract](../core/execution-diagnostics.md). The
runtime keeps its existing public health model compatible. Diagnostic generation
comes from successful required readiness, never from profile configuration.

`PortalRuntime.health()` is read-only local diagnostics: for each group it
reports availability/state-loss, the worker PIDs still live in that runtime,
the resolved policy profile and configured resource contract, and the most
recent invocation's queue-admission and worker-execution
durations. These clocks are observations under the existing single absolute
deadline; they do not add a worker management endpoint or reset a call deadline
between phases.

For Gradle consumers that opt into a dedicated worker source set, the codegen
plugin supplies `portalWorkerBundle`. It packages the generated worker JAR and
the declared `portal-worker` bootstrap/runtime plus worker runtime JAR
dependencies as deterministic `lib/` entries. The
bundle is the deployment input. `publishPortalWorkerArtifactMetadata` embeds it as
opaque ZIP data under a digest-addressed classpath resource, with strict format-2
resource-name/digest metadata and an explicit artifact index. No build-machine path
is recorded. `PortalRuntimeConfig.load()` resolves exactly one bundle resource,
copies at most 64 MiB transactionally into a verified cache file, then uses the
existing bounded ZIP extractor. Missing/duplicate resources, unknown metadata,
digest mismatch and symlinked cache components reject before launch. The runtime
does not interpret format-1 local-path metadata; an application can still explicitly
supply a local `PortalWorkerArtifact` through configuration. An explicit registry
entry overlapping generated metadata must match the generated resolved artifact.
The bundle task also emits a sibling `.sha256` file.

Worker implementation/runtime configurations are independent of host implementation
and runtime-only dependencies. Declare the service API and required worker runtime
explicitly, for example in the monorepo:

```kotlin
val portalWorker by sourceSets.creating
dependencies {
    implementation(project(":service-api"))
    "portalWorkerImplementation"(project(":service-api"))
    "portalWorkerImplementation"(project(":portals:worker"))
}
portalCodegen { workerSourceSet.set("portalWorker") }
```

The host JAR contains stubs/manifests and opaque bundle bytes, not directly loadable
guest dispatchers. This separates classpaths; environment authority and required
generation-bound readiness are distinct launch contracts. Receipt-backed generation
readiness and immutable operator launch profiles are implemented. See the
[P3 launch contract](../enforcer/process-portal-design.md#required-operator-launch-profiles-p3)
for inherited-compatible defaults, explicit trusted operator preparation, required named
selection, closed noncredential marker/resource checks,
versioned authorization and ownership limits. C4 real-object pinning between hashing,
installation and class loading and actual production launch/cgroup approval remain separate gates.

The historical worker-classpath system property is accepted only by the
repository's marked test execution. Production `ProcessBroker` startup requires
a non-empty classpath supplied by the verified runtime artifact path.

Extraction is transactional: it is staged beneath the cache and published only
after every entry succeeds. Bundle entries are bounded in count and expanded
size, reject directory and traversal paths, and cannot escape the staging
directory. A missing artifact, incorrect digest, malformed archive, or bundle
without worker JARs prevents worker startup.

This separation matters even for first-party code. It makes the exact worker
launch inputs reviewable and reproducible, keeps accidental host-only runtime
dependencies out of the child, and preserves the later option to distribute a
curated worker runtime without changing service contracts.

`PortalServiceId` is the stable explicit selector for multiple intentional
implementations of a contract. A lookup without an ID succeeds only if exactly
one binding exists. A runtime rejects duplicate IDs and duplicate default
bindings before creating a worker.

## Process and ownership rules

Runtime configuration copies manifest/profile/artifact collections; later caller
mutation cannot change validated lazy startup. Application acquisition ownership is
registered before broker creation/startup. Close stops admission/publication, joins
pending acquisition and retains failed cleanup owners for a later retry. Existing
proxies check runtime admission before a call, and fatal startup/rollback identity is
preserved. Broker process/native retirement continues to use its own lifecycle.

- The host resolves generated stubs only. It must neither initialize nor
  construct a guest implementation.
- The worker verifies its manifest and generated dispatcher after containment
  installs and before it publishes readiness. It constructs each generated
  service through the worker-local registry once per isolation group, then
  binds the generated dispatcher to that exact instance; dispatch never
  performs a second reflective construction.
- Generated registrations use a group-local construction context: a concrete
  collaborator with exactly one public constructor is built in the worker and
  shared within that group. Interfaces, abstract classes, platform types,
  ambiguous constructors, and cycles fail with a collaborator diagnostic;
  they require an explicit worker factory rather than capture of a host object.
- The broker-to-worker capability-FD direction, copy-in/copy-out payload
  semantics, one absolute RPC deadline, and failed-worker retirement remain
  unchanged.
- Missing metadata, worker artifacts, generated stubs, or containment cause a
  clear fail-closed error. There is no in-process implementation fallback.

## Initial service modes

`STATEFUL_SINGLETON` means one worker-side instance for an isolation group and
one atomically admitted active invocation. Overlap rejects with NOT_STARTED before
dispatch. Unknown completion or fatal invocation failure terminally loses the group;
later completion cannot reopen it. After that worker dies, the group reports state loss and
requires explicit recreation. `STATELESS` permits independently managed,
explicit capacity replacement, but never replays a call whose completion is
unknown.

## Non-goals

This release excludes Spring, remote/pod/sidecar backends, backend plugins,
service discovery, independent deployment versions, host callbacks,
bidirectional FDs, automatic retries, state replication, returned streams,
and arbitrary object identity across the boundary.
