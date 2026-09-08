# Linux Kernel Primitives Roadmap for the JVM

This document maps out advanced Linux kernel security and isolation primitives that are currently under-utilized or entirely unexploited in managed runtimes (like the JVM). 

Rather than treating the JVM as a static black box, the goal of these roadmap items is to explore deeper integration between Java's modern concurrency and native access structures (like the Panama FFM API and Virtual Threads) and low-level Linux system calls.

**Status (2026-09-07):** Architectural recommendations, not a claim that these features are implemented or enabled. Sections 7–9 prioritize additions for the syscall supervisor and application-level process portal. Implementation requires scoped backlog work and kernel compatibility verification.

---

## 1. Thread-Group Resource Control (`cgroups v2`)

### The Concept
Control Groups (`cgroups v2`) are typically used at the process, container, or pod boundary to throttle CPU, memory, and I/O. Writing a PID (including a thread's ID) to `cgroup.procs` moves the whole process. `cgroup.threads` supports individual thread placement within the same threaded resource domain.

### JVM Integration
An application could dynamically partition thread pools (represented by an `ExecutorService`) into separate sub-cgroups:

```kotlin
// Conceptual API:
val parserPool = Executors.newFixedThreadPool(4)
ContainedExecutors.limitResources(parserPool, CpuLimit("10%"))
```

### Security & Operational Value
**CPU (threaded controller):** moving worker TIDs into a threaded cgroup can throttle *scheduling* of those threads where the kernel supports it.

**Memory:** cgroup v2 memory is a **domain** controller. It does not create a separate heap. JVM allocations, GC, and native mappings cross thread pools. An OOM action is a **process** event, not a safe “kill only the malicious worker.” Hard memory/PID isolation belongs in a **subprocess or container**. Do not design `limitResources(..., MemoryLimit)` as per-thread containment.

---

## 2. Secret Memory Mappings (`memfd_secret`)

### The Concept
Added in Linux 5.14, `memfd_secret` creates a file descriptor backing secret memory mappings. The backing pages are removed from the kernel direct map and excluded from ordinary cross-process memory-access paths. Processes with access to the descriptor can map it; descriptor inheritance and transfer therefore matter. This is not hardware-enclave isolation or a general guarantee against hardware side channels.

### JVM Integration
An FFM integration would call `memfd_secret`, size the object with `ftruncate`, and map it with `mmap`, with explicit descriptor and mapping ownership behind `NativeEngine`. Ordinary arena allocation does not create secret memory. This is a proposed integration, not an existing Java or mazewall API.

These mappings cannot be passed directly as ordinary syscall buffers. They are locked against swapping and subject to `RLIMIT_MEMLOCK`. Copies into Java arrays, strings, logs, or ordinary native buffers lose these protections.

### Security Value (limited)
`memfd_secret` unmaps pages from the **kernel direct map** and reduces *cross-process* / some dump exposure. The mapping is still in **this process**. Every JVM thread shares that address space. Native ACE on a sibling can read the mapped pages. It is not intra-process confidentiality. Use a separate process or a hardware-backed key service if a compromised JVM must not see the secret. The `memfd_secret(2)` manual does not claim an absolute guarantee.

---

## 3. User-Space Page Faulting (`userfaultfd`)

### The Concept
The `userfaultfd` mechanism allows a user-space thread to handle page faults for specific memory addresses. When a thread accesses a page that is not currently mapped in RAM, the kernel suspends the thread and sends an event to a coordinator thread, which can dynamically fetch or populate the page before resuming the thread.

### Operational Context & Prerequisites
`userfaultfd` is an on-demand memory paging mechanism, **not a security sandbox or access-control boundary**.
- **Prerequisites:** On modern Linux kernels (Linux 5.11+), unprivileged `userfaultfd` creation is disabled by default via `vm.unprivileged_userfaultfd=0` (or restricted to `UFFD_USER_MODE_ONLY`) to prevent kernel heap exploitation.
- **Threat Boundary Limitations:** It does not prevent memory corruption on mapped pages or unauthorized access once a page is populated in the shared process address space.

---

## 4. `io_uring` Restriction Rings

### The Concept
`io_uring` is a high-performance asynchronous system call engine using shared memory rings. To prevent evasion attacks (since `io_uring` submissions bypass classic Seccomp checks on standard system call entry), the kernel provides a restriction mechanism (`io_uring_register` with `IORING_REGISTER_RESTRICTIONS`). This allows instantiating a submission queue (SQ) ring and locking it down to permit only a strict subset of asynchronous operations.

### Scope & Required Outer Policy
- **Object Constrained:** `IORING_REGISTER_RESTRICTIONS` restricts **only the specific ring instance** on which it is registered. It does **not** constrain the thread or process.
- **Bypass Risk:** Any native code or dependency capable of calling `io_uring_setup(2)` can allocate a new, unrestricted ring.
- **Required Outer Policy:** Tier 1 / Tier 2 Seccomp filters **must block or supervise `io_uring_setup`** to prevent the creation of unconstrained rings. Sandboxing file I/O on `io_uring` operations additionally relies on Landlock LSM VFS hooks, which kernel `io-wq` worker threads inherit from the sandboxed thread.

---

## 5. Debugger and Trace Protection (`prctl` & `Yama LSM`)

### The Concept & Ptrace Hierarchy
Controlling external process attachment and memory inspection involves multiple kernel layers:
1. **Process Dumpability (`PR_SET_DUMPABLE, 0`):** Disables ordinary ptrace attachment and kernel core dumps. A tracer with `CAP_SYS_PTRACE` in the target's user namespace can bypass the dumpability check; other access checks still apply.
2. **Yama LSM (`/proc/sys/kernel/yama/ptrace_scope`):** Enforces system-wide attachment policies. Scope 1 normally permits a tracer to attach to its descendants, subject to the other permission checks.
3. **Yama PTRACER Exception (`PR_SET_PTRACER, pid`):** Declares an explicit exception to allow a specific debugger/profiler PID. Invoking `PR_SET_PTRACER, 0` clears any previously configured exception and returns to the default Yama policy; it does **not** make the process non-dumpable on its own.

### Security Invariants
- `PR_SET_DUMPABLE, 0` is the primary primitive to harden process memory against same-UID inspection.
- When profiling under Yama `ptrace_scope=1`, descendant tracing (e.g. child JVM spawned by profiler) is permitted because parent-child relationships satisfy Yama Scope 1.
- `PR_SET_PTRACER` relaxes Yama only; it does not override dumpability, credentials, or Landlock restrictions. Setting Yama policy is an operator action, not a library-wide sysctl change.

---

## 6. Primitive Scope & Prerequisite Reference Matrix

| Primitive | Constrained Object | Scope | Bypass / Threat Vector | Required Outer Policy / Prerequisites |
| :--- | :--- | :--- | :--- | :--- |
| **`cgroups v2` (Threaded)** | Thread IDs (`tids`) | Thread CPU scheduling | Domain controllers (Memory) apply process-wide, triggering whole-process OOM | Subprocess isolation for hard memory limits |
| **`memfd_secret`** | Kernel Direct Map pages | Process-local mapping | Accessible to all sibling threads in same address space | Separate process / hardware enclave for intra-process secrets |
| **`userfaultfd`** | Address range page faults | Paging mechanism | Does not restrict reads/writes to mapped pages | `vm.unprivileged_userfaultfd=1` / `CAP_SYS_PTRACE` |
| **`io_uring` Restrictions** | Single `io_uring` FD | Specific Ring only | Call `io_uring_setup` to allocate new unconstrained ring | Seccomp filter blocking/supervising `io_uring_setup` + Landlock |
| **`PR_SET_DUMPABLE(0)`** | Process dumpability & ptrace | Whole Process | Root / `CAP_SYS_PTRACE` bypass | Process credential isolation |
| **`PR_SET_PTRACER(0)`** | Yama exception state | Yama policy | Resets to system Yama scope; does not disable ptrace | Preserve any exception required for supervision; non-dumpable tracees can break inspection |
| **`PR_SET_MDWE(1)`** | W+X memory transitions | Whole Process | Pre-existing executable pages | Apply before loading untrusted plugins |

## 7. Supervisor Protection Must Be Directional

The syscall supervisor and application portal are distinct: the supervisor mediates syscalls using `USER_NOTIF`; the portal runs application code in worker JVMs. See [supervisor design](../enforcer/supervisor-proxy-design.md) and [portal design](../enforcer/process-portal-design.md).

The current `SupervisorDaemonManager` spawns a child daemon and calls `SetPtracer(daemonPid)` in the parent JVM. `SupervisorProcessMemoryReader` reads tracee memory through `process_vm_readv`. The supervisor also imports tracee descriptors using `pidfd_getfd`. These operations require permissions that hardening must preserve.

- **Proposed first step:** make the supervisor non-dumpable during its bootstrap, before accepting untrusted work. This protects supervisor memory against ordinary external inspection without itself preventing the supervisor from reading the tracee.
- **Do not blindly make the tracee non-dumpable:** the existing unprivileged inspection path may then fail even with the Yama exception. Do not compensate by silently granting broad tracing capabilities.
- **Account for Landlock ancestry:** a Landlock-confined tracer must have the target in the same or a nested domain. Independently restricting the supervisor can break tracing even if filesystem permissions and Yama allow it.
- **Preserve enforcement after bootstrap:** credential changes and exec transitions affect dumpability; allowed `prctl` operations must not let untrusted code undo the intended setting. Non-dumpability alone does not prevent signals, IPC abuse, or policy mistakes.
- **Verify both directions:** hostile JVM → supervisor memory access must fail, while authorized supervisor → tracee inspection and descriptor import must succeed. Cover relevant Yama modes, Landlock domains, and diagnostic/core-dump expectations. No `EPERM`/`EACCES` downgrade.

This does not turn threads sharing a JVM heap into mutually isolated security domains. Process-wide Tier 1 containment remains necessary.

## 8. Prioritized Additions

| Priority | Mechanism | Intended benefit | Integration constraint |
| :--- | :--- | :--- | :--- |
| 1 | Supervisor non-dumpability | Protect supervisor policy state and secrets from ordinary external tracing | Preserve the inspection direction described above; separately restrict hostile process-interaction syscalls |
| 2 | Worker cgroups v2 | Bound CPU, memory, task count, and I/O; terminate runaway worker groups | Use delegated cgroups and separate supervisor/worker budgets; `-Xmx` is not a total-process memory limit |
| 3 | Worker PID namespace and private procfs | Restrict process visibility/addressability from workers while retaining outside supervision | Configure at spawn, handle PID 1/reaping, and exclude inherited host-procfs descriptors; `hidepid=2` alone does not separate same-UID processes |
| 3 | Worker network namespace | Separate interfaces, routes, ports, and network namespace resources | Bootstrap before JVM threads; inherited sockets retain their original network access; grant only intentional connected endpoints |
| 4 | Landlock signal and abstract Unix-socket scopes | Restrict interactions with processes/services outside the worker domain | Feature-probe the ABI, preserve JVM coordination, establish intended IPC before restriction, and account for the absence of outside-domain scope exceptions |
| 5 | Sealed `memfd_create` payloads | Transfer immutable bulk data across portal boundaries | Apply and verify write/grow/shrink seals before trusting data; incompatible writable mappings must be removed; this does not seal arbitrary tracee pointers |
| 5 | `memfd_secret` for selected broker secrets | Reduce memory disclosure and swap exposure | Keep plaintext out of worker/JVM heap copies; probe availability and enforce ownership and resource limits |

Reduced credentials and capability sets complement every layer. `no_new_privs` prevents privilege gains through exec; it does not drop privileges already held. Configure namespaces/credentials at launch rather than repurposing live JVM threads. Required unsupported protections must fail closed; optional profiles must explicitly describe their weaker guarantees.

Existing `openat2`, pidfd, seccomp, and Landlock plumbing are integration foundations, not evidence that every route or worker already has these protections. Confining broker opens requires trusted directory capabilities and appropriate resolution flags, not merely use of the `openat2` syscall. Cgroups and namespace work should build on portal worker lifecycle management.

## 9. Secret Storage Versus Secret Operations

There are two different service contracts:

- **Deliver a secret:** an agent retrieves a password/token and writes it to an application-readable file or returns it over RPC. This improves distribution, rotation, and storage hygiene, but a compromised authorized application can still read the delivered value.
- **Perform an operation:** a broker keeps a key/credential and exposes a restricted signing, authentication, or upstream-request operation. The worker receives a result, not the long-lived secret. A compromised client can still misuse operations it is authorized to request, so destination, operation, identity, rate, and lifetime restrictions remain essential.

Established examples, checked 2026-09-07:

| Tool/pattern | Contract | Boundary |
| :--- | :--- | :--- |
| Vault Agent Injector | Renders retrieved secrets into a shared memory volume | A delivery sidecar; secrets become readable by the application |
| systemd credentials | Supplies service credentials through protected files; supports encrypted credentials bound to TPM2 and/or host key material | Improves local delivery and storage hygiene; the consuming service can still read the secret |
| Vault Transit | Performs cryptographic operations through an API | Key material can remain in Vault; do not enable key export or return plaintext data keys when key non-disclosure is required |
| AWS KMS / HSM-backed operations | Performs operations with protected KMS keys | AWS-generated KMS key material remains within HSM boundaries in plaintext; this does not protect plaintext results or prevent authorized API misuse |
| OpenSSH `ssh-agent` | Holds authentication keys and answers agent requests | Illustrates local operation brokering; access to its socket can authorize use of keys without extracting them |

For mazewall, a possible future integration is a narrowly scoped portal service backed by an existing secret manager or key service. A general `getSecret()` endpoint does not preserve confidentiality from the worker. For bearer-token APIs, the trusted broker must authenticate the upstream request itself if the token must never enter worker memory. This is a recommendation, not an implemented credential sidecar or an approved new public API.

Do not assume every secret manager uses `memfd_secret`, non-dumpability, or a particular namespace profile. Deployment and implementation differ. Vault's production guidance explicitly addresses swap and `mlock`; systemd credentials use non-swappable memory where available. When applications must receive passwords, short-lived, narrowly scoped credentials (such as Vault dynamic database credentials) reduce exposure duration but do not prevent theft during authorized use.

## References

- [Kernel cgroup v2 documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html)
- [memfd_secret semantics and limitations](https://man7.org/linux/man-pages/man2/memfd_secret.2.html)
- [Ptrace access-check ordering](https://man7.org/linux/man-pages/man2/ptrace.2.html) and [Yama](https://docs.kernel.org/admin-guide/LSM/Yama.html)
- [Landlock ptrace restrictions and IPC scopes](https://www.kernel.org/doc/html/latest/userspace-api/landlock.html)
- [PID namespaces](https://man7.org/linux/man-pages/man7/pid_namespaces.7.html), [procfs mount options](https://man7.org/linux/man-pages/man5/proc.5.html), and [network namespaces](https://man7.org/linux/man-pages/man7/network_namespaces.7.html)
- [Sealed memfd objects](https://man7.org/linux/man-pages/man2/memfd_create.2.html) and [capabilities](https://man7.org/linux/man-pages/man7/capabilities.7.html)
- [Vault Agent Injector](https://developer.hashicorp.com/vault/docs/deploy/kubernetes/injector), [Vault Transit](https://developer.hashicorp.com/vault/docs/secrets/transit), and [Vault production hardening](https://developer.hashicorp.com/vault/docs/concepts/production-hardening)
- [AWS KMS data protection](https://docs.aws.amazon.com/kms/latest/developerguide/data-protection.html)
- [OpenSSH authentication agent](https://man.openbsd.org/ssh-agent)
- [systemd credentials](https://systemd.io/CREDENTIALS/) and [Vault dynamic database credentials](https://developer.hashicorp.com/vault/tutorials/db-credentials/database-secrets)
