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

> **Status:** Designed. Platform spawn/`SCM_RIGHTS` extract exists. Hand-written `:portal` runtime (`ProcessBroker` pool + Unix RPC + broker→worker FDs) exists. `:portal-codegen` KotlinPoet plugin generates host stubs and worker dispatchers; `Portal.create` fails closed if the stub is missing.
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

## Architecture

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

Worker first lines after IPC connect:
1. `ContainedExecutors.installOnProcess(denyProcessCreation + denyNetwork)` (process-wide seccomp).
2. `ContainedExecutors.installOnCurrentThread(ProcessPolicies.workerFilesystem)` — Landlock allowlist of `java.home` and classpath entries only. `allowJvmClasspath()` is `ThreadLocalOnly` (no ABI v8 TSYNC on existing helper threads). Fail closed if Landlock is unsupported. Extra readable paths are deferred.

`read`/`write` on inherited FDs remain legal; see [security-considerations.md](../core/security-considerations.md).

## IPC and FDs

Transport: `PrivateUnixEndpoint` + `SocketManager` (`AF_UNIX`, `SOCK_CLOEXEC`).

Payload: kotlinx.serialization (already in the repo) or an explicit record schema. Size and nesting caps on unmarshal. No `java.io.Serializable`. No live Java object identity.

Capabilities: broker opens with `openat2` + `RESOLVE_BENEATH`, then `sendDescriptor`. Worker `recvDescriptor(socket, FileDescriptorRole.Granted)`. API type is a token (`Capability.ReadFd`), not `InputStream`.

v1 forbids worker → broker FDs (confused deputy). Return values are data only.

Timeouts kill/restart the **worker process**. Do not `Thread.interrupt()` the broker.

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
| `:portal` (new) | Pool, RPC framing, capability tokens |
| `:portal-codegen` (new) | KotlinPoet host stub + worker dispatcher |

KotlinPoet is a **plugin** dependency. Ask before adding it; do not put it on `:enforcer`.

## Codegen (later)

Marker interface + factory, fail closed if the stub class is missing. Host stub: serialize args, attach FDs, wait for result. Worker dispatcher: deserialize, call impl, serialize result. Boundary types validated at generate time (primitives, `String`, records/POJOs, `byte[]`, `Capability.ReadFd` only).

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

1. **Plugin classpath isolation (was: open question #2):** No, a second Gradle source set (`workerMain`) is **not** required. For Phase 2 plugins, use a **separate Gradle module** (e.g., `:portal-worker`) that depends on `:portal` (stubs + interfaces) and contains only guest implementations. The broker module depends only on `:portal` and never includes the worker module. This gives cleaner isolation than source sets: the worker module can have its own dependencies, and Gradle's dependency resolution guarantees the broker classpath never sees worker classes. A Gradle check (or ArchUnit test) can verify that `PortalBuiltinDispatch` / `@SandboxImpl` annotated classes are not on the broker's runtime classpath.

## Phase 2: Plugin Classpath Layout

Module structure for plugin support:

| Module | Contains | Depends on | Visible to Broker? |
|---|---|---|---|
| `:portal` | Stubs, `ProcessBroker`, `Portal`, capability tokens | `:platform`, `:enforcer` | Yes |
| `:portal-codegen` | KotlinPoet plugin (generates stubs/dispatchers) | `:portal` | Build-time only |
| `:portal-worker` (new) | Guest implementations, worker dispatcher | `:portal` | **No** |
| Plugin module (e.g., `:plugin-foo`) | Plugin impl + its deps | `:portal-worker` | **No** |

Broker apps depend only on `:portal`. Worker JVMs are spawned with the `:portal-worker` + plugin modules on their classpath. The codegen plugin generates stubs into `:portal` and dispatchers into `:portal-worker`, ensuring the broker never loads guest code.
