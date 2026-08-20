# mazewall-profiler

**Automatically discover the exact syscall policy your workload needs — no guesswork, no deadlocks.**

Figuring out which system calls and filesystem paths your code uses by hand is error-prone and dangerous. Block the wrong coordination syscall (`futex`, `rt_sigreturn`) and the entire JVM deadlocks at the next GC cycle. The profiler eliminates this problem by observing your workload during a test run and generating the exact `Policy` DSL you need.

> **Dev/test only.** Do not include the `:profiler` module as a production runtime dependency.

---

## Quick Start

```kotlin
import io.mazewall.profiler.Profiler

// 1. Wrap your workload in a profile block during a test
val result = Profiler.profile {
    myXmlParser.parse(untrustedInput)
}

// 2. Print the generated policy DSL
// Note: toDsl() throws if exec/connect destinations were observed but cannot be enforced.
// Pass allowIncomplete=true if you cannot enforce all destinations.
println(result.behavior.toDsl(allowIncomplete = true))
```

**Output:**
```kotlin
Policy.builder()
    .base(Policy.NO_NETWORK)
    .allowFsRead("/app/schemas")
    .allowFsRead("/app/config.xml")
    .allowJvmClasspath()
    .build()
```

**3. Paste it into your application:**
```kotlin
val safePool = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    result.behavior.toPolicy()   // or paste the DSL above
)
```

That's it. The kernel now enforces exactly what the profiler observed — nothing more.

---

## The Problem It Solves

A `Policy` is a BPF program loaded into the Linux kernel. Writing one by hand means:
- Knowing the exact syscall numbers for your CPU architecture
- Anticipating every path your library touches during lazy classloading
- Knowing which JVM coordination calls (`futex`, `sched_yield`, `madvise`) must never be blocked
- Getting it wrong → JVM deadlock, no stack trace, no recovery

The profiler handles all of this by observing the actual execution.

---

## Profiling Tiers

Three profiling strategies are available depending on your environment and what you need to discover:

| Tier | API | Best For | Privilege |
|------|-----|----------|-----------|
| **S (Recommended)** | `MazewallProfiler` / `Profiler.profile { }` | Standard synchronous workloads, accurate syscall + path capture | Unprivileged |
| **H (Hybrid)** | `ProfileStrategy.HYBRID_NO_URING` | Disable `io_uring` while profiling; allow `io_uring_*` at runtime and let Landlock bind paths | Unprivileged |
| **P (strace, internal)** | Not a session API | JVM-floor lab dump / USER_NOTIF-unavailable environments | Parent-child `ptrace` |
| **eBPF (recorded)** | `ProfileStrategy.EBPF` + `ebpfEventLog` | Compile a rootful sidecar log (`kind=uring ...`) | Capture needs host `CAP_BPF`; compile does not |
| **eBPF (live)** | `ProfileStrategy.EBPF` without a log | Not implemented — fails closed | Host `CAP_BPF` in the **init** user ns |

The operator API is `MazewallProfiler.open().use { it.profile { workload() } }`. There is no `profile(Class)` / `TraceableWorkload` contract.

`IterativeProfiler` is deprecated (not a tracer). `StraceProfiler` is deprecated. Descendant strace is an internal floor probe (`DescendantStrace`), not how you profile application code. `AUTO` never selects STRACE.

`result.toPolicy()` refuses incomplete coverage (for example `io_uring` syscalls with no destinations). Pass `allowIncomplete = true` only if you will not treat the policy as a complete contract.

### Tier S — `USER_NOTIF` Daemon (Recommended)

The default. An out-of-process daemon intercepts every syscall on the profiled thread via the kernel's `SECCOMP_USER_NOTIF` interface. It captures both the syscall number and the resolved filesystem path (via `/proc/<pid>/fd/`), then releases the thread and lets it continue.

**Limitations:**
- `io_uring` operations bypass syscall interception (see [IO_URING_PROFILING.md](IO_URING_PROFILING.md) for solutions)
- Requires `ptrace_scope ≤ 1` or a container with `SYS_PTRACE` if cross-process path resolution is needed

### Tier A — Iterative Landlock Profiler

Runs the workload under a progressively tightening Landlock policy. When a path access is denied, the path is whitelisted and the workload retries. Converges to the minimal filesystem ruleset.

> [!CAUTION]
> Because the workload restarts on each violation, any side effects (DB writes, outbound messages) will execute multiple times. Use idempotent workloads or mock external systems.

```kotlin
val compiledPolicy = IterativeProfiler.profile(Policy.builder().build()) {
    targetWorkload()
}
println(compiledPolicy.allowedFsReadPaths)
```

### Tier P — Descendant `strace` Profiler

Wraps a subprocess under `strace -f` and parses the syscall log stream asynchronously. Useful for tracing legacy workloads or child JVM processes without modifying their code.

---

## SBoB Output

The profiler's final output is a `BillOfBehavior` — a structured behavioral contract that lists observed syscalls, filesystem paths, and network endpoints. This is the JVM-native contribution to the [SBoB (Software Bill of Behavior)](../docs/presentation/article1-threat-model.md) concept.

```kotlin
val behavior: BillOfBehavior = result.behavior

behavior.syscalls        // Set<Syscall>  — every syscall observed on the profiled thread
behavior.opens           // Set<String>   — every filesystem path opened
behavior.fsWritePaths    // Set<String>   — every path written to
behavior.connects         // Set<NetworkEndpoint> — observed connect() destinations
behavior.ioUringOps       // Set<String>  — io_uring opcodes (eBPF collector only)

behavior.toPolicy()      // → Policy (ready to pass to ContainedExecutors.wrap)
behavior.toDsl()         // → String (Kotlin DSL to paste into your codebase)
behavior.toJson()        // → String (machine-readable SBoB JSON)
behavior.toStackTracesJson() // → String (JSON mapping stack traces to events)
```

---

## Technical Architecture

For a detailed class hierarchy and structural relationship map, see the [Profiler Technical Design documentation](../docs/internals/designs/profiler/profiler-design.md).

- **`Profiler` / `ProfilerDaemon`**: Implements the out-of-process `USER_NOTIF` engine. The daemon receives the seccomp listener FD via UNIX socket `SCM_RIGHTS` passing, intercepts trapped syscalls, resolves paths via `process_vm_readv`, and sends an ACK back to release the worker thread.
- **`ProfilerTraceListener`**: Bridge between the daemon and the JVM — receives `TraceEvent`s and correlates them with JVM stack traces via `ThreadRegistry`.
- **`MazewallProfiler`**: Owned session, strategy selection, coverage on the result. eBPF fails closed until a collector exists.
- **`IterativeProfiler`**: Deprecated deny-and-retry Landlock learning loop.
- **`DescendantStrace` / `StraceCollector`**: Internal child-JVM `strace -f` for floor dumps. Not operator API.
- **`BobCompiler` / `BillOfBehavior`**: Deduplicates raw high-frequency syscall streams and compiles the structured behavioral contract.

For the critical ACK loop architecture and deadlock prevention rules, see [designs/core/architectural-map.md](../docs/internals/designs/core/architectural-map.md).

---

## Source Tree

> Use `kotlin scripts/file_structure.main.kts <file>` to inspect any file's API surface before reading its full content.

```
profiler/src/main/kotlin/io/mazewall/profiler/
│
├── Profiler.kt                # ⭐ Primary public entry point: Profiler.profile { }
├── BillOfBehavior.kt          # Structured behavioral contract output (syscalls, paths, network)
├── BillOfBehaviorDto.kt       # JSON-serializable DTO for SBoB output
├── TraceableWorkload.kt       # Internal descendant-strace child contract (not operator API)
├── ProfilingResult.kt         # Result value wrapping BillOfBehavior + metadata
│
├── engine/
│   ├── ProfilerDaemon.kt         # Daemon entry point (separate JVM process)
│   ├── ProfilerDaemonEngine.kt   # Core USER_NOTIF ACK loop logic
│   ├── ProfilerSessionHandler.kt # Handles a single profiling session's lifecycle
│   ├── ProfilerTransport.kt      # UNIX socket + SCM_RIGHTS FD passing
│   ├── ProfilerInstaller.kt      # Installs the NOTIFY filter on the target thread
│   ├── HandshakeSession.kt       # Startup handshake protocol between JVM and daemon
│   ├── SyscallPathResolver.kt    # Resolves path args via process_vm_readv
│   ├── TraceEvent.kt             # Sealed hierarchy of syscall trace events
│   └── SyscallEvent.kt           # Raw syscall intercept data
│
├── compiler/
│   └── BobCompiler.kt         # Compiles raw TraceEvents into a BillOfBehavior
│
├── iterative/
│   └── IterativeProfiler.kt   # Landlock deny-and-retry learning loop (Tier A)
│
├── strace/
│   └── StraceProfiler.kt      # strace-based profiler (Tier P)
│
└── triage/
    └── (internal diagnostic tooling)
```



## Testing

```bash
# Unit tests (no kernel interaction)
./gradlew :profiler:test

# Full integration suite (requires Podman, Linux 5.0+)
./scripts/run_tests.sh :profiler:integrationTest
```
