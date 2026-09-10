---
title: "Process Portal: Broker/Worker Isolation for JVM Apps"
scope: "enforcer"
critical_syscalls: ["execve", "clone", "sendmsg", "recvmsg", "openat2"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/JvmChildProcess.kt"
  - "platform/src/main/kotlin/io/mazewall/core/PrivateUnixEndpoint.kt"
  - "platform/src/main/kotlin/io/mazewall/core/SocketManager.kt"
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
keywords: ["process-portal", "broker", "worker", "SCM_RIGHTS", "codegen"]
---

# Process Portal: Broker/Worker Isolation

> **Status:** Designed. Platform spawn/`SCM_RIGHTS` extract exists. Hand-written `:portal` runtime (`ProcessBroker` pool + Unix RPC + broker→worker FDs) exists. `:portal-worker` module contains guest implementations. `:portal-codegen` KotlinPoet plugin generates host stubs and worker dispatchers; `Portal.create` fails closed if the stub is missing.
>
> This is an **application** portal (Chrome renderer model). It is not the **syscall** supervisor in [supervisor-proxy-design.md](supervisor-proxy-design.md) (`USER_NOTIF` + `SECCOMP_IOCTL_NOTIF_ADDFD`).

Thread-scoped mazewall cannot stop native ACE from pivoting through a sibling heap. Separate OS processes can. The missing piece is ergonomics: generated stubs so a first-party module looks like a local call while running in a pooled, process-wide sandboxed JVM.

Presentation context: [article0a-history.md](../../../presentation/article0a-history.md), [article6-isolates.md](../../../presentation/article6-isolates.md) § Portals.

## Locked decisions

| Item | Choice |
|---|---|
| Isolation | Child HotSpot JVMs + `installOnProcess`. No Wasm. |
| Audience | Phase 1: first-party risky modules. Plugin classpath rules stay in this spec. |
| Lifecycle | Pooled, long-lived workers. Not spawn-per-call. |
| Boundary | Copy-in/copy-out data **plus** capability FDs, **broker → worker only**. |
| Codegen | KotlinPoet, Gradle plugin module only (not runtime). |
| Host fallback | Never instantiate guest `Impl` in the broker. Fail closed if the stub/worker is missing. |

## Authoritative lifecycles

The broker pool is published transactionally. Its closed lifecycle is `New → Starting → Running
→ Closing → Closed`, with `Failed` retaining startup/cleanup failure until cleanup is retried. `Starting` workers
remain provisional: the idle queue and live-slot registry receive no entry until every configured
worker has connected and reported readiness. A single monotonic deadline (30 seconds by default)
covers every initial accept and readiness observation. Failure of any pool member retires and reaps
the complete provisional batch. A separate provisional-owner registry covers partial acquisition
and accepted-but-unclaimed sockets. Shutdown cancels startup, joins its completion boundary, and
publishes `Closed` only after all provisional processes, pumps, sockets, and endpoints retire.
Pre-native cleanup failures keep their owner registered for a later close retry; completed cleanup
steps are never dispatched twice. An acquisition remains registered until the launcher has either
published the child under the same retirement lock or definitively failed, so a startup join timeout
or interruption cannot orphan a late child. Cleanup drains independent resources after ordinary or
fatal failures, publishes `Failed`, and releases concurrent close waiters before rethrowing the
fatal-first aggregate. `Closed` therefore never precedes acquisition completion and child reaping.

Worker replacement is deliberately outside the failed call's result. A failed or timed-out worker
is destroyed and reaped first; replenishment runs independently, is logged separately, and cannot
replace or delay the original failure. Checkout, return, replacement, and shutdown consult the same lifecycle authority,
so a slot cannot be returned after closing admission begins.

A fatal broker call error retires and reaps its checked-out worker before propagation. The
original error retains cleanup failures as suppressed evidence; replacement remains separate
maintenance. Each child output pump drains incrementally into at most 64 diagnostic lines of
1,024 characters, records truncation/drop counts and stream failures, and recognizes readiness
only from an exact complete sentinel line. Child output is never appended to a shared file or
forwarded continuously into broker logs. Startup failures carry the bounded diagnostic snapshot.

The worker request loop has the closed states `AwaitingRequest → Dispatching → SendingReply`.
`ReplySucceeded` is the only transition back to `AwaitingRequest`; `ReplyFailed` terminates the
channel. Builtin and generated methods are resolved before one dispatch-failure boundary, which
deliberately unwraps reflective invocation failures. Consequently a partial response write closes
the channel and cannot be followed by an error frame. The READY sentinel is emitted only after
containment, dispatcher publication/guest construction, and channel initialization have succeeded.
An `InvocationTargetException` is unwrapped through any target `Throwable`: ordinary exceptions
become one error response, while fatal errors retain their identity, send no response, close owned
capabilities and transport, and receive capability then transport cleanup failures only as
suppressed evidence.
See [the portal lifecycle diagram](../../../diagrams/portal_lifecycle.puml).

## Architecture

Broker request transmission produces `AwaitReply`; worker events after `Stopped` produce a
`PostTerminalObservation` without another send or close effect. The single worker evaluator
owns dispatch-result transitions. Resource cleanup shares platform's fatal-first,
identity-deduplicating failure fold while each owner retains its own cleanup deadlines.

```
Broker JVM                         Worker JVM (pool member)
─────────                          ───────────────────────
app → generated stub
        Unix socket RPC            worker main
        [header + payload]         connect IPC
        [SCM_RIGHTS FDs]           ContainedExecutors.installOnProcess(policy)
        ← result bytes             generated dispatcher → guest impl
                                   openat/connect blocked; uses granted FDs
```

Spawn workers **before** the broker calls `installOnProcess`. Children inherit seccomp; `SupervisorDaemonManager.refuseSpawnIfParentIsFiltered()` already encodes this.

After IPC connection and channel initialization, the worker installs one permanent baseline
through `ContainedExecutors.installOnDedicatedProcess`: the classpath/`java.home` filesystem
allowlist plus `denyProcessCreation` and `denyNetwork`. This internal entry requires Landlock
TSYNC (ABI 8+) and rejects fallback configuration. The ordinary installer applies Landlock
before Seccomp, records each irreversible stage, and synchronizes both across existing JVM
threads. Older kernels fail worker startup; there is no thread-only worker baseline.
Dispatcher validation and guest construction follow containment. Any initialization or readiness
publication failure closes the owned channel, preserving the original failure and suppressing
cleanup failures. Only after READY succeeds does ownership pass to the execution driver.
Extra readable paths are deferred.

`read`/`write` on inherited FDs remain legal; see [security-considerations.md](../core/security-considerations.md).

Before serving the first request, `PortalWorkerMain` strictly validates generated dispatcher entries
and captures a local `PortalDispatcherTable`. Its startup builder copies method IDs into immutable
keys, rejects duplicates, and publishes a copied handler map exactly once; publication invalidates
the builder. Malformed strict entries abort startup. The legacy registry's tolerant bootstrap is
available only before publication, and no registry method can replace the published mapping.
This freezes dispatch authority, not mutable application state inside a guest implementation.
Portal method IDs, frame bytes, payload copy boundaries, and capability-FD ownership are unchanged.

## IPC and FDs

Transport: `PrivateUnixEndpoint` + `SocketManager` (`AF_UNIX`, `SOCK_CLOEXEC`).

Payload: kotlinx.serialization (already in the repo) or an explicit record schema. Size and nesting caps on unmarshal. No `java.io.Serializable`. No live Java object identity.

Capabilities: broker opens with `openat2` + `RESOLVE_BENEATH`, then `sendDescriptor`. Worker `recvDescriptor(socket, FileDescriptorRole.Granted)`. API type is a token (`Capability.ReadFd`), not `InputStream`.

v1 forbids worker → broker FDs (confused deputy). Return values are data only.

Timeouts kill/restart the **worker process**. Do not `Thread.interrupt()` the broker.

Every broker RPC creates one monotonic absolute deadline before worker checkout. The same
budget covers request bytes, every descriptor transfer, and response bytes; frame stages
never reset it. Portal-owned sockets use `O_NONBLOCK`, readiness polling, and nonblocking
`SCM_RIGHTS` attempts. Readiness is advisory: `EAGAIN`/`EINTR` retries recheck the deadline.
Only a timeout before the first header byte is an idle tick. After any frame progress,
timeout is a terminal incomplete frame and the channel cannot be reused. Only zero-byte EOF
before a new header is `PortalPeerClosedException`; EOF after progress and native I/O errors
remain terminal failures with their cause. A partial received
descriptor collection remains locally owned until successful delivery and is closed on failure.
The terminal channel retains received owners whose pre-native cleanup fails, so later `close()`
calls retry only unfinished retirement. Closing admission is distinct from retiring the socket;
a native close result (including errno) is terminal, while a failure before native dispatch is
retryable. Channel-lock admission consumes the same absolute deadline; failure to acquire the
channel does not terminate another caller's active frame. Both channel and capability admission
observe cancellation at bounded intervals, independently of the thread holding the lock.
An expired admission fails before I/O without consuming queued bytes; it is not a worker idle
read tick. Idle ticks apply only to an admitted receive waiting for its first header byte.

### Capability ownership migration

`Capability.ReadFd` is now `AutoCloseable`: broker code must use `openReadOnly(...).use { ... }`
(Java: try-with-resources). The broker retains its original owner while `SCM_RIGHTS` creates
a separate worker-owned descriptor. Each in-process alias shares one owner, including generated
dispatcher arguments and worker final cleanup; do not re-adopt an alias as another owner.
`PortalChannel.receive` and dispatcher handler lists now contain `Capability.ReadFd`, not raw
owned descriptor tokens. Native consumers use `capability.borrow { fd -> ... }`; the token must
not escape the callback. Close waits for an active borrow, is idempotent, and later borrows fail.
Closing from inside that same borrow is rejected. Broker sends borrow one capability at a time
under the RPC deadline, avoiding multi-capability lock-order deadlocks. Frame encoding, existing
method IDs, descriptor order, and the broker-to-worker-only authority boundary are unchanged.

## Reuse (already in tree)

| Primitive | Role |
|---|---|
| `JvmChildProcess` / `JvmChildSpec` | Child JVM command line, stdout ready sentinel |
| `PrivateUnixEndpoint` | `0700` dir, `sun_path` ≤ 107 bytes |
| `FileDescriptorRole.Granted` | Typed `SCM_RIGHTS` adopt |
| `SocketManager.recvDescriptor(fd, role)` | Role-parameterized receive |
| `ProcessPolicies` / `PolicyPresets.NO_EXEC_HOTSPOT` | Worker process policy |
| `IsolatedProcessTester` | Test-only spawn-run-exit; not the pool |

Do **not** overload `SandboxDispatcher` (in-process thread pools). New type, e.g. `ProcessBroker`.

## Modules

| Module | Responsibility |
|---|---|
| `:platform` | Spawn, Unix endpoint, typed `SCM_RIGHTS` (done) |
| `:enforcer` | Worker `installOnProcess` + presets |
| `:portal` | Pool, RPC framing, capability tokens, broker stubs |
| `:portal-worker` | Guest implementations, worker dispatcher |
| `:portal-codegen` | KotlinPoet host stub + worker dispatcher plugin |

KotlinPoet is a **plugin** dependency. Ask before adding it; do not put it on `:enforcer`.

## Codegen (later)

Marker interface + factory, fail closed if the stub class is missing. Host stub: serialize args, attach FDs, wait for result. Worker dispatcher: deserialize, call impl, serialize result. Boundary types validated at generate time (primitives, `String`, records/POJOs, `byte[]`, `Capability.ReadFd` only).

The generator compiles each requested service into one immutable schema before emitting either
side. The schema contains the effective inherited method set, resolves overrides, validates every
constructor component against a public type-compatible reader, and assigns IDs from the root
service plus erased method signature. Overloads remain unsupported and fail with all conflicting
signatures. Generated identifiers use independent KotlinPoet allocators for member constants,
parameters, receivers, and locals, so keywords, hostile parameter names, and case-distinct method
names cannot collide.

Host builds emit stubs only by default. Applications opt into a dedicated guest source set with
`portalCodegen { workerSourceSet.set("portalWorker") }` and place guest implementations under
`src/portalWorker/kotlin`. The plugin compiles dispatchers in that source set and exposes
`portalWorkerJar`, containing guest classes and `META-INF/mazewall/portal/<service>` descriptors.
For opted-in projects, `assemble` (and therefore `build`) depends on this worker artifact, so
ordinary verification compiles guest implementations and generated dispatchers. Host-only projects
retain their ordinary lifecycle without a worker artifact task.
Only this artifact belongs on the worker classpath; ordinary `jar` contains neither dispatchers
nor descriptors. `main` and `test` are rejected as worker output source sets. The worker resolves
one descriptor per configured service through its own defining classloader before constructing
the guest. Missing, duplicate, malformed, or conflicting descriptors abort readiness. Existing
registry bootstrap APIs remain available for migration; production startup requires generated metadata.
See [the code-generation diagram](../../../diagrams/portal_codegen.puml).

Boundary validation allows `Capability.ReadFd` only as a direct inbound parameter. Returns and
structured components report their service/method/property path and fail generation. Java records
use record methods; Kotlin records and constructor-backed properties use Kotlin property access.
Computed and delegated constructor component readers are rejected because reconstruction cannot
preserve their value reliably. Inherited overrides and service-based wire IDs remain unchanged.

The Gradle task emits stubs, dispatchers, and worker resources into task-owned staging trees. It publishes all trees
only after every configured interface validates and every file is generated, replacing prior trees
with atomic same-filesystem moves. A removed interface therefore removes its old generated files;
a validation or generation failure leaves all previously published trees intact. Generated wire
IDs and frame bytes are unaffected.

Hand-written stub must work before KotlinPoet so protocol bugs are not hidden in generated code.

## Explicitly not Glassbox

Do not port TeaVM/Chicory/handles, `JVM_FALLBACK` / host `Impl()`, `SecurityGate` string allowlists, `VirtualHttpClient` redirects, `InputStream` as a host handle, or instruction-count gas. Isolation is the OS process + mazewall policy.

Phase 2 (plugins): guest classes never load in the broker; worker classpath is curated. Phase 1 shared classpath is acceptable only because both sides are first-party **and** the impl is not executed in the broker.

## Not in v1

- Supervisor `USER_NOTIF`/`ADDFD` (issue-068): syscall broker, complementary later.
- Cgroups v2 / `CLONE_NEWNET` (issues 069, 070): apply to the worker PID after the portal exists.
- Graal isolates / zygote `fork` after HotSpot init.
- Bidirectional FDs, `@HostCall` reverse RPC (design later as broker-side services).

## PR plan

1. **Platform extract (landed):** `Granted`, typed recv, `JvmChildProcess`, `PrivateUnixEndpoint`; supervisor/profiler/tester switched.
2. **This document** + cross-links.
3. **`:portal` runtime:** pool, framed RPC, `Capability.ReadFd`, worker install-after-connect. Hand-written stub + integration test.
4. **`:portal-codegen`:** KotlinPoet plugin. Runtime remains the source of truth.

## Open questions

1. Worker heap/CPU: cgroup on the child PID in v1.1, or rely on `-Xmx` only?

## Resolved questions

1. **Plugin classpath isolation (was: open question #2):** Use a **separate Gradle module** (`:portal-worker`), not a `workerMain` source set. The broker depends on `:portal` only. `:portal` resolves `:portal-worker` into a detached `portalWorkerClasspath` configuration (not `runtimeOnly`) and passes it as `JvmChildSpec.classpath`. `PortalRuntimeClasspathTest` asserts `PortalBuiltinDispatch` / `PortalWorkerMain` are absent from the broker test runtime.

## Phase 2: Plugin Classpath Layout

Module structure for plugin support:

| Module | Contains | Depends on | Visible to Broker? |
|---|---|---|---|
| `:portal` | Stubs, `ProcessBroker`, `Portal`, capability tokens | `:platform`, `:enforcer` | Yes |
| `:portal-codegen` | KotlinPoet plugin (generates stubs/dispatchers) | `:portal` | Build-time only |
| `:portal-worker` (new) | Guest implementations, worker dispatcher | `:portal` | **No** |
| Plugin module (e.g., `:plugin-foo`) | Plugin impl + its deps | `:portal-worker` | **No** |

Broker apps depend only on `:portal`. Worker JVMs are spawned with the `:portal-worker` + plugin modules on their classpath. The codegen plugin generates stubs into `:portal` and dispatchers into `:portal-worker`, ensuring the broker never loads guest code.
