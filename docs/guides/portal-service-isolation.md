# Portal Service Isolation

Portals move a value-oriented Java or Kotlin service implementation into a
restricted local child JVM while callers retain its interface. This is a local
process-isolation mechanism, not remote execution, a general DI framework, or
a replacement for normal service boundaries.

## What crosses the boundary

Generated stubs copy request and response values. Supported values include
primitives, strings, byte arrays (`byte[]`/`ByteArray`), `UUID`, `Instant`,
`BigDecimal`, enum names, copied `List`/`MutableList` and `Map`/`MutableMap`
contracts, and validated constructor-backed records/objects composed from those
values. `Capability.ReadFd` is the explicit advanced inbound capability type. It
is never returned and never flows worker-to-host.

Paths, callbacks, object identity, Java serialization, returned streams, and
reactive streams are unsupported. Raw `InputStream` contracts remain rejected
unless an inbound parameter explicitly uses
`@PortalInputSnapshotAdapter(maximumBytes = …)`. That adapter copies at most
the declared count without closing the caller's stream, and the worker receives
a fresh finite stream. `PortalInputSnapshot` may spill into an
operator-selected directory and owns/deletes its spill data.

## Build and launch inputs

Apply the portal codegen plugin and choose a dedicated worker source set. It
generates host stubs, worker dispatchers, immutable service manifests, and a
`portalWorkerBundle` ZIP with its SHA-256 sidecar. Configured stub, dispatcher,
manifest, and worker-resource output directories must be pairwise disjoint
non-overlapping trees (including relative/symlinked aliases); overlapping
publication plans fail before modifying prior generated files. The bundle
contains only JARs under `lib/`.

For a generated Gradle build, `publishPortalWorkerArtifactMetadata` publishes
opaque bundle bytes and digest-addressed resource metadata in the host artifact.
`PortalRuntimeConfig.load()` discovers those resources; the application still
supplies the required named resource profiles before opening the runtime. It can instead
provide the same `PortalWorkerArtifact(path, sha256)` through
`PortalRuntimeConfig`; a conflicting override is rejected. The runtime verifies
the archive digest, extracts it with entry and expanded-size limits, and
launches the child JVM only from the verified extracted JARs. It never uses the
host application's classpath as a fallback.

The historical worker-classpath property is test-only. Production startup must
receive the classpath derived from a verified worker bundle.

Missing manifests, generated stubs, containment support, artifacts, digest
verification, or dispatcher descriptors are startup failures. The host never
constructs the implementation as a substitute.

## Resource profiles

The generated `policyProfile` is a host-selected key. Pass a matching typed
profile to `PortalRuntimeConfig`; unknown keys fail while opening the runtime.

```kotlin
val profiles = mapOf(
    "baseline" to PortalWorkerProfile(),
    "restricted-parser" to PortalWorkerProfile(
        maxHeapBytes = 64L * 1024 * 1024,
        memoryMaxBytes = 128L * 1024 * 1024,
        cpuLimit = PortalCpuLimit(Duration.ofMillis(50), Duration.ofMillis(100)),
    ),
)
val runtime = PortalRuntime.open(
    PortalRuntimeConfig(
        manifests = manifests,
        workerArtifacts = artifacts,
        workerProfiles = profiles,
        cgroupRoot = delegatedCgroupRoot,
    ),
)
```

Limited profiles require a writable delegated cgroup v2 root containing the
requested `memory` and `cpu` controllers. Portal attaches the child PID and
writes limits before worker readiness. It fails closed if that cannot happen.
The minimum 32 MiB gap between `memoryMaxBytes` and `maxHeapBytes` is a validation
floor, not proof that a particular JVM/workload has sufficient non-heap headroom.
The fixed Landlock/Seccomp baseline remains identical for
all profiles; readable-path and syscall differences are not profile features.

## Usage rules that differ from threads

- Reuse one application-owned runtime and its bindings. Each isolation group has
  its own worker pool; creating a runtime per request repeats startup and caches.
  Close the runtime at application shutdown and retain failed cleanup for retry.
- Values are copied. Host object identity, static fields, DI instances, thread-local
  context and transactions do not cross. Pass required context as explicit bounded
  DTO fields; those fields do not automatically authenticate a caller.
- `STATELESS` workers still reuse their own service instances and caches. Calls have
  no worker affinity or per-call reset. Keep request/tenant state local; choose the
  existing singleton mode deliberately when the application needs retained state.
- A timeout or cancellation does not undo work. Use invocation evidence; never infer
  that an unknown call is safe to retry. Result delivery and physical execution end
  are different, including when a future has already completed exceptionally.
- Codecs, DTO constructors and prepared-call mappings run in the host. Keep them
  small and bounded; avoid blocking callbacks, cyclic waits and locks held across
  calls. Guest background tasks can outlive a method and consume group resources.
- Worker dependencies must work under the fixed filesystem/network/process policy.
  Use explicit file grants and worker-local construction. Qualify startup and later
  replacement under the host's policy; initial warm-up cannot authorize a later spawn.
- Consume results with a finite outstanding window and release completed handles
  and values. Neither admission counts nor encoded frame limits bound objects the
  application keeps reachable after completion.

The [proposed safeguards and delivery plan](../internals/designs/portal/portal-developer-experience.md#10-process-boundary-usage-safeguards)
add generated snapshots, aggregate capacity checks and runnable qualification cases.
Those additions remain planned; existing `prepare` requires arguments to remain
stable until mapping/encoding ends.

## Memory cost

Each child JVM adds its own heap, metadata, JIT/GC state, native allocations and
thread stacks. Application caches/models are duplicated per worker. Shared libraries
and compatible CDS archives can share read-only pages, but ordinary mutable heaps
remain separate. Compared with threads, expect a higher baseline; compared with a
separate JVM API service, measure the complete host-plus-server deployment.

The normal default launch uses `-Xmx64m`. This is a heap maximum, not an immediate
allocation or a process-memory limit. Custom heap/cgroup limits must be configured
together. Per-worker cgroup limits also need host/sibling headroom in the parent
deployment; OOM termination is a possible limit outcome, not successful completion.
The child is attached after launch and before guest readiness; existing bootstrap
memory charges can remain in the parent, which also needs a deployment budget.

During a call, original values, encoded copies, decoded objects and retained results
may coexist. More groups/workers and queued/retained results multiply these costs.
Use small measured pools, bounded result windows, compact DTOs and worker-local
intermediate computation. RSS sums can double-count shared pages; report PSS and
cgroup usage with their scope. See the [memory model and measurement plan](../internals/designs/portal/portal-developer-experience.md#11-memory-model-and-aggregate-capacity)
for the proposed capacity checks and explicit measurement limits.

## Lifecycle and failure semantics

An isolation group has a stable identity, policy profile, worker artifact, and
instance mode. Stateful groups must treat worker loss as state loss; callers
must recreate the runtime/group state rather than assuming a retry is safe.
After an unknown-completion failure, the runtime rejects later stateful calls
with `PortalStateLostException`; `PortalRuntime.health()` reports the group as
`STATE_LOST`. Stateless capacity replacement never replays an indeterminate
call.

`PortalCallException.evidence` distinguishes a call that did not start, one
that completed with a remote reply, and one with unknown completion. Treat an
unknown completion as an application-level decision point, not a retry signal.

Host SIGKILL bypasses application close hooks. Cleanup after host
SIGKILL is not yet qualified, especially with a hung guest call, non-daemon guest
threads or blocked shutdown hooks. RPC closure or worker main return is not proof
of process death. Deployments requiring crash cleanup must qualify their process-tree
supervision; the [planned lifecycle controls](../internals/designs/portal/portal-developer-experience.md#10-process-boundary-usage-safeguards)
test this separately from graceful close.

`PortalRuntime.health()` is local and read-only. It includes each group's
resolved profile and resource contract alongside worker PIDs, state, and latest
call timing; it does not perform an RPC, restart a worker, or alter state.

When a worker-side business method throws, the host receives
`PortalRemoteCallException` with `COMPLETED` evidence and a bounded remote
exception type and message. The protocol never serializes arbitrary exception
objects or their cause graphs. Generated stubs recreate only explicitly
declared exception types from a fixed host-side allow-list, using public
message/no-argument constructors; undeclared types remain remote-call failures.

## Inspection

Use `portalCheck` to validate generation and `portalDescribe` to print the
configured service ID, contract, implementation, group, mode, policy, and
worker-artifact key without running guest code.

## Non-goals

This initial release deliberately excludes Spring integration, remote workers,
sidecars, service discovery, host callbacks, bidirectional capabilities,
automatic retries, and transparent state replication.
