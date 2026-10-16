# AI Agent Sandboxing & Compartmentalization PoC

This module demonstrates bounded, owned worker compartments for first-party JVM tools handling untrusted agent input.

Seccomp/Landlock restrict work on dedicated platform workers. Experimental stacktrace supervision adds selected syscall authorization; this is not heap isolation or a complete arbitrary-code-execution boundary. Independent helper threads and already-open resources remain outside the tool-body contract.

---

## Demonstrated Threat Scenarios

This PoC runs a LangChain4j agent with three tools, demonstrating three common security exploits triggered via prompt injection:

Each run declares whether the selected operation must be allowed or denied and exits non-zero when reality differs. Only kernel-facing `OBSERVED_POLICY_DENIAL` evidence is reported as containment; permission-shaped exceptions without authenticated provenance are inconclusive infrastructure outcomes. Fixtures live under one fresh owner-only temporary root and are deleted without following symlinks after the scenario.

1.  **SSRF / Network Egress Bypass (`--scenario ssrf`)**
    *   **Goal:** The agent has a `fetchWebpage` tool to scrape docs. Prompt injection forces it to hit the internal AWS metadata service (`http://169.254.169.254`).
    *   **Mitigation:** `mazewall` applies a Seccomp-BPF filter to the web fetcher thread. Before installing the process baseline, the demo starts the external supervisor. One pure fail-closed classifier permits global unicast destinations and rejects metadata, RFC1918, shared, loopback, link-local, multicast, documentation, benchmarking, reserved, IPv4-mapped private, malformed, and unsupported socket addresses.
2.  **Path Traversal (`--scenario path_traversal`)**
    *   **Goal:** The agent has an `analyzeUserFile` tool to read files in the `agent-workspace` directory. Prompt injection forces it to attempt a path traversal to a file outside the workspace (e.g. `../passwd`).
    *   **Mitigation:** `mazewall` applies Landlock LSM rules to the file-handling thread, physically restricting its filesystem view to *only* the designated workspace directory. The kernel blocks the path traversal with `EACCES`.
3.  **Arbitrary Command Execution / RCE (`--scenario rce_malicious` vs `--scenario rce_legit`)**
    *   **Goal:** The agent has a legitimate `executeDataAnalysis` tool that executes shell commands. However, prompt injection hijacks the `fetchWebpage` tool (or native code injection occurs) to spawn a malicious reverse shell.
    *   **Mitigation:** `mazewall` uses **Stacktrace-Enforced Execution**. Spawning process commands is supervised and authorized only when the JVM stack contains the exact `io.mazewall.demo.agent.AgentTools.executeAuthorizedAnalysis` frame, a named body called by the legitimate `executeDataAnalysis` tool. Class or method substrings do not confer authority. Attempts from other contexts (like `fetchWebpage`) are denied by the kernel.

---

## How to Run the PoC

### 1. SSRF Scenario (Network Isolation)
*   **Vulnerable (Unprotected):**
    ```bash
    ./gradlew :demos:agent-sandbox-demo:run --args="--scenario ssrf"
    ```
    *(Result: This unprotected run depends on host networking. A timeout is reported as infrastructure failure, not as protection.)*

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

### 4. Benign Filesystem Workload (Allowed)

```bash
./gradlew :demos:agent-sandbox-demo:run --args="--scenario file_legit --mazewall"
```

*(Result: The agent reads the file inside its owned workspace, demonstrating that legitimate file work remains available.)*

---

## ⚠️ Highly Experimental: Stacktrace-Enforced Execution

The stacktrace scoping mechanism utilized in this demo is **highly experimental** and subject to significant API changes or complete removal in future versions.

### How It Works:
1. **The Safepoint Deadlock Challenge:** Spawning OS processes or Java threads triggers the Linux `clone` (or `clone3`) syscall. Supervising `CLONE` directly causes a deadlock: the thread is blocked in `Thread.start()` holding internal classloader/thread monitors, and checking the stacktrace requires triggering a JVM safepoint (which waits for the blocked thread to yield).
2. **The JVM launchMechanism Workaround:** Configure VFORK **before ProcessImpl initializes**, using the existing application argument `-Djdk.lang.Process.launchMechanism=vfork`. A JDK25 FORK-mode malicious launch bypassed the exact VFORK/FORK handlers; glibc fork can use the allowed CLONE route, though that fixture is not a syscall trace. The protected bootstrap rejects explicitly incompatible launch settings. Changing a system property after ProcessImpl initialization cannot change its mechanism. The handlers remain exactly CONNECT + VFORK/FORK for web, VFORK/FORK for analysis; CLONE/CLONE3/EXECVE/EXECVEAT remain allowed as before.
3. **Parent Thread Context Capture:** When `vfork`/`fork` is called, the parent JVM thread is suspended before the child process is fully detached. The supervisor intercepts this call on the parent thread, queries `Thread.getStackTrace()`, and determines if the call originated from the authorized tool (`executeDataAnalysis`).
4. **Execution Restriction:** Since the child process detaches afterward, the actual `execve` occurs inside the child. Because child PIDs have no JVM stacktrace, we enforce validation on the parent thread's `vfork`/`fork` rather than on `execve` directly.

## Owned bootstrap and shutdown

The protected scenario explicitly starts `Supervision.prepareSupervisor()` before
`ManagedRuntime.open(HOTSPOT_JIT, processBaseline, ...)`. The baseline still blocks
PTRACE, MOUNT, UNSHARE and CHROOT, permits executable mappings for JIT, and intentionally
permits launching approved analysis processes. Each of the three domains has one
preinstalled platform worker and one admission. Custom handler membership is captured
once during owned preparation; no default handler substitution occurs.

HTTP connection/read is synchronous on the web worker, with one overall two-second
connection/response-header deadline and automatic redirects disabled. Normal, redirect
and error response bodies are returned, honoring supported Content-Type charsets with
UTF-8 fallback. The response timer ends at headers; subsequent buffered body reads do
not acquire a new two-second inactivity timeout. A per-request platform observer only
expires header acquisition by disconnecting; it never connects or reads HTTP on behalf
of the worker, and is joined before physical body end. A tool result timeout requests cancellation
but does not prove physical end. Tool shutdown retains outstanding invocation handles,
requests cancellation, and drains actual workers/receipt cleanup under one five-second
budget. Incomplete drain is a failure. The existing process runner owns child/tree/output
cleanup; closing never removes permanent filters from live threads. Unprotected mode
uses explicitly separate ordinary JDK executors and reports no protection.

A narrow closing/registration monitor rejects new tool admission and establishes the
cancellation snapshot. Core submission/resource validation, body execution, result
waiting, cancellation and drain remain outside it. A handle published after the closing
snapshot cancels itself; handles stay retained through actual `executionEnd`.

Fresh-JVM checks cover allowed public HTTP (`1.1.1.1:80`, requiring reachable host
egress), denied private/metadata connections, approved analysis, denied web-stack spawn,
workspace read/traversal, incompatible launch rejection, and actual child/worker death
after cancellation. A separate acquired-loopback native fixture proves immutable custom
handler capture and exact-vs-lookalike stack decisions without external network access.
Acquired local HTTP fixtures additionally verify status/error bodies, charset fallback,
trickled-header expiry and delayed-body success in comparison and actual custom-supervised
worker modes. A held-publication barrier proves close cannot miss an admitted analysis
handle. These controls require no external networking.
Infrastructure timeouts and generic permission failures are not authenticated denials;
filesystem proof uses a host read control and the actual restricted-worker
`AccessDeniedException`, rather than relabeling every IOException.
