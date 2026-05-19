# The Global Sandbox Fallacy: Thread-Scoped Seccomp in the JVM

> **Series overview:** This is Part 2 of a 4-part series on behavioral security for cloud-native applications.


In Part 1, we established that the Linux kernel gives us three unprivileged enforcement primitives — Seccomp, Landlock, and (for platform agents) BPF-LSM — and that a Software Bill of Behavior describes what software is *expected* to do so these primitives have something authoritative to enforce.

Now we get practical. The JVM is one of the most capability-rich processes in the modern data center. Let's examine why the standard approach to securing it leaves significant attack surface, and how thread-scoped enforcement closes the gap.

---

## Why Not BPF-LSM?

Before anything else: if you've been following kernel security, your first question is probably *"why Seccomp and not BPF-LSM?"*

BPF-LSM is unambiguously more powerful. While Seccomp sees raw memory addresses and is TOCTOU-vulnerable for path-based decisions (Part 1), BPF-LSM hooks *after* kernel objects are fully resolved — it inspects the canonical path `/etc/passwd`, the resolved destination IP, the resolved inode. It enables complex, context-aware enforcement that Seccomp cannot match.

**The architectural blocker is privilege.** Loading a BPF-LSM program requires `CAP_BPF` or `CAP_MAC_ADMIN`. A production JVM running as a non-root user in a container should never hold these capabilities. Using BPF-LSM for application-level self-restriction means deploying a highly privileged node agent (a Kubernetes DaemonSet) to manage policies on the JVM's behalf — a significant operational dependency.

Seccomp and Landlock are **self-restriction primitives**. With `NoNewPrivileges` set, any thread can unilaterally strip its own capabilities — no agents, no cluster-level permissions. `jseccomp` requires zero external infrastructure. That architectural purity has a cost (TOCTOU on path inspection), but it's the right trade-off for developer-driven "shift left" security.

---

## The Global Sandbox Fallacy

The standard approach to JVM security is a global seccomp profile applied to the entire Linux process — the Docker default profile, an AppArmor policy on the pod, or a custom seccomp JSON in the `securityContext`.

This is not worthless. Docker's default profile already blocks ~40 dangerous syscalls: `keyctl`, `add_key`, `request_key`, `ptrace` in certain modes, and others. That baseline matters.

**But the remaining allowed syscalls are the problem.** A typical Spring Boot application — even after Docker's default restrictions — still requires:
- `socket` + `connect` + `sendmsg` for its API and database connections
- `openat` + `read` for reading config files and loading classes
- `mmap` with `PROT_EXEC` for the JIT compiler to generate native code

Because the *process* needs these capabilities, *every thread* in the process has them — including threads processing untrusted data.

When an attacker triggers an RCE vulnerability (Log4Shell, a deserialization gadget chain, an XXE payload that reaches JNDI), they inherit the full capability set of the worker thread. They don't need to escape the container. They can use the network socket the JVM already has open to exfiltrate data, the filesystem access it already has to read `/etc/passwd`, the process execution it already has to... wait, that's where it gets interesting.

---

## The Solution: Tiered Enforcement

The Linux kernel provides a capability that is underutilized: **Seccomp filters can be applied per-thread.**

`jseccomp` is built around a two-tier model:

**Tier 1 — Global Process Lockdown:** At application startup, apply `Policy.NO_EXEC` to the entire JVM process via `ContainedExecutors.installOnProcess()`. This permanently disables shell spawning (`execve`, `execveat`, `fork`, `vfork`, `memfd_create`) for every thread, present and future.

This mimics the approach Elasticsearch has used in production for years — a minimal, process-wide filter that renders Log4Shell-style RCE toothless. The attacker can reach your vulnerable code; they simply cannot spawn a shell. The filter is permanent and cannot be undone.

> [!WARNING]
> **Experimental and Untested Code.** The entire `jseccomp` library — including both the process-wide (`installOnProcess()`) and thread-scoped (`wrap()`) mechanisms — is an **experimental proof-of-concept**. None of this code has been tested or validated for production environments. Do not use any part of this library in production.



**Tier 2 — Surgical Thread Containment:** For specific worker pools handling untrusted data — JSON parsers, image processors, XML deserializers, report generators — apply stricter policies. `ContainedExecutors.wrap()` creates an `ExecutorService` decorator that installs the chosen policy on each worker thread before it runs its first task. These restrictions are permanent for the lifetime of that thread.

```kotlin
// Global lockdown at startup — all threads, forever
ContainedExecutors.installOnProcess(Policy.NO_EXEC)

// Per-pool surgical restriction for untrusted-data workers
val imageProcessor = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    Policy.PURE_COMPUTE  // no network, no file open, no exec, no mmap(PROT_EXEC)
)
```

---

## Stopping Shellcode Without Breaking the JIT

The JVM's JIT compiler is the most obvious obstacle to strict memory protection. The JIT must call `mmap` with `PROT_EXEC` to allocate executable memory for optimized native code. A naive Seccomp filter blocking all `PROT_EXEC` will crash the JVM immediately.

`jseccomp` solves this with **BPF argument inspection**. The BPF filter doesn't just check the syscall number — it checks the `prot` argument to `mmap`. Specifically, it loads the lower 32 bits of `prot` and tests whether the `PROT_EXEC` bit (0x4) is set:

- `mmap(addr, len, PROT_READ | PROT_WRITE, ...)` → **Allowed.** Worker thread allocates data memory normally.
- `mmap(addr, len, PROT_READ | PROT_EXEC, ...)` → **Blocked. EPERM.** Shellcode injection attempt stopped.

Because this filter is applied only to the contained worker threads, the JIT threads running on the same JVM continue operating without restriction. We've surgically neutralized shellcode execution — thread-scoped, zero JVM stability impact.

> **32-bit truncation note:** BPF operates on 32-bit words. The filter loads the lower 32 bits of `prot`. Since the Linux kernel casts the `prot` argument to `unsigned long` but only honors the standard lower bits defined in POSIX, this truncation is architecturally correct and matches kernel behavior.

---

## The Limits: ROP and JOP

Blocking `mmap(PROT_EXEC)` prevents the *introduction of new malicious code* (shellcode). It does not stop an attacker who reuses *existing* code already mapped in the JVM's address space.

**Return-Oriented Programming (ROP)** and **Jump-Oriented Programming (JOP)** chain together short existing code sequences ("gadgets") from the JVM binary, loaded libraries, and the JDK itself. These chains can implement arbitrary computation without ever calling `mmap` or `mprotect`. Seccomp cannot see CPU instruction flow — only syscalls.

This is not a flaw in `jseccomp`; it is the honest limit of the syscall interception model. The complementary defenses are:
- **ASLR** — randomizes the base addresses of loaded code, making gadget chain addresses unpredictable
- **Stack Canaries** — detect stack corruption before a ROP chain can pivot
- **CFI (Control Flow Integrity)** — hardware enforcement of valid branch targets (Intel CET Shadow Stacks, ARM BTI)

Seccomp is the lock on the door for *new* code. ASLR + CFI are the internal sensors for gadget chain attacks.

---

## Seccomp + Landlock: The Complete Picture

Seccomp's TOCTOU vulnerability for path inspection (it sees raw pointers, not resolved paths) means it cannot safely enforce "this thread may only read from `/app/data`."

**Landlock** fills that gap. It provides path-aware filesystem access control at the inode level, with the same zero-privilege profile as Seccomp. `jseccomp` combines both:

```kotlin
val policy = Policy.builder()
    .base(Policy.PURE_COMPUTE)
    .allowJvmClasspath()          // allow lazy classloading (critical — see below)
    .allowFsRead("/data/incoming")
    .allowFsWrite("/data/processed")
    .build()

val executor = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    policy
)
```

**Critical JVM gotcha:** Landlock is applied at the OS thread level, permanently. If a restricted worker thread is the first to trigger the JVM's lazy classloading for a new class, the JVM will attempt to open the `.jar` or `.class` file from the classpath. If Landlock blocks that read, the JVM throws `NoClassDefFoundError`. `allowJvmClasspath()` pre-authorizes `java.home` and all classpath entries to prevent this. Calling it is not optional when using Landlock.

Unlike Seccomp's `TSYNC` flag, Landlock's `landlock_restrict_self` only affects the calling thread — not existing JVM threads. True process-wide Landlock requires applying the ruleset *before* `execve`-ing the JVM (from a launcher), so all threads inherit it from birth.

---

## Internal Micro-segmentation: Scalpel vs. Shield

A common question: *"If I already run Kubescape or Falco cluster-wide, why do I need thread-level containment?"*

The answer is Defense-in-Depth, and the distinction is *visibility*.

Cluster-wide tools see your container as a black box. They observe the process boundary — what syscalls the JVM process makes, what network connections it opens. They cannot see *which thread inside the JVM* is making those calls, *which library* is on the call stack, or *what data* triggered the behavior. A worker thread processing a malicious JSON payload looks identical to a thread processing a legitimate request from the outside.

`jseccomp` applies restrictions based on internal application logic that no external orchestrator can see. The cluster-wide tool is the building's perimeter security; `jseccomp` is the lock on each individual safe inside.

---

## Beyond Java: The Scheduler Constraint

The principles here are universal, but thread-level enforcement is only meaningful if there is a stable 1:1 mapping between your application's concurrency primitive and an OS thread.

**Go goroutines:** Go's M:N scheduler multiplexes thousands of goroutines onto a small pool of OS threads. If you apply a Seccomp filter to an OS thread to sandbox one goroutine, the Go runtime may schedule a different, completely unrelated goroutine onto that "poisoned" thread — which then crashes the moment it attempts a syscall the previous goroutine was forbidden from using. `runtime.LockOSThread()` prevents scheduling but cripples Go's concurrency model. `GOMAXPROCS=1` doesn't help — the scheduler is still non-deterministic with respect to thread assignment. Thread-level containment is currently impractical for Go.

**Java virtual threads (Project Loom, Java 21+):** Loom's virtual threads have exactly the same M:N scheduler problem as Go goroutines. `jseccomp` detects virtual threads at runtime and immediately throws `IllegalStateException` with a clear diagnostic message — preventing silent policy bypass on carrier threads. If you use `Executors.newVirtualThreadPerTaskExecutor()`, the library will tell you explicitly that it cannot safely contain those threads.

```
java.lang.IllegalStateException: Attempted to apply seccomp containment inside a virtual thread.
Use a dedicated platform thread pool and install containment on its carrier threads instead.
```

**Rust and C++ with native OS threads:** These use predictable 1:1 thread models and are excellent candidates for thread-level containment. Process-level enforcement (`prctl(PR_SET_SECCOMP, ...)`) is universally portable regardless of thread model.

---

## The `seccomp=unconfined` Requirement Explained

The Docker example in Part 3 requires running with `--security-opt seccomp=unconfined`. This warrants an explanation, because the instruction to disable seccomp in a security-focused demo is counterintuitive.

Linux enforces a **monotonicity invariant** for seccomp filters: each new filter can only be *more restrictive* than the existing one, never more permissive. When Docker applies its default seccomp profile to a container at startup, that profile becomes the floor. Any attempt by the JVM inside the container to install its own (different) seccomp filter would fail, because the kernel cannot verify that the new filter is strictly a subset of the existing one.

Running with `seccomp=unconfined` removes the host-provided floor, allowing `jseccomp` to install its own filters from a clean state. In production, you would instead provide a custom Docker seccomp profile that is a superset of everything `jseccomp` needs to block — but that is an operational concern, not a fundamental limitation.

---

While process-wide lockdown (Tier 1) is a proven conceptual model (demonstrated by Elasticsearch's native lockdown mechanisms), its implementation in `jseccomp` is entirely experimental. 

Similarly, thread-scoped surgical containment (Tier 2) provides a targeted proof-of-concept isolation boundary. However, the entire library — including both process-wide and thread-scoped modes — remains an **untested research experiment** that should not be used in any production environments.

*Next up: Part 3: The Attacks jseccomp Stops*


