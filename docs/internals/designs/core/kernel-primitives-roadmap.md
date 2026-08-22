# Linux Kernel Primitives Roadmap for the JVM

This document maps out advanced Linux kernel security and isolation primitives that are currently under-utilized or entirely unexploited in managed runtimes (like the JVM). 

Rather than treating the JVM as a static black box, the goal of these roadmap items is to explore deeper integration between Java's modern concurrency and native access structures (like the Panama FFM API and Virtual Threads) and low-level Linux system calls.

---

## 1. Thread-Group Resource Control (`cgroups v2`)

### The Concept
Control Groups (`cgroups v2`) are typically used at the process, container, or pod boundary to throttle CPU, memory, and I/O. However, the Linux kernel allows writing individual Thread IDs (`tids`) into a cgroup's `cgroup.procs` or `cgroup.threads` controllers.

### JVM Integration
An application could dynamically partition thread pools (represented by an `ExecutorService`) into separate sub-cgroups:

```kotlin
// Conceptual API:
val parserPool = Executors.newFixedThreadPool(4)
ContainedExecutors.limitResources(parserPool, CpuLimit("10%"), MemoryLimit("128MB"))
```

### Security & Operational Value
**CPU (threaded controller):** moving worker TIDs into a threaded cgroup can throttle *scheduling* of those threads where the kernel supports it.

**Memory:** cgroup v2 memory is a **domain** controller. It does not create a separate heap. JVM allocations, GC, and native mappings cross thread pools. An OOM action is a **process** event, not a safe “kill only the malicious worker.” Hard memory/PID isolation belongs in a **subprocess or container**. Do not design `limitResources(..., MemoryLimit)` as per-thread containment.

---

## 2. Hardware-Isolated Memory (`memfd_secret`)

### The Concept
Added in Linux 5.14, the `memfd_secret` system call creates a memory area that is visible only to the owning process. Crucially, the page tables for this memory are removed from the kernel's direct map (they are not mapped in the kernel page tables at all). The pages are protected against hardware side-channel attacks (like Rowhammer) and are not accessible by other virtual memory maps.

### JVM Integration
Using Panama FFM (`arena.allocate` or `MemorySegment.ofAddress`), the JVM can allocate and reference memory backed by a `memfd_secret` file descriptor:

```kotlin
val fd = LinuxNative.memfd_secret(0)
val segment = MemorySegment.mapFile(fd, 0, keySize, MapMode.READ_WRITE, arena)
```

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
1. **Process Dumpability (`PR_SET_DUMPABLE, 0`):** Disables ptrace attachment from non-root callers and prevents kernel core dumps from writing process memory to disk.
2. **Yama LSM (`/proc/sys/kernel/yama/ptrace_scope`):** Enforces system-wide attachment policies (e.g. Scope 1 restricts ptrace to ancestor processes).
3. **Yama PTRACER Exception (`PR_SET_PTRACER, pid`):** Declares an explicit exception to allow a specific debugger/profiler PID. Invoking `PR_SET_PTRACER, 0` clears any previously configured exception and returns to the default Yama policy; it does **not** make the process non-dumpable on its own.

### Security Invariants
- `PR_SET_DUMPABLE, 0` is the primary primitive to harden process memory against same-UID inspection.
- When profiling under Yama `ptrace_scope=1`, descendant tracing (e.g. child JVM spawned by profiler) is permitted because parent-child relationships satisfy Yama Scope 1.

---

## 6. Primitive Scope & Prerequisite Reference Matrix

| Primitive | Constrained Object | Scope | Bypass / Threat Vector | Required Outer Policy / Prerequisites |
| :--- | :--- | :--- | :--- | :--- |
| **`cgroups v2` (Threaded)** | Thread IDs (`tids`) | Thread CPU scheduling | Domain controllers (Memory) apply process-wide, triggering whole-process OOM | Subprocess isolation for hard memory limits |
| **`memfd_secret`** | Kernel Direct Map pages | Process-local mapping | Accessible to all sibling threads in same address space | Separate process / hardware enclave for intra-process secrets |
| **`userfaultfd`** | Address range page faults | Paging mechanism | Does not restrict reads/writes to mapped pages | `vm.unprivileged_userfaultfd=1` / `CAP_SYS_PTRACE` |
| **`io_uring` Restrictions** | Single `io_uring` FD | Specific Ring only | Call `io_uring_setup` to allocate new unconstrained ring | Seccomp filter blocking/supervising `io_uring_setup` + Landlock |
| **`PR_SET_DUMPABLE(0)`** | Process dumpability & ptrace | Whole Process | Root / `CAP_SYS_PTRACE` bypass | Process credential isolation |
| **`PR_SET_PTRACER(0)`** | Yama exception state | Yama policy | Resets to system Yama scope; does not disable ptrace | Pair with `PR_SET_DUMPABLE(0)` and system Yama `ptrace_scope>=1` |
| **`PR_SET_MDWE(1)`** | W+X memory transitions | Whole Process | Pre-existing executable pages | Apply before loading untrusted plugins |

