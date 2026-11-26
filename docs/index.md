---
layout: default
---
# mazewall — Behavioral Sandboxing for JVM Applications

**`0.1.0`: process-wide Seccomp-BPF lockdown for Linux x86_64 JVMs.**

The first release, `io.mazewall:mazewall-lockdown`, is planned for GitHub Packages. See [why you would use lockdown](https://github.com/Pilleo/mazewall/tree/master/lockdown), or [try a local snapshot](https://github.com/Pilleo/mazewall/blob/master/GETTING_STARTED.md#gradle-dependency).

The articles below also cover experimental per-thread sandboxing, Landlock, and profiling. Those features are not part of the `0.1.0` artifact.

## Read the Article Series

1. **[Prologue: The History of Linux Trust Issues (And Why You Should Develop Them Too)](presentation/article0a-history.html)**  
   How OS boundaries evolved from 2005 to 2021, why thread isolation failed in C++, and how managed runtimes change the rules.

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
- **Design Docs:** [Portal Architecture](internals/designs/portal-design.html) | [Process Portal (Broker/Worker)](internals/designs/enforcer/process-portal-design.html) | [Portal Service Isolation](guides/portal-service-isolation.html) | [Containment Design](internals/designs/enforcer/containment-design.html) | [Security Considerations](internals/designs/core/security-considerations.html) | [Architectural Map](internals/designs/core/architectural-map.html) | [Kernel Primitives Roadmap](internals/designs/core/kernel-primitives-roadmap.html) | [Documentation Standards](internals/documentation-standards.html)
