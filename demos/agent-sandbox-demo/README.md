# AI Agent Sandboxing & Compartmentalization PoC

This module demonstrates how `mazewall` can secure LLM-based autonomous agents and protect host environments during the "agentic era." 

Unlike traditional out-of-process sandboxes (e.g., Docker or L7 proxies like NVIDIA OpenShell) which suffer from high latency and are unable to isolate Java tools running in the same JVM, `mazewall` enforces **Thread-Level Compartmentalization** and **Stacktrace-Enforced Execution** directly at the Linux kernel level.

---

## Demonstrated Threat Scenarios

This PoC runs a LangChain4j agent with three tools, demonstrating three common security exploits triggered via prompt injection:

1.  **SSRF / Network Egress Bypass (`--scenario ssrf`)**
    *   **Goal:** The agent has a `fetchWebpage` tool to scrape docs. Prompt injection forces it to hit the internal AWS metadata service (`http://169.254.169.254`).
    *   **Mitigation:** `mazewall` applies a Seccomp-BPF filter to the web fetcher thread, using a user-space supervisor to check and instantly drop TCP connections targeting private/local IP subnets.
2.  **Path Traversal (`--scenario path_traversal`)**
    *   **Goal:** The agent has an `analyzeUserFile` tool to read files in the `agent-workspace` directory. Prompt injection forces it to attempt a path traversal to a file outside the workspace (e.g. `../passwd`).
    *   **Mitigation:** `mazewall` applies Landlock LSM rules to the file-handling thread, physically restricting its filesystem view to *only* the designated workspace directory. The kernel blocks the path traversal with `EACCES`.
3.  **Arbitrary Command Execution / RCE (`--scenario rce_malicious` vs `--scenario rce_legit`)**
    *   **Goal:** The agent has a legitimate `executeDataAnalysis` tool that executes shell commands. However, prompt injection hijacks the `fetchWebpage` tool (or native code injection occurs) to spawn a malicious reverse shell.
    *   **Mitigation:** `mazewall` uses **Stacktrace-Enforced Execution**. Spawning process commands is supervised and authorized *only* if the JVM calling stack trace originates from the legitimate `executeDataAnalysis` tool. Attempts to spawn processes from other contexts (like `fetchWebpage`) are denied by the kernel.

---

## How to Run the PoC

### 1. SSRF Scenario (Network Isolation)
*   **Vulnerable (Unprotected):**
    ```bash
    ./gradlew :demos:agent-sandbox-demo:run --args="--scenario ssrf"
    ```
    *(Result: The agent attempts to connect and fetch the private metadata endpoint, failing only with a network timeout/connection error rather than a security denial).*

*   **Protected by Mazewall:**
    ```bash
    ./gradlew :demos:agent-sandbox-demo:run --args="--scenario ssrf --mazewall"
    ```
    *(Result: The kernel intercepts the `connect()` syscall, determines that the IP is private, blocks it instantly, and throws a security exception).*

### 2. Path Traversal Scenario (Filesystem Confinement)
*   **Vulnerable (Unprotected):**
    ```bash
    ./gradlew :demos:agent-sandbox-demo:run --args="--scenario path_traversal"
    ```
    *(Result: The agent successfully traverses directories and reads the file).*

*   **Protected by Mazewall:**
    ```bash
    ./gradlew :demos:agent-sandbox-demo:run --args="--scenario path_traversal --mazewall"
    ```
    *(Result: Landlock intercepts the file read at the kernel level, denying the traversal attempt).*

### 3. RCE Scenarios (Stacktrace-Enforced Execution)
*   **Legitimate Shell Execution (Allowed):**
    ```bash
    ./gradlew :demos:agent-sandbox-demo:run --args="--scenario rce_legit --mazewall"
    ```
    *(Result: The shell command is allowed because the execution stack trace originates from the authorized `executeDataAnalysis` tool).*

*   **Malicious Shell Execution (Blocked):**
    ```bash
    ./gradlew :demos:agent-sandbox-demo:run --args="--scenario rce_malicious --mazewall"
    ```
    *(Result: The command execution is blocked because the stack trace did not originate from `executeDataAnalysis`).*

---

## ⚠️ Highly Experimental: Stacktrace-Enforced Execution

The stacktrace scoping mechanism utilized in this demo is **highly experimental** and subject to significant API changes or complete removal in future versions.

### How It Works:
1. **The Safepoint Deadlock Challenge:** Spawning OS processes or Java threads triggers the Linux `clone` (or `clone3`) syscall. Supervising `CLONE` directly causes a deadlock: the thread is blocked in `Thread.start()` holding internal classloader/thread monitors, and checking the stacktrace requires triggering a JVM safepoint (which waits for the blocked thread to yield).
2. **The JVM launchMechanism Workaround:** To bypass this, we configure the JVM to launch processes using `vfork` or `fork` instead of `clone` (`-Djdk.lang.Process.launchMechanism=vfork`). We then allow thread-creation `CLONE` calls un-supervised.
3. **Parent Thread Context Capture:** When `vfork`/`fork` is called, the parent JVM thread is suspended before the child process is fully detached. The supervisor intercepts this call on the parent thread, queries `Thread.getStackTrace()`, and determines if the call originated from the authorized tool (`executeDataAnalysis`).
4. **Execution Restriction:** Since the child process detaches afterward, the actual `execve` occurs inside the child. Because child PIDs have no JVM stacktrace, we enforce validation on the parent thread's `vfork`/`fork` rather than on `execve` directly.

