---
layout: default
---
# mazewall — Behavioral Sandboxing for JVM Applications

**Per-thread syscall sandboxing using Linux Seccomp-BPF and Landlock LSM, from inside the JVM.**

mazewall lets you wrap any Java/Kotlin `ExecutorService` with a kernel-enforced behavioral contract. The contract restricts exactly which syscalls and filesystem paths that thread pool can access — enforced by the Linux kernel, not application code.

While the examples are JVM-specific, the underlying concepts (Seccomp-BPF, Landlock, SBoB) apply to all backend runtimes and are relevant to anyone working on cloud-native security.

## Read the Article Series

1. **[Part 0: Your Threads Are All Equally Trusted — Should They Be?](presentation/article0-developer-primer.html)**  
   Process-wide vs thread-scoped sandbox basics, ExecutorService wrapping, and architectural trade-offs.

2. **[Part 1: Do You Really Know What Your App Is Doing at Runtime?](presentation/article1-threat-model.html)**  
   The threat model, SBoB concept, and why container-level profiles aren't enough.

3. **[Part 2: Let Your Code Build Its Own Sandbox](presentation/article2-profiler.html)**  
   Dynamic profiling: how to observe a workload and auto-generate its minimal policy.

4. **[Part 3: Thread-Scoped Containment Mechanics](presentation/article3-enforcement.html)**  
   FFM native bindings, errno safety races, Loom virtual thread carrier poisoning, GC safepoint whitelists.

5. **[Part 4: Exploit Scenarios & Kernel Blocking](presentation/article4-attacks.html)**  
   Log4Shell, fileless malware, JIT executable memory, and `io_uring` evasions — tested against mazewall.

6. **[Part 5: Ahead-of-Time SBoB with GraalVM](presentation/article5-graalvm.html)**  
   How AOT compilation changes the security picture and removes JIT runtime noise.

7. **[Part 6: Beyond the Thread — Isolates, WebAssembly, and Tooling](presentation/article6-isolates.html)**  
   Heap-level isolation via GraalVM Isolates, instruction-level isolation via WebAssembly, and the developer tooling roadmap.

---

## Repository

- **Source & Quick Start:** [github.com/Pilleo/mazewall](https://github.com/Pilleo/mazewall)
- **Design Docs:** [Containment Design](internals/designs/enforcer/containment-design.html) | [Security Considerations](internals/designs/core/security-considerations.html) | [Architectural Map](internals/designs/core/architectural-map.html) | [Kernel Primitives Roadmap](internals/designs/core/kernel-primitives-roadmap.html) | [Documentation Standards](internals/documentation-standards.html)
