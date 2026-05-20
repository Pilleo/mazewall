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

**Java virtual threads (Project Loom, Java 21+):** Loom's virtual threads have exactly the same M:N scheduler problem as Go goroutines. Virtual threads are scheduled onto a carrier thread pool (by default, a `ForkJoinPool`). 
If a task running on a virtual thread installs a seccomp filter on its current thread, it sandboxes the underlying **OS carrier thread**. When that task completes or yields, the carrier thread remains permanently restricted. Any subsequent virtual thread scheduled onto that carrier—even one running a completely unrelated, high-privilege administrative task—will inherit those restrictions and crash or fail.

To prevent this critical "carrier contamination," `jseccomp` detects virtual threads at runtime and immediately throws `IllegalStateException` with a clear diagnostic message:

```
java.lang.IllegalStateException: Attempted to apply seccomp containment inside a virtual thread.
Use a dedicated platform thread pool and install containment on its carrier threads instead.
```

**Rust and C++ with native OS threads:** These use predictable 1:1 thread models and are excellent candidates for thread-level containment. Process-level enforcement (`prctl(PR_SET_SECCOMP, ...)`) is universally portable regardless of thread model.

---

## Configuring Docker: Custom Seccomp Profiles vs. Unconfined

The hands-on examples in Part 3 require running Docker containers with `--security-opt seccomp=unconfined`. This instruction is often misunderstood by developers and system administrators as a security hazard. Let's look at what is actually happening.

A common misconception is that the kernel prevents the installation of new seccomp filters because it cannot verify "monotonicity compatibility." This is technically incorrect: **the Linux kernel supports filter stacking natively**. You can stack as many filters as you want (up to a depth limit of 32, or a BPF instruction count limit), and the kernel will run all of them in reverse order, applying the most restrictive action returned.

**The actual blocker is Docker's default Seccomp profile.** By default, Docker blocks the `seccomp(2)` system call and restricts the `prctl(2)` system call arguments to prevent containers from modifying their own security filters. When the JVM inside a standard container attempts to call `prctl(PR_SET_SECCOMP)` or `seccomp(SECCOMP_SET_MODE_FILTER)`, the host kernel blocks the call at the outer Docker container boundary, causing an immediate `EPERM`.

Running with `seccomp=unconfined` disables the host-side seccomp sandbox entirely to let our demo install filters. However, **this is highly discouraged for production environments**.

### The Production Solution: Custom Docker JSON Profile

Instead of running unconfined, you should run your container with a **custom Docker seccomp JSON profile** that is identical to the standard Docker profile but explicitly whitelists the installation syscalls:

1. **`seccomp`**: Allowed with zero restrictions.
2. **`prctl`**: Allowed, specifically whitelisting `PR_SET_SECCOMP` (22) and `PR_SET_NO_NEW_PRIVS` (38) in the arguments list.

This maintains Docker's robust container-level security floor (blocking access to dangerous host-level calls like `keyctl` or kernel namespace mutations) while empowering the JVM inside the container to dynamically apply its own unprivileged thread-level sandboxes.

---

While process-wide lockdown (Tier 1) is a proven conceptual model (demonstrated by Elasticsearch's native lockdown mechanisms), its implementation in `jseccomp` is entirely experimental. 

Similarly, thread-scoped surgical containment (Tier 2) provides a targeted proof-of-concept isolation boundary. However, the entire library — including both process-wide and thread-scoped modes — remains an **untested research experiment** that should not be used in any production environments.

*Next up: Part 3: The Attacks jseccomp Stops*