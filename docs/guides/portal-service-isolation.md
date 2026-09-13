# Portal Service Isolation

Portals move a value-oriented Java or Kotlin service implementation into a
restricted local child JVM while callers retain its interface. This is a local
process-isolation mechanism, not remote execution, a general DI framework, or
a replacement for normal service boundaries.

## What crosses the boundary

Generated stubs copy request and response values. Supported values include
primitives, strings, byte arrays, `UUID`, `Instant`, `BigDecimal`, enum names,
and validated constructor-backed records/objects composed from those values.
`Capability.ReadFd` is the explicit advanced inbound capability type. It is
never returned and never flows worker-to-host.

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
`portalWorkerBundle` ZIP with its SHA-256 sidecar. The bundle contains only
JARs under `lib/`.

For a generated Gradle build, `publishPortalWorkerArtifactMetadata` publishes
the bundle path and SHA-256 under the host's generated resources, so
`PortalRuntime.open()` discovers it automatically. An application can instead
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
The 32 MiB gap between `memoryMaxBytes` and `maxHeapBytes` is mandatory JVM
non-heap headroom. The fixed Landlock/Seccomp baseline remains identical for
all profiles; readable-path and syscall differences are not profile features.

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
