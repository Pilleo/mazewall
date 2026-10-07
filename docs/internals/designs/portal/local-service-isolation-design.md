# Local Service-Isolation Portals

## Scope

The first release is framework-agnostic local service isolation. It keeps an
existing Java or Kotlin service interface on the host and executes its
implementation in a restricted local child JVM. It is not a microservices
framework, a remote-execution protocol, or a dependency-injection framework.

Spring integration is explicitly out of scope for this release. A later
integration may consume the same manifest, but it must not add Spring
dependencies to `:portals:runtime` or alter the runtime contract defined here.

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
services. Missing delegation, unsupported controllers, or cgroup write failure
are startup failures; Portal never falls back to an unbounded worker.

Profiles do not vary the process sandbox in this release. Every worker retains
the existing fail-closed JVM filesystem allowlist, process-creation denial, and
network denial. Per-profile readable paths and arbitrary syscall policies remain
deferred.

## Diagnostics and failure evidence

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
bundle is the deployment input. `publishPortalWorkerArtifactMetadata` embeds
its path and digest in the generated host resources, which `PortalRuntime.open()`
loads through an explicit artifact index; callers need not maintain a worker
classpath or dispatcher system property. An explicit registry is still allowed
when it exactly matches generated metadata. The bundle task also emits a
sibling `.sha256` file.

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
