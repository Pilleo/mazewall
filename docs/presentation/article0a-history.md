# The Kernel Has Always Known Better Than Your Code

[![Series Home](https://img.shields.io/badge/Series-Home-1e293b)](../../README.md)

> **No prior knowledge assumed.** If you have ever wondered why browsers run multiple processes for a handful of tabs, why Elasticsearch refuses to start without certain kernel features, or why your backend service trusts all of its own threads equally — this is for you.

---

## The Software You Trust Is Already Sandboxed. Yours Isn't.

Open Activity Monitor or `htop` right now. Find Chrome or Firefox. Count their processes.

Even with only a handful of tabs open, you will see a dozen or more separate OS processes: a main browser process, several renderer processes grouping tabs by site origin, a GPU process, a network service process, extension processes. Firefox similarly runs a pool of up to eight content processes shared across all tabs. Neither browser runs one renderer per tab — they use process *pools* — but even so, the count is deliberately much higher than a naive single-process implementation would require.

This is not a bug or a memory leak. It is a deliberate security architecture decision. And behind it sits a Linux kernel feature that has been quietly protecting production systems since 2005 — one that most backend services have never touched.

**Why doesn't your application use it?**

---

## 2005: The Kernel Gets a Lock

The story starts not with browsers or containers, but with a grid computing company called Cpushare. In 2005, Andrea Arcangeli contributed a feature to Linux 2.6.12 called **seccomp** — *secure computing mode*.

The original design was blunt. A process that enabled seccomp strict mode was immediately locked to exactly four system calls: `read`, `write`, `exit`, and `sigreturn`. Nothing else. Any other syscall killed the process instantly with `SIGKILL`. The use case was specific: Cpushare wanted to run untrusted code submitted by strangers on their machines without the code being able to do anything harmful. The kernel enforced it. No bypass was possible.

This original strict mode was useful in narrow contexts but too limiting for general applications — most software needs more than four system calls to function. It saw limited adoption outside specialized compute environments.

The more significant development came a few years later.

---

## 2008–2012: Browsers Define the Problem

When Google shipped Chrome in 2008, they brought a security architecture that was unusual at the time: every renderer process — the component that actually executes web page code — ran in a separate OS process with heavily restricted privileges.

The threat model was clear. A browser renderer executes arbitrary JavaScript from arbitrary websites. If a memory corruption vulnerability in the JavaScript engine gave an attacker code execution, the damage should be contained to that renderer. It should not be able to read files from disk. It should not be able to connect to internal network services. It should not be able to escalate to the rest of the system.

The mechanism was process isolation: separate address spaces, separate privilege levels, and an IPC channel to request privileged operations from the trusted browser process. A compromised renderer was a contained renderer.

This worked well. It still works well. But it was expensive enough that in 2009, the Chromium team tried a cheaper alternative: instead of a separate process for each isolated component, apply syscall restrictions directly to specific threads within the existing process. A restricted thread to handle untrusted content, a trusted sibling thread to relay operations that required privileges. Thread-level filtering rather than process-level isolation.

It worked. It shipped.

And then they hit a wall.

---

## The Wall: Why Thread-Level Filtering Failed in C++

The wall is not an API limitation. It is a property of the execution environment.

A Seccomp filter on a thread restricts what *that thread* can ask the kernel to do. It says nothing about what that thread can do to the memory of its siblings. Threads, unlike processes, share an address space. Every byte of heap memory is accessible to every thread in the process.

To work around the brutal 4-syscall limit of original seccomp without kernel changes, Markus Gutschke and the Chromium team built an extraordinarily creative hack: a **trusted thread** architecture. When a sandboxed thread needed to issue a forbidden system call (like `open` or `mmap`), it sent an RPC request over a socket pair to a trusted sibling thread inside the same process, which validated the request and made the syscall on its behalf.

Because the untrusted thread shared memory with the trusted thread, the trusted thread could not trust the stack or heap at all—a compromised untrusted thread could overwrite stack variables mid-execution. To prevent memory tampering, the Chromium team wrote the trusted thread's validation loop in **handcrafted assembly** (x86/x86_64). The trusted thread operated exclusively out of CPU registers, never touching memory for sensitive state validation ([LWN: Secure computing sandboxes](https://lwn.net/Articles/346800/), [ImperialViolet: Seccomp improvements](https://www.imperialviolet.org/2009/08/26/seccomp.html)).

It was an engineering marvel, but it highlighted the fundamental challenge: an attacker who achieves native code execution on a restricted C++ thread — through a buffer overflow, use-after-free, or type confusion — operates below logical boundaries. They can corrupt sibling thread memory or hijack execution pointers directly.

The heroic assembly hack was eventually retired when proper kernel filter support arrived.

---

## 2012: Seccomp Gets a Filter Language

In 2012, Will Drewry at Google contributed **Seccomp-BPF** to Linux 3.5. Instead of the original all-or-nothing strict mode, processes could now install arbitrary BPF programs as syscall filters — allowlists and denylists based on syscall number, arguments, and calling context.

This was a significant step. Seccomp was no longer a blunt instrument. A process could now say: "allow `read`, `write`, `connect`, and `sendmsg`, but block `execve`, `fork`, and `ptrace`" — and the kernel would enforce it on every syscall, for the lifetime of the process, with no way to uninstall the filter once set.

Chrome adopted Seccomp-BPF immediately for its renderer sandbox. The Chromium thread experiment, which had been working around the absence of a proper syscall filter API, was superseded.

Docker launched in 2013 and eventually added Seccomp-BPF support. By 2016 it became a default: every Docker container runs with a Seccomp profile blocking about 44 high-risk syscalls out of roughly 350. Your containers today are almost certainly running with this protection — applied by the container runtime, invisibly.

That same year, Elasticsearch 5.0 shipped with process-wide syscall filtering as a **required bootstrap check** on Linux. It refuses to start if the kernel does not support it. Millions of production clusters have been running with this protection since 2016 — a process-wide Seccomp-BPF policy that blocks `execve`, `fork`, module loading, and other high-risk operations for the entire JVM process. The filter has almost never caused a problem, and it has quietly prevented entire categories of post-exploitation attack from working.

Here is what that means concretely: an attacker who exploits a vulnerability in Elasticsearch — a deserialization gadget, a scripting engine bug, a query parser flaw — achieves remote code execution in the JVM. They then try `execve("/bin/sh")`. The kernel returns `EPERM`. Not Elasticsearch's code rejecting it. The kernel. The reverse shell that would work against almost any unprotected Linux process is dead on arrival.

A fair question at this point: if Docker already applies a Seccomp profile to every container by default, why does Elasticsearch bother with its own? Three reasons. First, Docker's default profile is a generic allowlist designed to work for *any* container — it permits around 311 syscalls. Elasticsearch's own filter is tuned to what a JVM search engine specifically needs: considerably more restrictive. Second, operators routinely run containers with `--security-opt seccomp=unconfined`, particularly in cloud-managed environments, development setups, or when troubleshooting. Elasticsearch's self-applied filter survives that misconfiguration — the application protects itself regardless of what the orchestration layer does. Third, the container runtime's seccomp filter guards the container boundary; the application-level filter guards the process boundary. They are complementary, not redundant.

---

## 2021: The Filesystem Gets the Same Treatment

In 2021, Linux 5.13 introduced **Landlock** — a complement to Seccomp that applies the same principle to filesystem and network access. Where Seccomp filters system call numbers, Landlock enforces path-based rules: a process (or thread) can declare that it should only be able to read from specific directories, write to others, and connect to specific ports. The kernel enforces these rules at the inode level, after path resolution, avoiding time-of-check/time-of-use races.

Like Seccomp, Landlock is *unprivileged*: any application can restrict itself without root access. Like Seccomp, a Landlock ruleset cannot be loosened once installed. The kernel treats it as a one-way lock.

---

## The Gap That Remains

Two decades of production use have established that **process-wide restriction works**. It is proven, cheap, and dramatically underused by application developers — most backend services leave these kernel features entirely to their container runtime, relying on a generic, lowest-common-denominator profile rather than one tuned to what their application actually does.

But process-wide restriction is coarse-grained. Consider a typical backend service with several distinct kinds of work:

- **HTTP handlers** — parse user input, query a database, return JSON
- **Document processors** — read files from disk, convert formats, render output
- **Outbound integrators** — connect to external APIs, send notifications

From the kernel's perspective, every thread in the process is identical. A compromised document processor has the same permission to open network sockets as the outbound integrator. The HTTP handler can attempt to write to arbitrary filesystem paths. Process-wide restriction blocks the worst escalation paths — `execve`, `fork`, module loading — but it cannot express the question: *why should the document processor be allowed to make outbound network connections at all?*

That requires thread-scoped restriction. And we established above that thread-scoped restriction failed in Chromium's case.

The key variable is not the kernel. It is not the language per se either. It is two separate questions: can an exploit in one thread *arbitrarily write to the memory of another*? And do the units of work you want to isolate actually map to OS-level threads?

## When Thread-Level Filtering Is Actually Sound

The Chromium experiment failed because C++ allows arbitrary writes to memory addresses. An attacker with native code execution — through a buffer overflow, use-after-free, or type confusion — can reach any memory location in the process. The Seccomp filter on a restricted thread becomes irrelevant: the attacker never needs to use that thread's syscall path. They corrupt a sibling's stack and hitch a ride.

This is not a flaw unique to C++. Any execution environment that permits arbitrary memory writes has the same property — and any environment that prevents them gets a meaningful security benefit from thread-level filtering. The problem is *manual memory management*, not any particular language.

But memory safety alone is not sufficient. The second requirement is that the work units you want to isolate — the "HTTP handler scope" or the "document processor scope" — must actually correspond to *OS-level threads*, because Seccomp filters operate at the OS thread level. The kernel has no concept of goroutines, fibers, green threads, or event loop tasks.

With both criteria in mind:

### JVM languages (Java, Kotlin, Scala)

Strong fit on both counts. JVM threads are OS threads — 1:1 mapping guaranteed by the JVM specification. The bytecode verifier prevents raw pointer manipulation in managed code. An attacker exploiting a backend service through injection, SSRF, or deserialization gadgets cannot forge memory addresses. Both process-wide and thread-scoped filtering apply to the correct unit of work.

### C# and F# (.NET / CLR)

Same strong fit as JVM. The CLR's IL verifier enforces managed type safety. .NET threads are OS threads. Both layers apply cleanly.

### Rust

Conditional. Rust threads are OS threads, and Rust's ownership system prevents memory corruption in *safe* code — the cross-thread memory escape the Chromium experiment exposed is not possible in pure safe Rust.

The caveat is real: `unsafe` blocks are common in Rust's ecosystem, and many performance-sensitive libraries use them internally. FFI calls into C also bring C/C++ risk back. The value of thread-level filtering in a Rust service depends on the `unsafe` footprint of the dependency graph.

### C and C++

The Chromium wall, exactly. Manual memory management means native code execution implies arbitrary memory writes. Thread-level filtering is a logical boundary an attacker with code execution can step across. Process-wide restriction still provides meaningful value — blocking `execve`, `fork`, module loading — but thread-scoped profiles do not add the same safety benefit as they do in managed runtimes.

### Go

Process-wide only. This is a thread-model problem, not a memory-safety problem. Go is memory-safe and its type system prevents raw pointer arithmetic in standard code.

But Go's concurrency model is M:N: many goroutines are multiplexed over a smaller pool of OS threads by the Go scheduler. A goroutine can migrate between OS threads at any scheduler preemption point. Installing a Seccomp filter on an OS thread restricts that OS thread — it does not restrict the goroutine abstraction, because the OS does not know goroutines exist. You cannot express "this goroutine may not open network connections" at the kernel level. Process-wide restriction works fine; goroutine-scoped policy is not achievable through OS-level thread filtering.

### Node.js

Complicated, with caveats. Node's primary model is a single-threaded event loop — one OS thread handles all JavaScript I/O and callbacks. Thread-level filtering on a single-threaded event loop adds nothing useful for per-request isolation.

Worker Threads (added in Node 10) are different: each Worker runs in its own V8 isolate with a separate JavaScript heap, on its own OS thread. Workers explicitly cannot share heap objects with the main thread (SharedArrayBuffer aside). For architectures that deliberately separate trust boundaries into distinct Worker Threads, per-thread filtering could apply and the cross-thread memory problem does not arise in the same form. But this requires designing your application around Workers for isolation, which most Node services do not do.

### Python

Not straightforward. Python threads are OS threads, so the kernel plumbing exists. Python's interpreter itself is memory-safe — standard Python code cannot forge raw pointers.

The complications: CPython's Global Interpreter Lock (GIL) means only one thread executes Python bytecode at a time. C extensions run without the GIL and are written in C — a vulnerability in a C extension providing code execution lands you in C/C++ territory regardless of what Python guarantees. Python 3.12+ introduced an experimental free-threaded mode (no GIL), which makes per-thread isolation more interesting conceptually, but also removes the implicit serialization that previously limited concurrent damage.

Process-wide restriction is straightforwardly useful for Python. Thread-scoped filtering is technically possible (threads are OS threads), but its real benefit depends heavily on whether the workload uses native extensions and how much C code is in the picture.

---

## Two Layers. Both Proven. Both Underused.

The practical upshot of this history:

**Process-wide syscall restriction** — available since Linux 3.5 (2012), in production at scale since at least Elasticsearch 5.0 (2016). Applied once at startup. Blocks the most dangerous escalation syscalls globally across every thread. Almost free at runtime. Dramatically underused by application developers, who leave it entirely to their container runtime with a generic profile.

**Thread-scoped syscall restriction** — the approach Chromium could not safely use in C++, but that changes for managed and memory-safe runtimes. Applied per component. Restricts specific thread pools to the syscalls and filesystem paths they genuinely require. Turns a compromised PDF processor into a thread that physically cannot open a network socket, regardless of what vulnerability was exploited.

Neither requires new hardware. Neither requires privileged access — both Seccomp and Landlock can be installed by any unprivileged process after setting `PR_SET_NO_NEW_PRIVS`. The primitives have been in the kernel for over a decade.

What has been missing is the tooling to apply them at the granularity that application architecture actually demands, and a clear understanding of which runtimes make the thread-level layer meaningful rather than illusory.

## Where This Is Going

The industry's default posture — apply one generic Seccomp profile to the container, monitor at the cluster level, alert on anomalies — is valuable. But it treats applications as black boxes and relies on catching problems after they occur.

The direction the field is moving is toward **behavioral contracts**: explicit declarations of what each component of a service is expected to do at the syscall level. Not inferred from logs after the fact, but expressed alongside the code and enforced inline by the kernel. An emerging concept called the **Software Bill of Behavior (SBoB)** aims to make these contracts portable and auditable — shipped alongside the application binary and enforceable at every layer of the stack.

The kernel primitives that make this possible have existed since 2005 and 2012. The history is long. The gap between what is technically possible and what most services actually deploy remains wide.

---

**The next question is practical:** to install a Seccomp filter, you need to know exactly which system calls your application makes — under normal conditions, under load, across all code paths. Guess wrong and you crash your own service. Guess too conservatively and you leave the filter useless.

How do you generate that list without manual inspection of every library in your dependency tree? And how do you express it at thread granularity rather than for the whole process?

*Those questions are what the next article in this series addresses.*
