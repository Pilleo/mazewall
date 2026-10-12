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

> **Status:** Designed. Platform spawn/`SCM_RIGHTS` extract exists. Hand-written `:portals:runtime` runtime (`ProcessBroker` pool + Unix RPC + broker→worker FDs) exists. `:portals:worker` module contains guest implementations. `:portals:codegen` KotlinPoet plugin generates host stubs and worker dispatchers; `Portal.create` fails closed if the stub is missing.
>
> This is an **application** portal (Chrome renderer model). It is not the **syscall** supervisor in [supervisor-proxy-design.md](supervisor-proxy-design.md) (`USER_NOTIF` + `SECCOMP_IOCTL_NOTIF_ADDFD`).

The framework-agnostic service-isolation extension is specified in
[local-service-isolation-design.md](../portal/local-service-isolation-design.md). It adds
generated service metadata and a runtime facade without changing the broker/worker
capability boundary or introducing an application framework dependency.

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

The application runtime snapshots manifest, artifact and profile selectors before lazy
startup. Broker acquisition is registered before construction and the broker is owned
before start. Runtime closing stops publication/invocation admission, cancels visible
startup through the existing broker lifecycle and joins late acquisition. Failed
retirement retains ownership for retry; Closed is published only after acquisitions
and every owner retire. Startup/rollback preserve fatal-first failure identity.

Stateful group admission is atomic with terminal state loss: exactly one invocation
can be active, overlapping calls reject as NOT_STARTED before body entry, and unknown
completion or fatal invocation failure makes the group unavailable. A delayed success
or completion event cannot reopen lost state. The broker captures the manifest's instance
mode: retiring a lost stateful worker moves the running broker to Failed before maintenance
and never constructs a replacement guest. Cleanup remains retryable. Stateless groups keep
their independent capacity/replacement contract.

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

Broker-launched workers now require a bounded parent startup control frame on the
owned RPC channel before containment or guest construction. A fresh session and the
actual child PID must match the command-line expectation; the parent sends authorization
only after required cgroup setup and socket ownership succeed. Ordinary application
startup proceeds only after the worker returns the matching acknowledgement; an old
sentinel-only worker cannot satisfy that exchange. Ordinary application
payload limits are independent of the 640-byte startup control bound. Missing, mismatched
or cancelled authorization closes startup ownership and never reaches the guest.
The parent ACK authorizes setup; it is not final readiness. Before launching each child,
the broker captures an immutable generation from the verified artifact digest, ordered
explicit classpath paths and contents, exact group manifest records, and JVM runtime
identity (`java.runtime.version`, vendor, VM name, architecture and `java.home`). The
worker independently measures its actual inputs and defining-loader manifests after
authorization and compares the requested generation before containment or guest entry.
Missing real group metadata is a failure, including in typed builtin test fixtures.

Final readiness is a format-2 response on the owned RPC channel with a separate method
identity, zero request ID/descriptors and a 640-byte maximum enforced from the header
before body allocation. Its canonical ASCII/LF record binds session, actual child PID,
input/manifest/runtime and launch-contract SHA-256 identities, required parent resource
identity, the versioned static baseline policy identity,
and required process-wide Seccomp/filesystem evidence. Unknown versions, extra or
duplicate fields, noncanonical or mismatched records reject. The worker publishes only
after validating the actual required installation receipt and constructing its immutable
dispatcher table. Protection fields derive from that validated receipt, not parent flags.
The host validates the entire record and consults a pure readiness gate before publication;
the existing absolute startup deadline and cancellation/ownership checks still apply.
The standalone worker CLI's sentinel path is separate; it is not a fallback for a broker
expecting a required record. Format-1 authorization/readiness records cannot satisfy a
format-2 broker. C4 physical object pinning across measurement, kernel installation and
class loading and production operator approval remain separate gates.

### Required operator launch profiles (P3)

`PortalRuntimeConfig.launchProfiles` selects an immutable `PortalLaunchProfile` by the
manifest's policy-profile name. `requiredLaunchProfiles` declares names that must have
explicit authority; missing names and unsupported versions reject during configuration,
before child acquisition. Version 2 retains inherited deployment behavior when no operator
preparation is supplied, including through the original public constructor/load entries.
The existing three JVM option-injector exclusions remain unchanged. Merged JDK pipe stdio,
owned RPC socket and no launch-time application-FD transfer remain required. Existing
resource-profile APIs and original public entry/default bridges stay unchanged.

Environment preparation belongs to an explicit trusted operator `JvmLaunchPreparation`
callback supplied in the profile, before the library acquires the child. The library never
selects, replaces, enumerates or attests arbitrary environment entries or credential values.
There is no credential discovery, sanitizer, automatic export or total-environment secrecy
claim. The callback prepares settings only; it must not launch another process or transfer
resource ownership. The library checks the captured command and owned pipe transport are
unchanged after callback return. Preparation failure propagates without child acquisition;
unsupported required preparation rejects instead of falling back to legacy launch.

The checked metadata surface is closed: `PortalLaunchMarker.APPROVED`, `HOST_ONLY` and
`DEPLOYMENT_REGION` name the corresponding synthetic/noncredential `MAZEWALL_*` markers.
Expected marker text is bounded canonical printable ASCII (128 characters), and absent
marker expectations are explicit. Only those fixed declared names are read in the worker;
arbitrary names, duplicate/conflicting fields and old schemas reject before guest entry.
These declarations are immutable metadata, not deep snapshots of callback state. Operators
own callback state and keep its intended preparation aligned with declared expectations.

For example, a profile can declare `APPROVED` as `approved` and `HOST_ONLY` absent while its
operator callback sets only `MAZEWALL_APPROVED_MARKER` and removes only the known synthetic
`MAZEWALL_HOST_ONLY_MARKER`. Unmentioned deployment entries retain inherited behavior.
`PortalLaunchProfile.jvmArgs` snapshots explicit JVM arguments at the same launch owner;
command/generation/runtime checks and the required static baseline still apply.

`PortalDescriptorIdentity.measure` reads device/inode from an operator-acquired resource;
it is an observation, never a closeable FD token. The operator retains that resource through
startup to prevent identity reuse. The profile's forbidden-resource set is checked against
actual child descriptors by the parent before permit and independently by the worker before
containment/guest construction. Both sides also check actual FD 0/1/2 are merged pipes.
These are declared resource exclusions, not an inventory-wide FD allowlist or revocation
of descriptors opened later. JDK exec closure alone is not the negative control: the native
fixture opens a canary in the child, proves that same resource observable from both processes,
and then reaches real required rejection with no guest entry. A separate disposable host
probe proves operator preparation of the known synthetic marker while preserving the
inherited deployment marker. Real-guest public constructor/load controls exercise both the
legacy inherited case and an explicit operator-chosen deployment region.

The immutable format-2 launch contract is bounded, canonical and versioned. Its declared
noncredential marker metadata and resource identities travel in the child command line;
format-2 parent authorization binds the exact
contract identity before the worker ACK or guest entry. Final readiness retains that checked
identity alongside the genuine installation receipt. Required cgroup setup additionally
rejects ordinary filesystems, attaches using the existing controller before permit, and binds
independently measured `/proc/<pid>/cgroup` membership in authorization and readiness.
The worker measures membership after receiving the frame, so it cannot race ahead of parent
attachment. An unbounded resource profile explicitly records `resources=none`; an unavailable
required cgroup has no weaker fallback. Positive delegated-cgroup attachment/retirement and
production launch review require actual operator authority; filesystem fixtures are not that proof.

No launch-profile helper owns a process or descriptor. Existing broker provisional acquisition,
output pump, socket/channel, endpoint and cgroup owners retain custody through failure,
cancellation, deadline and retryable cleanup. Publication still follows process setup, parent
authorization, independent measurements, all-thread static containment and dispatcher construction.

Stateless worker replacement is deliberately outside the failed call's result. A failed or timed-out worker
is destroyed and reaped first; replenishment runs independently, is logged separately, and cannot
replace or delay the original failure. Checkout, return, replacement, and shutdown consult the same lifecycle authority,
so a slot cannot be returned after closing admission begins.

A fatal broker call error retires and reaps its checked-out worker before propagation. The
original error retains cleanup failures as suppressed evidence; replacement remains separate
stateless maintenance. Each child output pump drains incrementally into at most 64 diagnostic lines of
1,024 characters, records truncation/drop counts and stream failures. Its exact-line sentinel
observation is diagnostic only for broker startup; only the required socket record authorizes
publication. Child output is never appended to a shared file or
forwarded continuously into broker logs. Startup failures carry the bounded diagnostic snapshot.

The worker request loop has the closed states `AwaitingRequest → Dispatching → SendingReply`.
`ReplySucceeded` is the only transition back to `AwaitingRequest`; `ReplyFailed` terminates the
channel. Builtin and generated methods are resolved before one dispatch-failure boundary, which
deliberately unwraps reflective invocation failures. Consequently a partial response write closes
the channel and cannot be followed by an error frame. Required readiness is sent only after
receipt validation, dispatcher publication/guest construction, and channel initialization succeed.
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
cleanup failures. Only after required readiness succeeds does transport ownership pass to the execution driver.
The actual unsupervised static-baseline receipt is strongly rooted for the full OS process
lifetime, including startup rejection. There is no Java close/clear on shutdown-hook entry
or completion: other hooks and guest threads may still execute. Physical death reclaims OS
resources; this is not Java-level receipt retirement or supervised-lease release evidence.
Main return does not prove process exit when guests create non-daemon JVM threads. In-process
driver embeddings retire at their synchronous execution end. Receipt closure cannot
undo installed Seccomp/Landlock, which remain for the OS process lifetime. No reconstructed
receipt or parent-provided protection flag substitutes for actual installer evidence.

Cached launch files are checked against per-entry SHA-256 values measured from the verified
archive, including the complete expected file set and exact sorted `lib/*.jar` classpath.
Stable extra, missing, replaced or symlinked cache entries reject before generation capture.
This cache integrity check is separate from concurrent C4 object pinning. Host measurement
uses the acquisition's absolute startup deadline and active authority, with bounded
classpath text/count, traversal depth/entries, metadata and total bytes. Blocking I/O runs
in a separately registered physical platform-thread owner; interruptible FileChannel reads
are cancelled on expiry/close. Since Thread.interrupt can itself synchronously run a
blocking channel-close callback, cancellation has its own registered physical thread;
both capture and cancellation threads must terminate/join before ownership retires.
Result timeout never proves physical I/O end: an operation
which does not respond to interruption remains owned and prevents Closed until later
retirement succeeds. An interrupted physical retirement wait preserves the caller's
interrupt status and reports incomplete cleanup without removing its pending owner.
Already physically ended owners retire without an interruptible wait; their unobserved
fatal outcomes and cancellation failures remain observable under fatal-first cleanup.
Result completion and end signals alone never replace the actual thread termination/join
checks. Only the startup caller can launch, after a final deadline/active
check; late measurement completion has no launch effect.
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

Capabilities are rooted at startup: `PortalRuntimeConfig.grantRoots` maps immutable
`PortalGrantRootId` values to validated real directories. The broker accepts only
`openReadOnly(rootId, relative)`, opens beneath that configured root with `openat2` +
`RESOLVE_BENEATH`, verifies the pinned descriptor is a regular file, then
`sendDescriptor`s it. Unknown IDs, absolute or traversal paths, symlink escapes,
non-regular files, and grants after broker close fail closed. Worker
`recvDescriptor(socket, FileDescriptorRole.Granted)`. The API type is a token
(`Capability.ReadFd`), not `InputStream`.

This deliberately shrinks ambient filesystem authority; it does **not** make a compromised
broker a complete host-ACE boundary. The broker still owns the worker pool. A separate,
minimal privileged grant helper remains the future isolation step.

v1 forbids worker → broker FDs (confused deputy). Return values are data only.

Timeouts retire the **worker process**. Stateless pools may replace it; stateful groups
remain terminally unavailable. Do not `Thread.interrupt()` the broker.

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

`PortalRuntimeConfig.limits` captures immutable `PortalRuntimeLimits` before startup:
worker capacity, total concurrent admissions (running plus waiting for checkout), call,
startup and close deadlines, request/response bytes and capability count. Defaults are
one worker, 64 admissions, 30-second deadlines and the protocol's 8 MiB/eight-descriptor
caps. Stateful singleton groups always use one worker. Overload and oversized requests
reject as `NOT_STARTED` before checkout or transport; the admission lease ends only when
the synchronous broker call returns. Response headers are checked against configured
bounds before allocating the body or receiving descriptors. Worker retirement remains
owned independently after synchronous call delivery; synchronous admission release
does not prove process exit.

`PortalRuntime.prepare(serviceClass, serviceId, PortalOperationCall)` binds a
`PreparedPortalOperation<A,R>` to that service's existing broker. `submit(arguments)`
returns the shared minimal `InvocationHandle<R>` with separate result and execution-end
stages. The mapping invokes a generated host stub; mapping, input encoding and output
decoding remain host work, not code protected by the child's policy. A mapping cannot
substitute a different broker or return a local result without invoking its worker.
Arguments must remain stable until mapping/encoding ends; this API does not snapshot
arbitrary host object graphs.

Broker-owned finite JDK host dispatch consumes the same admission pool as synchronous
calls, including queued operations. Its host threads install no containment. The RPC
deadline starts at submit and reaches checkout, transport and response without reset.
Queued cancellation removes the task and releases admission without mapping entry.
Running cancellation interrupts host transport and stops caller waiting, but retains
admission and execution-end until the exact retired worker is reaped and all its known
owners finish retirement. Pre-native cleanup failure leaves that end pending and the
broker owns retry; it cannot be hidden by stateless capacity replenishment. Runtime
close stops/cancels owned dispatch before retirement and joins it under the close budget.
Closing from its own operation mapping is rejected. Converted result/end futures do not
own the execution, and a prepared operation has no independent pool lifecycle.

### Capability ownership migration

`Capability.ReadFd` is now `AutoCloseable`: broker code must use
`openReadOnly(rootId, relative).use { ... }`
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
| `:portals:runtime` | Pool, RPC framing, capability tokens, broker stubs |
| `:portals:worker` | Guest implementations, worker dispatcher |
| `:portals:codegen` | KotlinPoet host stub + worker dispatcher plugin |

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
Worker source-set dependencies are explicit and do not inherit host implementation
or runtime-only configurations. The generated host JAR embeds the worker ZIP as
opaque digest-addressed resource data with strict portable format-2 metadata.
Runtime catalog resolution verifies and copies that resource before the existing
bounded extractor builds the child classpath; no absolute build path is retained.
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
3. **`:portals:runtime` runtime:** pool, framed RPC, `Capability.ReadFd`, worker install-after-connect. Hand-written stub + integration test.
4. **`:portals:codegen`:** KotlinPoet plugin. Runtime remains the source of truth.

## Open questions

1. Worker heap/CPU: cgroup on the child PID in v1.1, or rely on `-Xmx` only?

## Resolved questions

1. **Plugin classpath isolation (was: open question #2):** Use a **separate Gradle module** (`:portals:worker`), not a `workerMain` source set. The broker depends on `:portals:runtime` only. `:portals:runtime` resolves `:portals:worker` into a detached `portalWorkerClasspath` configuration (not `runtimeOnly`) and passes it as `JvmChildSpec.classpath`. `PortalRuntimeClasspathTest` asserts `PortalBuiltinDispatch` / `PortalWorkerMain` are absent from the broker test runtime.

## Phase 2: Plugin Classpath Layout

Module structure for plugin support:

| Module | Contains | Depends on | Visible to Broker? |
|---|---|---|---|
| `:portals:runtime` | Stubs, `ProcessBroker`, `Portal`, capability tokens | `:platform`, `:enforcer` | Yes |
| `:portals:codegen` | KotlinPoet plugin (generates stubs/dispatchers) | `:portals:runtime` | Build-time only |
| `:portals:worker` (new) | Guest implementations, worker dispatcher | `:portals:runtime` | **No** |
| Plugin module (e.g., `:plugin-foo`) | Plugin impl + its deps | `:portals:worker` | **No** |

Broker apps depend only on `:portals:runtime`. Worker JVMs are spawned with the `:portals:worker` + plugin modules on their classpath. The codegen plugin generates stubs into `:portals:runtime` and dispatchers into `:portals:worker`, ensuring the broker never loads guest code.
