# Why Would We Do the Same Thing That Failed?

[![← Part 1](https://img.shields.io/badge/←_Part_1-Threat_Model-6366f1)](article1-threat-model.md)
[![Series Home](https://img.shields.io/badge/Series-Home-1e293b)](../../README.md)
[![Part 2 →](https://img.shields.io/badge/Part_2_→-Dynamic_Profiling-6366f1)](article2-profiler.md)

> **This is an interlude in the series.** Part 1 established the threat. Before we explain how mazewall works, we need to address the obvious objection: *thread-level sandboxing has been tried before. It failed. Why are we doing it again?*
>
> The short answer: because the failure conditions do not apply to this threat model. The long answer requires some precision about what actually failed and why.

---

## The Question That Deserves a Direct Answer

If you work in security, you probably know that thread-level system call filtering has been on the table for a long time. You may also know that serious teams — including the Chromium team — tried it, concluded it was fundamentally broken, and abandoned it.

So when someone says "we apply kernel-enforced syscall filters to individual JVM threads," the instinctive reaction from a security engineer should be: *"We know where this road goes."*

That instinct is correct. And also, in this specific context, wrong.

This article explains why.

---

## What Failed, and Why It Failed

In 2009, the Chromium team experimented with an "untrusted thread / trusted helper thread" model. The idea was architecturally attractive: instead of spawning a full separate process for every browser tab (expensive), apply tight system call restrictions directly to the thread handling untrusted content, while keeping a trusted sibling thread available to relay privileged operations.

They abandoned it. Not because the kernel API wasn't capable enough, but because of a more fundamental problem.

**The Linux kernel does not distinguish between a process and a thread.** Both are Tasks (`task_struct`) internally. The difference is a single `clone()` flag:

- **Processes** are created *without* `CLONE_VM`. They get their own hardware-mapped virtual memory space. Physical page tables separate them at the CPU level.
- **Threads** are created *with* `CLONE_VM`. They share the same address space. Every byte of memory on the heap is readable and writable by every thread in the process.

A Seccomp filter installed on Thread A only restricts *what Thread A can ask the kernel to do*. It says nothing about what Thread A can do to Thread B's stack.

If an attacker achieves native code execution on a sandboxed thread — for example, via a memory corruption vulnerability in a C++ component — they can write shellcode directly into a sibling thread's stack or code segment. They don't need to call a system call themselves. They hijack Thread B's execution and *make Thread B do it for them*. The sandboxed thread becomes a relay. The filter is circumvented entirely.

The conclusion was clear: for C and C++ code, thread-level kernel filtering is a logical boundary, not a physical one. And logical boundaries are only as strong as the code that respects them.

---

## What Actually Changed

The failure was not a failure of Seccomp or Landlock. It was a failure of the execution environment. Chromium's threads ran native C++ — code that can write to arbitrary memory addresses. The JVM's threads run inside a managed runtime that enforces type safety and prevents raw pointer manipulation.

But we need to be precise here, because "JVM type safety" and "memory isolation" are not the same thing.

**All JVM threads share the same process address space.** A thread running a sandboxed task and a thread running uncontained work are in the same heap. `sun.misc.Unsafe` and FFM pointers can write to arbitrary memory. The JVM does not provide *physical* memory isolation between threads — process-level mechanisms like separate address spaces or hardware page tables do that, and the JVM does not have them.

What the JVM provides is a **type-safety barrier for standard bytecode execution**. An attacker who is restricted to executing valid Java bytecode through normal application code paths — manipulating a URL parameter, sending a malicious payload, exploiting a deserialization gadget — cannot forge raw pointers or write to sibling thread stacks. They are constrained to what the JVM type system allows.

This distinction matters for how we think about the threat model:

| Threat | Cross-thread escape possible? |
|---|---|
| C / C++ buffer overflow in a native library | ✅ Yes — writes raw memory directly |
| **Standard Java bytecode on a JVM thread** | **❌ No — type system prevents raw pointer forging** |
| Java code reaching `sun.misc.Unsafe` via gadget chain | ✅ Yes — explicit escape hatch around type safety |

For the specific threat we are trying to stop — **trusted code processing untrusted data** — the type-safety barrier holds. An attacker sending a malicious SSRF payload, a crafted XML document triggering XXE, or a path traversal string is constrained to what the standard library does with that data, executing on the thread that the Seccomp filter covers.

---

## The Two-Tier Design and What It Actually Defends

Mazewall is not Tier 2 alone. It is always two layers stacked deliberately:

**Tier 1 (`installOnProcess(Policy.NO_EXEC)`)** installs a process-wide Seccomp filter at startup that blocks `execve`, `fork`, `vfork`, and `execveat` on every OS thread in the JVM — including the `ForkJoinPool.commonPool()`, `Thread.startVirtualThread(...)`, and any thread the attacker can dispatch to.

**Tier 2 (`ContainedExecutors.wrap(executor, policy)`)** adds tighter per-syscall and per-path restrictions on specific thread pools that handle untrusted input.

With both in place, here is what the combined defense actually stops and what it does not:

| Attack vector | Defence |
|---|---|
| SSRF / XXE / path traversal on a contained thread | ✅ Blocked by Tier 2 on that thread |
| Native JNI library tries to spawn a shell | ✅ Blocked by Tier 1 (process-wide, no exec anywhere) |
| Java deserialization RCE dispatching via `CompletableFuture.runAsync(Runtime.exec(...))` | ✅ Blocked by Tier 1 (blocked process-wide for all threads) |
| Java deserialization RCE doing SSRF from `ForkJoinPool.commonPool()` (no Tier 2 filter on it) | ❌ Not blocked — Tier 1 only covers exec, Tier 2 does not cover that thread |
| `sun.misc.Unsafe` pointer manipulation to corrupt sibling thread memory | ❌ Not blocked — physical memory is shared |

The gap in row four is real and worth being explicit about: if an attacker achieves *arbitrary Java code execution* on any thread, they can dispatch network or filesystem operations to uncontained sibling threads via standard Java concurrency APIs, bypassing Tier 2. Tier 1 prevents them from escalating to a shell, but it does not prevent SSRF or file reads from those uncontained threads.

This is why the threat model is specific. Mazewall is designed for the **data-plane attack scenario** — a backend service running trusted code over untrusted input. The primary threats are SSRF, XXE, path traversal, and native library exploits. Against those threats, the two-tier stack provides a real and meaningful defence. Against an attacker who can already execute arbitrary Java logic in your process, the model is different and the tooling required is different (Tier 1 still limits the worst escalation by dropping `execve`, `fork`, `vfork`, and `execveat` globally, but you are in a harder problem space).

The security considerations document covers the full threat matrix in detail, including when GraalVM Native Image fundamentally changes the calculus by making dynamic code execution physically impossible.

---

## The Chromium Answer

The Chromium team was right for Chromium. A browser renderer handles arbitrary untrusted C++ code, WebGL shaders, and V8 JIT output. A single memory corruption vulnerability can hand an attacker native RCE before any sandbox has time to respond. Process-level isolation with separate page tables is the correct answer when the threat is native memory corruption in untrusted C++ code — a threat that operates below the JVM's type-safety layer entirely.

Chromium *also* uses Intel MPK (memory protection keys) inside the renderer process today — to safely JIT-compile WebAssembly with fast write-XOR-execute page permission toggles. Thread-level isolation came back into Chromium, in the environment where it could be made to work: a runtime-controlled JIT with hardware page tagging.

Mazewall's use case is narrower: a backend JVM service running trusted code over untrusted data. The threat is not native memory corruption in untrusted C++ modules. It is application-level attacks — malformed inputs, server-side request forgery, injection attacks — that try to abuse what the trusted code does with them. In that scenario, thread-level kernel filtering on the thread that processes those inputs, combined with a process-wide execution block, is a sound and proportionate defence.

---

## Next: Building the Policy

Thread-level sandboxing on the JVM is sound. But to install a Seccomp filter, you need to know which system calls your application actually makes — and you need to know this without breaking your application in production or in CI.

That is the problem the profiler solves.

**[Part 2: Let Your Code Build Its Own Sandbox →](article2-profiler.md)**
