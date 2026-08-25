# Network Isolation via Namespaces — Design (issue-070)

> Status (2026-08-25): **Design ready, implementation deferred.** Two Jules sessions
> failed deterministically attempting unprivileged `clone(CLONE_NEWNET)` (sids
> `2934675315516512391`, `305566646083`); the kernel rejects it without a user-namespace
> chain. This document specifies the privilege boundary so implementation becomes a
> bounded task instead of an agent gamble.

## 1. Goal & non-goals

**Goal:** contained threads get their own network namespace — isolated ports, isolated
loopback, no visibility of host interfaces — while staying inside the mazewall process
model where practical.

**Non-goals:**
- Per-thread netns on live JVM threads. `setns(2)` for network namespaces works per-thread
  in principle, but migrating a running JVM thread pool breaks GC signal routing and
  thread-dump tooling (same class of failure documented in containment-design.md §Tier-1).
  Explicitly avoided.
- Replacing seccomp network denial. `PolicyPresets.NO_NETWORK` (blocks `socket/connect/
  sendto/…`) stays the primary deny-layer; namespaces add *namespacing* (independent
  loopback, port space) which seccomp cannot express.

## 2. Privilege analysis (why two attempts failed)

`clone(CLONE_NEWNET)` requires `CAP_SYS_ADMIN` **in the governing user namespace**.
Unprivileged paths, in order of preference:

| Path | Requirement | Verdict |
|---|---|---|
| `CLONE_NEWUSER \|\| CLONE_NEWNET` in one `clone3()` | `/proc/sys/kernel/unprivileged_userns_clone` ≠ 0 (Debian/Ubuntu) and `max_user_namespaces` > 0 | **Chosen** — detect at runtime, degrade gracefully |
| `unshare -Urn` helper process | `unshare(1)` binary present | Fallback if single-syscall path is restricted by seccomp profile nesting |
| Root / `CAP_SYS_ADMIN` container | Out of scope for library threat model | Not pursued |

**Kernel-gated support probe** (mirrors CET/Cgroups detection pattern):
read `/proc/sys/kernel/max_user_namespaces > 0` and attempt a throwaway
`CLONE_NEWUSER|CLONE_NEWNET` child; cache result in `Platform.featureMatrix`.

## 3. Architecture: spawn-time netns on portal-style workers

Identical shape to the Process Portal (process-portal-design.md): **the namespace
attaches to a fresh child process at spawn time, never to existing JVM threads.**

```
JVM (host netns)
  └─ ProcessLauncher.start(
         cloneflags = CLONE_NEWUSER | CLONE_NEWNET,
         uidMap/gidMap written to /proc/<child>/{uid_map,gid_map} ("0 <realuid> 1")
       )
       └─ child JVM (own netns: loopback only, no host routes)
            └─ serves RPC over PrivateUnixEndpoint FD passed pre-exec
                 (FD survives: unix sockets are netns-independent at connect time
                  via passed fd, bind happens inside child netns)
```

Key mechanics:
1. **Single syscall spawn**: use `clone3()` with `CLONE_NEWUSER|CLONE_NEWNET` via
   `SyscallInvoker` (RawNativeEngine already exposes raw syscalls). Fork/vfork wrappers
   in ProcessLauncher need a flags extension point.
2. **Identity mapping**: write `0 <euid> 1` to `uid_map` (`gid_map` analogous, with
   `setgroups("deny")` first). Without mapping, the child has no valid ids and
   filesystem access fails.
3. **Loopback bring-up**: inside a fresh netns `lo` is DOWN. Child runs
   `ip link set lo up` (or ioctl) during bootstrap; no external interface exists.
4. **Communication**: parent↔child RPC reuses the portal pattern — parent creates a
   listening `PrivateUnixEndpoint`, passes the *connect-end* as a granted FD
   (`SCM_RIGHTS` unnecessary: same host, different netns — unix sockets with
   filesystem path are netns-scoped, so pass the **listen-side fd into the child**
   before exec via `fd` argument or after-connect handshake). Design decision:
   **parent listens, child connects out through the inherited fd** created with
   `socketpair(AF_UNIX)` pre-clone — socketpairs survive netns changes because both
   ends were created in the parent's netns and remain connected.
5. **Egress**: none by default. If the child legitimately needs outbound network,
   that is a policy escalation handled by explicit allowlisted proxies (portal
   services), never by attaching host interfaces.

## 4. API surface (sketch)

```kotlin
// platform: spawn primitive
ProcessLauncher.startInNetworkNamespace(spec, flags = CLONE_NEWUSER or CLONE_NEWNET)

// enforcer api: detection + typed wrapper
Platform.isNetworkNamespaceSupported(): Boolean          // featureMatrix cached
ContainedExecutors.newNetworkIsolatedWorker(             // portal-worker style
    spec: JvmChildSpec, policy: Policy<*, Uncompiled>,
): ContainedWorker                                       // throws NamespaceUnsupportedException
```

Failure semantics follow the cgroups pattern: capability probe → graceful
`NamespaceUnsupportedException`; **never** silently run un-isolated when isolation
was requested (fail-closed rule).

## 5. Relationship to existing layers

| Layer | Provides | This design adds |
|---|---|---|
| `NO_NETWORK` seccomp preset | syscall deny (fail-closed) | independent loopback/port space |
| Landlock fs rules | path scoping | — |
| Portal workers | privilege boundary for *exec* | same boundary for *net* |

Defense-in-depth: deploy **both** seccomp deny and netns; seccomp covers
pre-netns-bootstrap windows inside the child.

## 6. Test strategy

- Kernel-gated integration tests (skip when probe fails), mirroring
  `integrationTestFreshJvm` patterns.
- Assertions: child sees only `lo`; child cannot `connect()` to host-bound ports;
  parent↔child RPC works over the socketpair; uid_map applied (child euid == real euid).
- Negative: `max_user_namespaces=0` simulation → `NamespaceUnsupportedException`.

## 7. Open questions

1. `clone3()` vs legacy `clone(2)` struct sizing across abis (s390x/ppc64le reverse
   arg order — see issue-20260821-113000 family).
2. Debian kernels with `unprivileged_userns_clone=0`: is a setuid `newuidmap`
   dependency acceptable? (Probably not — prefer hard unsupported verdict.)
3. Interaction with HotSpot: child JVM boot inside netns needs no extra flags beyond
   loopback bring-up — verify on 25.0.x.

## 8. Implementation plan (bounded tasks)

1. Platform probe + featureMatrix entry (+ unit tests with fake procfs).
2. ProcessLauncher clone3/flags extension (+ abi tests).
3. uid/gid map writer + loopback bootstrap in child bootstrap classpath hook.
4. `ContainedExecutors.newNetworkIsolatedWorker` + fail-closed exception.
5. Integration suite per §6.
