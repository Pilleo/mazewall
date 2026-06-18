# Guidelines for AI Coding Agents in mazewall-profiler

Welcome, AI Agent. This is the **`:profiler`** subproject of **mazewall**. It contains the developer-only diagnostic, tracing, and auto-rule compilation systems.

Operating these tools requires deep interaction with Linux kernel notification loops, out-of-process process tracing, and virtual file structures. You MUST adhere to these strict limits, rules, and guidelines when modifying files in this subproject.

---

## 🚧 Core Invariants & Boundaries

### 1. Profiler ACK Deadlock Prevention
> [!CAUTION]
> Every code path in `ProfilerSessionHandler.kt` that receives a `USER_NOTIF` seccomp event **must** either send `SECCOMP_USER_NOTIF_FLAG_CONTINUE` (via the `0x41` ACK byte loop) or `SECCOMP_USER_NOTIF_FLAG_KILL_THREAD`. 
> 
> **The 0xAC (PROTOCOL_ACK_BYTE) Handshake:** Before the daemon sends a `CONTINUE` response to the kernel, it must wait for the mandatory `0xAC` acknowledgment byte from the JVM Trace Listener. If you miss sending or receiving this ACK, the tracee worker OS thread will deadlock permanently.

### 2. Reactor Loop & Session Handling
The `ProfilerDaemonEngine` uses a non-blocking reactor loop that delegates session-specific logic to `ProfilerSessionHandler`.
- **Statelessness:** Keep the engine stateless; all session-specific file descriptors and state must live inside the `ProfilerSessionHandler` instance.
- **Error Propagation:** If a session fails (e.g., due to a broken UNIX socket), ensure the handler closes the seccomp listener FD (`notifFd`) to prevent kernel resource leaks.

### 3. Yama `ptrace_scope` & Yama LSM
- Under default container configurations, process memory reads (`/proc/<pid>/mem`) or attaches are blocked by Yama LSM (`ptrace_scope = 1`).
- To allow the out-of-process `ProfilerDaemon` to inspect JVM tracee memory segments unprivileged, the tracee JVM must explicitly authorize the daemon PID via `prctl(PR_SET_PTRACER, daemonPid)`. Ensure this call is preserved during tracing startup.

### 4. Descriptor Passing via UNIX Domain Sockets
- Transferring the seccomp listener file descriptor to the daemon requires standard UNIX domain sockets and `SCM_RIGHTS` ancillary control messages. Ensure the FFM memory structure representing the `msghdr` and `cmsghdr` payload blocks remains byte-aligned.

### 5. Strace Descendant Tracing Boundaries
- The `StraceProfiler` spawns JVM workloads directly under `strace -f`.

---

## 🔄 Verification & Testing
For test script commands and Podman orchestration parameters, refer to the parent registry in [Root AGENTS.md](file:///home/leanid/Documents/code/java/jseccomp/AGENTS.md#5-testing-and-verification-guidelines).
- **Lints & Style:** Spotbugs and Detekt analyses must pass completely. If detekt complains about function counts or sizes in complex daemon handling states, use specific annotations like `@Suppress("TooManyFunctions")`.
- **Coverage Rules:**
  - `Profiler*` instruction coverage must remain $\ge 60\%$.
- **Test environments:** Guard all tests using the `@EnabledIfLinuxAndSupported` annotation copy to ensure they run only on compatible Linux host systems.
- **Min Kernel Version:** `ProfilerIntegrationTest` requires a Linux kernel $\ge 5.0$ support (the introduction of Seccomp User Notifications).
