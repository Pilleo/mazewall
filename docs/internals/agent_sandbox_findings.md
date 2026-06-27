# AI Agent Sandboxing & Security Findings

This document synthesizes our architectural findings, threat models, and integration strategies for securing LLM-based autonomous agents using `mazewall`.

---

## 1. The Core Value Proposition: Blast Radius Confinement

Unlike traditional web applications, AI agents are dynamically driven by untrusted natural language prompts (Prompt Injection) and frequently require tools that run code, fetch web resources, or read files.

*   **Not an LLM Firewall:** `mazewall` operates at the Linux kernel syscall level. It does not inspect the semantic contents of requests (e.g., detecting malicious SQL strings or prompt injection payloads).
*   **Confinement:** Instead, it guarantees **Blast Radius Confinement**. If an agent is compromised via prompt injection, the kernel physically prevents it from escaping its declared sandbox boundaries (e.g., spawning shell processes, scanning the host network, or reading forbidden configuration files).

---

## 2. The Confused Deputy Matrix

AI agents act as "deputies" on behalf of users. When an agent is compromised, attackers attempt to exploit this relationship. `mazewall` mitigates this at three distinct layers:

| Layer | Threat Vector | Mitigated By | Status |
| :--- | :--- | :--- | :--- |
| **OS-Level** | Attacker uses path traversal (`../../etc/shadow`) or malicious symlinks to access host files. | `openat2` syscall with `RESOLVE_BENEATH` flag at the supervisor proxy layer. | **Designed (Not Yet Implemented)** |
| **Execution-Level** | Attacker achieves native execution and spoofs JVM stack traces to bypass tool restrictions. | Tier 1 global `NO_EXEC` / `W^X` memory protection paired with Stacktrace Scoping. | **Designed (Not Yet Implemented)** |
| **Logical/Semantic** | Attacker prompts the agent to perform malicious actions *within* the tool's allowed scope (e.g. dropping a DB table). | Out of scope for kernel-level sandboxing. Must be solved using **Agent Compartmentalization**. | **Not Solved at Syscall Level** |

### Implementing Agent Compartmentalization
To mitigate Logical Confused Deputy vulnerabilities, multi-agent systems must avoid "God Agents." Instead, deploy multiple micro-agents:
*   **Web Agent:** Thread-scoped to allow network syscalls but block all filesystem writes.
*   **Database Agent:** Thread-scoped to allow connection to the DB port but block all web outbound connections.
*   If the Web Agent is prompt-injected, the kernel blocks it from writing to the DB directly. It must communicate with the DB Agent, which acts as a semantic barrier.

---

## 3. Threat Model: The Shared-Memory ACE Escape Caveat

Thread-scoped seccomp (Tier 2) is **not** an absolute security boundary against an attacker with Arbitrary Code Execution (ACE) or even arbitrary Java logic execution on the JVM thread.

*   **The Shared-Memory Vulnerability:** Because all JVM threads share the same heap and virtual address space, an exploit utilizing native memory corruption (e.g., via FFM `Unsafe` pointers or native helper libraries) can corrupt memory on unrestricted sibling threads or GC helper threads to execute arbitrary code.
*   **The Java Concurrency Bypass:** An attacker capable of executing arbitrary Java logic (even without achieving native memory corruption) can easily bypass thread-scoped filters by spawning tasks on uncontained threads (e.g., via `CompletableFuture.runAsync()`). Refer to [§1 of SECURITY_CONSIDERATIONS.md](file:///home/leanid/Documents/code/java/jseccomp/docs/internals/SECURITY_CONSIDERATIONS.md#1-thread-level-vs-process-level-isolation) for the detailed concurrency pivot analysis.
*   **The Mandated Backstop (Tier 1):** Process-wide `NO_EXEC` and `mprotect` restrictions must be applied at startup (`ContainedExecutors.installOnProcess`). This eliminates the execution of injected native code, blocking the prerequisite step for stack-spoofing memory corruption.
*   **Roadmap (Tier 1 Expansion):** Process-wide namespaces (Mount, PID, Network) and cgroups v2 limits are required to bound the JVM at initialization. Thread-local namespaces are rejected due to JVM internal coordination conflicts.

---

## 4. Integration Strategy for Agent Frameworks (LangChain4j / Spring AI)

When proposing `mazewall` to frameworks like **LangChain4j** or **Spring AI**, it should be framed as a **Secure Tool Executor**.

### Key Integration Points:
1.  **Dynamic Code Execution Sandbox:** Force tools that execute dynamic Python or JS code (e.g., for data analysis) to run on threads bounded by process-wide or thread-scoped seccomp.
2.  **Declarative Tool Scoping:** Automatically wrap tool invocations in thread-scoped sandboxes using `ContainedExecutors` based on the tool's annotation configuration.
    ```java
    // Target Spring AI / LangChain4j integration concept
    @Tool
    @Sandbox(allowNetwork = false, allowedPaths = "/app/data/sandbox")
    public String executeLocalScript(String scriptPath) { ... }
    ```
3.  **Carrier Thread Safety:** Ensure the integration integrates cleanly with Project Loom virtual threads, taking care to implement the Supervisor Proxy queue limits to avoid carrier thread exhaustion.

---

## 5. Thread-Level Compartmentalization vs. Docker

A common misconception is that integrating `mazewall` with embedded polyglot engines (like GraalPy) is redundant to using Docker. 

While Docker is excellent at isolating an *entire application* from the host OS, it is physically incapable of securing **Native Java LLM Tools** from each other.

### The Limitation of Docker for AI Agents
In frameworks like LangChain4j, tools are Java methods running in the same JVM:
```java
@Tool("Search the web")
public String searchWeb(String url) { ... }

@Tool("Query internal DB")
public String queryDb(String sql) { ... }
```
If this Agent is deployed inside a Docker container, that container **must** be granted network access to both the public web and the internal database. If the Agent suffers a prompt injection and uses the `searchWeb` tool to execute an SSRF attack against the internal DB, **Docker will allow it**. Docker only sees the JVM process making a network call, which is authorized.

*Note on Network Policies:* While container orchestrators (like Kubernetes) can enforce Network Policies to restrict pod egress, these policies apply to the **entire container process**. They cannot distinguish between network requests originating from the `searchWeb` tool versus the `queryDb` tool since both requests originate from the same JVM process and container network interface.

### Why `mazewall` is Different
`mazewall` operates at the **Thread** boundary, enabling Agent Compartmentalization *inside* the JVM:
*   **The Reasoning Thread:** The thread parsing the LLM response is fully locked down. It cannot open files or network sockets.
*   **The Web Tool Thread:** When `searchWeb` is invoked, it runs on a thread with a `mazewall` seccomp filter that *only* allows outbound connections to public IPs (blocking local subnets).
*   **The DB Tool Thread:** When `queryDb` is invoked, it runs on a thread with a filter that *only* allows connections to the database IP.

**The Strategic Pitch:** Docker secures the application from the host. `mazewall` secures the agent's tools from each other. Because BPF thread filters take microseconds to apply and require zero serialization overhead to pass data (unlike IPC between containers), `mazewall` provides zero-trust isolation between natively executed tools without the latency of containerization.

---

## 6. Case Study: Comparative Analysis with NVIDIA OpenShell

We conducted a deep architectural review of **NVIDIA OpenShell** (GitHub and architecture logs) to compare its host/agent containment patterns with `mazewall`'s JVM-native model.

### Architectural Breakdown

| Layer | NVIDIA OpenShell (Out-of-Process) | mazewall (In-Process JVM Library) |
| :--- | :--- | :--- |
| **Sandbox Scope** | **Container Boundary:** Spawns a lightweight Kubernetes cluster (`K3s`) inside a Docker container for each agent. | **Process/Thread Boundary:** Manages Seccomp-BPF and Landlock rulesets directly inside the JVM address space. |
| **Egress Protection** | **L7 Proxy + TLS MITM:** Intercepts TCP sockets, maps them to PIDs via `/proc/net/tcp`, verifies parent processes, and filters L7 requests (resolving path/method). | **L4 Syscall Blocking:** Restricts `connect` or `socket` syscalls completely at the kernel layer, rejecting connections instantly without proxy overhead. |
| **Credential Safety** | **Upstream Secret Injection:** Sandboxed process only sees placeholder environment variables; proxy swaps placeholders upstream. | **In-Memory Thread Restriction:** Blocks restricted threads from reading security properties or environment maps. |
| **Syscall Hooking** | Uses Rust's `seccompiler` library to compile filters injected in child forks before executing agent binaries. | Manages raw Linux syscalls using Java's Foreign Function & Memory (FFM) API and loads them dynamically. |

---

### Core Lessons & Actionable Enhancements for `mazewall`

1. **Socket Address Family Argument Filtering (L4 Egress Evasion Prevention)**
   * *OpenShell Pattern:* OpenShell inspects the first argument (Address Family) of `socket()` to block raw packet capturing (`AF_PACKET`), Bluetooth, and VM sockets. It denies `AF_INET`/`AF_INET6` under network-blocking mode, but always permits local Unix Domain Sockets (`AF_UNIX`).
   * *Lesson for mazewall:* Blocking `socket` or `connect` completely breaks local IPC (e.g. databases, local daemon sockets). We should implement a `SocketAddressFamilyInspector` under the `SyscallInspectionPipeline` to inspect the address family, allowing `AF_UNIX` (local) while restricting `AF_INET`/`AF_INET6` (remote).

2. **JVM-Aware Filesystem Baseline Templates**
   * *OpenShell Pattern:* OpenShell starts with a blank Landlock ruleset and whitelists host directories recursively (e.g., read-only for `/usr`, `/lib`, `/etc`, and read-write for `/sandbox`, `/tmp`).
   * *Lesson for mazewall:* JVM-native tools require specific access to runtime libraries (e.g., `java.home`, classpath directories, SSL cert folders like `/etc/ssl/certs`). We should provide built-in templates inside `PolicyBuilder` to automatically pre-seed these JVM-critical paths as read-only, allowing developers to lock down filesystems with minimal manual path declaration.

3. **Telemetry & Profiler Code Stripping**
   * *OpenShell Pattern:* Telemetry can be completely stripped out at compile time using Rust Cargo features (`--no-default-features`).
   * *Lesson for mazewall:* To minimize security surface area and runtime overhead, all observer/profiler code (`:profiler` module, tracing sockets, log publishers) must be optionally strippable during build compilation (e.g. through Gradle build-time configuration or conditional code-gen) so production environments carry zero tracing baggage.

4. **Credential Isolation via Sibling Thread Delegation**
   * *OpenShell Pattern:* Replaces sensitive variables with placeholder strings and relies on an external proxy to inject secrets upstream, keeping secrets out of the agent process memory.
   * *Lesson for mazewall:* Because the JVM heap is shared, we cannot isolate secret strings on the heap from threads containing ACE exploits. However, we can use thread-scoped `mazewall` policies to block unauthorized threads from querying environment variables, forcing them to delegate secret resolution to secure sibling threads or credential containers.

---

## 7. Process Spawning & Stacktrace Enforcement Nuances

Through the agent sandboxing implementation, we discovered two key system-level behaviors when enforcing stacktrace-based rules on process spawning:

1. **Lost JVM Stacktrace on Child Process `execve`:**
   * When the JVM spawns a process, the child process runs in a separate PID.
   * When `execve` is supervised, the seccomp notification comes from the child process PID.
   * Since this new PID is not registered in the JVM's `threadRegistry`, calls to `Thread.getStackTrace()` yield an empty array.
   * Direct stacktrace validation on `EXECVE`/`EXECVEAT` is therefore not possible.
   
2. **ClassLoader/Safepoint Deadlocks on thread-creation `clone`:**
   * JVM thread creation and process spawning both invoke `clone` (or `clone3`) under the hood.
   * If `CLONE` is supervised, the supervisor must capture the stacktrace via `Thread.getStackTrace()`, which triggers a JVM safepoint.
   * Since the thread is blocked inside `Thread.start()` holding internal classloader and thread creation locks, waiting for a safepoint creates a permanent circular deadlock.

### The Solution:
* Allow `CLONE` and `CLONE3` entirely to ensure thread creation and standard JVM scheduling function without triggering supervisor inspections.
* Set `-Djdk.lang.Process.launchMechanism=vfork` to force the JVM to spawn processes using `vfork` or `fork` rather than `clone`.
* Supervise `VFORK` and `FORK` instead of `EXECVE`. Since `vfork` is executed on the calling JVM thread before the child process is fully detached, we can capture the calling JVM stacktrace perfectly and authorize or block the process spawn directly on the parent thread. 

For example, when `vfork` is intercepted on the parent thread, the supervisor receives the following JVM stacktrace:
```
[MAZEWALL] [DEBUG] process spawn (syscall=VFORK) stack:
  at java.lang.ProcessImpl.forkAndExec(ProcessImpl.java:-2)
  at java.lang.ProcessImpl.<init>(ProcessImpl.java:300)
  at java.lang.ProcessImpl.start(ProcessImpl.java:231)
  at java.lang.ProcessBuilder.start(ProcessBuilder.java:1078)
  at java.lang.ProcessBuilder.start(ProcessBuilder.java:1046)
  at io.mazewall.demo.agent.AgentTools.executeDataAnalysis$lambda$0(Tools.kt:153)
  at io.mazewall.enforcer.internal.ContainedExecutorWrapper.wrapCallable$lambda$0(ContainedExecutorWrapper.kt:51)
  at java.util.concurrent.FutureTask.run(FutureTask.java:328)
  at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1090)
  at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:614)
  at java.lang.Thread.run(Thread.java:1474)
```
The scoping policy can then safely inspect this trace, matching `AgentTools.executeDataAnalysis` to allow the execution, or denying it if it originates from an untrusted context (e.g. from `fetchWebpage`).

---
