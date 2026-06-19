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
| **OS-Level** | Attacker uses path traversal (`../../etc/shadow`) or malicious symlinks to access host files. | `openat2` syscall with `RESOLVE_BENEATH` flag at the supervisor proxy layer. | **Fully Solved** |
| **Execution-Level** | Attacker achieves native execution and spoofs JVM stack traces to bypass tool restrictions. | Tier 1 global `NO_EXEC` / `W^X` memory protection paired with Stacktrace Scoping. | **Fully Solved** |
| **Logical/Semantic** | Attacker prompts the agent to perform malicious actions *within* the tool's allowed scope (e.g. dropping a DB table). | Out of scope for kernel-level sandboxing. Must be solved using **Agent Compartmentalization**. | **Not Solved at Syscall Level** |

### Implementing Agent Compartmentalization
To mitigate Logical Confused Deputy vulnerabilities, multi-agent systems must avoid "God Agents." Instead, deploy multiple micro-agents:
*   **Web Agent:** Thread-scoped to allow network syscalls but block all filesystem writes.
*   **Database Agent:** Thread-scoped to allow connection to the DB port but block all web outbound connections.
*   If the Web Agent is prompt-injected, the kernel blocks it from writing to the DB directly. It must communicate with the DB Agent, which acts as a semantic barrier.

---

## 3. Threat Model: The Shared-Memory ACE Escape Caveat

Thread-scoped seccomp (Tier 2) is **not** an absolute security boundary against an attacker with Arbitrary Code Execution (ACE) on the JVM thread.

*   **The Shared-Memory Vulnerability:** Because all JVM threads share the same heap and virtual address space, an exploit utilizing native memory corruption (e.g., via FFM `Unsafe` pointers or native helper libraries) can corrupt memory on unrestricted sibling threads or GC helper threads to execute arbitrary code.
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

### Why `mazewall` is Different
`mazewall` operates at the **Thread** boundary, enabling Agent Compartmentalization *inside* the JVM:
*   **The Reasoning Thread:** The thread parsing the LLM response is fully locked down. It cannot open files or network sockets.
*   **The Web Tool Thread:** When `searchWeb` is invoked, it runs on a thread with a `mazewall` seccomp filter that *only* allows outbound connections to public IPs (blocking local subnets).
*   **The DB Tool Thread:** When `queryDb` is invoked, it runs on a thread with a filter that *only* allows connections to the database IP.

**The Strategic Pitch:** Docker secures the application from the host. `mazewall` secures the agent's tools from each other. Because BPF thread filters take microseconds to apply and require zero serialization overhead to pass data (unlike IPC between containers), `mazewall` provides zero-trust isolation between natively executed tools without the latency of containerization.
