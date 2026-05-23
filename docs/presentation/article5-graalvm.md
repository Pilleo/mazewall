# Generating an SBoB for Java: Production & AOT

> **Series overview:** This is Part 5 of a 5-part series on behavioral security for cloud-native applications. **What this part adds:** scaling SBoB generation to production environments—explaining the "Merge Fallacy" of dynamic runtimes, and how GraalVM Native Image's Closed-World assumption allows us to mathematically prune capabilities Ahead-of-Time (AOT).

---

In Parts 2 and 4, we saw how **mazewall** dynamically profiles a worker thread to generate a security policy and enforces that policy at the Linux kernel level, successfully blocking advanced exploits like asynchronous `io_uring` evasion.

However, when we transition from a focused microservice demo to a large-scale enterprise application, dynamic profiling faces a classical architectural limitation: **the scale of dynamic paths.**

Modern JVM frameworks (like Spring Boot, Micronaut, and Quarkus) are massive, dynamic engines. They heavily rely on reflection, runtime bytecode generation, dynamic proxying, and deep dependency graphs.

Today, we address how to scale SBoB generation to these massive systems, why dynamic analysis alone is insufficient, and how **GraalVM Native Image** and Ahead-of-Time (AOT) compilation provide a mathematically complete approach to behavioral sandboxing.

---

## The Dynamic Scaling Problem: The "Merge Fallacy"

Dynamic profiling via test suites (as shown in Part 2) is highly effective for isolated, thread-scoped worker tasks. However, trying to generate a *process-wide* sandbox policy by merging dynamic profiling logs from multiple staging environments introduces a dangerous architectural anti-pattern: **The Merge Fallacy.**

Suppose you run your application in a staging environment for a week. You capture every system call and filesystem path, merge them, and deploy the resulting policy to production. 

This approach has two fatal flaws:

1.  **The Under-Specification Trap (False Positives):** If your test suite or staging environment fails to trigger a rare code path—such as a database reconnect loop, a DNS rollover, or an annual PDF reporting generation task—that specific system call or directory will be missing from your compiled policy. When that rare event occurs in production, the kernel will block the call, crashing your application.
2.  **The Over-Specification Trap (Least Privilege Erosion):** To prevent these crashes, security teams often aggressively merge profile logs from every developer, test run, and staging deployment. By the time you merge all dynamic execution logs, your whitelist has expanded so much that it covers almost the entire system call table. The sandbox loses its teeth, violating the principle of least privilege.

This is the Merge Fallacy: **combining discrete dynamic traces does not equal a mathematically complete or secure static policy.**

To secure dynamic, complex runtimes without crashing rare operational paths, we must shift from dynamic discovery to **static, mathematical proof.**

---

## Enter GraalVM: The Closed-World Assumption

**GraalVM Native Image** compiles Java applications Ahead-of-Time (AOT) into standalone native executable binaries. 

To compile a native binary, GraalVM relies on a strict architectural requirement: **The Closed-World Assumption.**

```
   Java Bytecode & Dependencies
                |
                v
     [GraalVM Points-To Analysis] <--- Evaluates whole program
                |
                v
    [Pruning: Unreachable Code Deleted]
                |
                v
      Standalone Native Binary
```

Before compilation, GraalVM performs a global, static **points-to analysis**. It starts at the application's entry point (`main` method) and mathematically traces the call graph across all classes, methods, and third-party dependencies. If a method or class is not mathematically reachable via any active execution path, it is permanently pruned and deleted from the final binary.

This closed-world compilation completely changes the game for behavioral sandboxing:

1.  **Dead Code Elimination:** In a standard JVM, a 100KB microservice carries the entire 100MB JVM runtime (including management agents, compiler stubs, and unused NIO packages). If an attacker achieves ACE, they can invoke any JVM native method. In a GraalVM binary, all unreachable code—including unused native bridges—is gone.
2.  **Deterministic Dependency Graph:** AOT compilation forces the resolving of all dynamic classloading, reflection, and proxy generation at build time. The final native binary contains a static, immutable instruction set.

---

## Static SBoB: Compiling Rules from the Abstract Syntax Tree (AST)

Because a GraalVM native binary has a deterministic, fully resolved call graph, we no longer need to run the application to *discover* what it does. We can analyze the compiled binary statically to **derive the exact security contract.**

At compile time, we can parse the binary's Abstract Syntax Tree (AST) to generate the SBoB:

1.  **Syscall Mapping:** We scan the fully resolved native binary for all occurrences of assembly instructions that invoke system calls (`syscall` on x86_64 or `svc` on aarch64).
2.  **Call-Graph Tracing:** We map each system call back to the logical application method that invoked it.
3.  **AST Policy Generation:** By analyzing the call graph, we statically prove that the application only invokes a specific subset of system calls (e.g. `read`, `write`, `epoll_wait`). We can automatically compile these system calls into a minimal Seccomp profile and embed it directly into the binary's metadata.

This is a **Static SBoB**. It is mathematically complete (zero false positives for rare operational paths) and enforces absolute least privilege (no dead code or unused stubs can ever inject hidden syscalls).

---

## The Hybrid Model: Combining Static and Dynamic SBoB

In production, the ideal cloud-native security model is a **hybrid architecture** that leverages both GraalVM's static pruning and **mazewall**'s thread-scoped dynamic containment:

```
+--------------------------------------------------------+
|              Host Kernel (Docker / K8s)                |
|  Static SBoB applied globally to the GraalVM binary    |
|  Restricts process-wide host access                    |
+--------------------------------------------------------+
                           |
                           v
+--------------------------------------------------------+
|             GraalVM Native Process (Tier 1)            |
|  Policy.NO_EXEC process-wide lockdown applied at init |
+--------------------------------------------------------+
                           |
                           v
+--------------------------------------------------------+
|          mazewall Sandboxed Thread (Tier 2)            |
|  Dynamic thread-scoped policy (e.g. PURE_COMPUTE)      |
|  Surgically locks down untrusted task worker threads   |
+--------------------------------------------------------+
```

1.  **Process-Wide Static Floor:** At the host level (Kubernetes/Podman), you run the compiled GraalVM binary using a highly restrictive static Seccomp profile derived during compilation. This blocks any system calls not present in the program's static call graph.
2.  **Process-Wide Native Lockdown (Tier 1):** At application startup, the binary invokes `mazewall`'s process containment (`ContainedExecutors.installOnProcess()`) to permanently lock out shell executions (`execve`).
3.  **Thread-Scoped Dynamic Isolation (Tier 2):** For specific threads handling untrusted user input, you wrap your executors with `mazewall` using the dynamic profiles generated during unit testing.

By combining these layers, you achieve defense in depth. An attacker who compromises a worker thread is confined by Landlock and Seccomp at the thread level. Even if they somehow bypass the thread filter, they are blocked by the process-wide `NO_EXEC` lockdown, and finally backed stop by the host-level GraalVM static Seccomp profile.

---

## Summary of the 5-Part Journey

We have reached the end of our behavioral security journey. Let's trace how the pieces connect:

*   **Part 1: The SBoB Concept** — We established the shift from blunt host-level boundaries to granular behavioral contracts (Software Bill of Behavior).
*   **Part 2: Let Your Code Build Its Own Sandbox** — We introduced **mazewall** and demonstrated how developers can dynamically profile worker threads (`Profiler.profile`) to automatically generate security policies without guessing rules.
*   **Part 3: Thread-Scoped JVM Mechanics** — We examined the advanced systems-level mechanics of JVM sandboxing, including the Java FFM native bridge, JIT memory protection, Loom carrier contamination prevention, and the golden rule of JVM safety (never block synchronization syscalls).
*   **Part 4: The Attacks We Stop** — We put mazewall in the laboratory and verified its defense against real exploits, culminating in the co-enforcement of Seccomp and Landlock blocking asynchronous `io_uring` evasion.
*   **Part 5: Production & AOT** — We explored how to scale this architecture to enterprise production, using GraalVM Native Image to mathematically prune capabilities Ahead-of-Time.

---

## What You Can Do Today

Behavioral sandboxing is transitioning from an experimental PoC to standard production-grade architecture. You can start adopting this security philosophy today:

1.  **Adopt a Contract Mindset:** Start auditing and documenting what system resources and network endpoints your microservices actually require at runtime.
2.  **Experiment with Mazewall:** Check out the [mazewall repository](https://github.com/io-mazewall/mazewall), run the dynamic profiler against your JUnit tests, and inspect the generated Kotlin DSL.
3.  **Build with GraalVM:** Compile your microservices into native binaries to automatically prune dead code and eliminate unused runtime capabilities.

By moving your architecture toward unprivileged, uncompromised, and contract-aligned sandboxing, you ensure that even when the next high-severity Remote Code Execution zero-day hits, your application is structurally immune.

---

*Thank you for reading the series! Feedback, issues, and contributions are welcome in the [mazewall repository](https://github.com/io-mazewall/mazewall).*
