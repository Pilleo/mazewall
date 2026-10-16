# mazewall-profiler

**Observe workloads and generate coverage-qualified policy candidates.**

Figuring out which system calls and filesystem paths your code uses by hand is error-prone. The profiler observes test workloads and generates candidates; collection completeness is not future-path coverage, trusted review approval or proof of enforcement. The canonical policy compiler preserves the JVM coordination floor.

> **Dev/test only.** Do not include the `:profiler` module as a production runtime dependency.

For event-correlated Java evidence on the pinned interpreter JVM, see the explicit
[HotSpot agentless backend](HOTSPOT_AGENTLESS.md). Its coverage is always incomplete
and its results cannot generate an enforceable policy.

---

## Quick Start

<!-- consumer: profiler-collect -->
```kotlin
import io.mazewall.profiler.api.MazewallProfiler

fun collectProfile(workload: () -> Unit) = MazewallProfiler.open().use { session ->
    session.profile { workload() }
}
```

Profile in a disposable test JVM before an application baseline that forbids the
profiler's child services. The synchronous `profile()` call finalizes its internal
collection session before returning the result. Closing the outer `MazewallProfiler`
facade prevents further use; it is not the collection-drain boundary. Neither
operation removes installed filters. Inspect `result.coverage` and its scope/warnings first.

Select the runtime on the profiled component **before** restrictive composition.
For a HotSpot JVM, normalize the template's executable-mapping floor explicitly:

<!-- consumer: profiler-convert -->
```kotlin
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ProcessPolicies
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.restrictFurtherWith
import io.mazewall.profiler.api.ProfilingResult

fun convertProfile(result: ProfilingResult<*>) = run {
    val profileBase = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
        .base(Policy.PURE_COMPUTE_UNSAFE)
        .forRuntime(RuntimeProfile.HOTSPOT_JIT)
        .build()
    println(result.toProfileDocumentJson()) // persist behavior AND measured coverage
    println(result.toDsl(basePolicyName = "profileBase", base = profileBase))
    val candidate = result.toPolicy(base = profileBase) // refuses incomplete coverage
    candidate.restrictFurtherWith(
        ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT),
    )
}
```

`profileBase` in the emitted DSL refers to the base constructed above. Keep that
declaration with the generated code. For Native Image, select `NATIVE_IMAGE` for
both the base and restrictive component. An intersection cannot restore mapping
permission already denied by another component. Unrestricted JIT helper threads
do not prove mapping denial safe on every HotSpot worker.

After reviewing and validating the candidate against representative workloads,
prepare it in an explicitly bootstrapped owned runtime:

<!-- consumer: profiler-prepare -->
```kotlin
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime

fun prepareCandidate(
    runtime: ManagedRuntime,
    reviewedPolicy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>,
) = runtime.prepare(
    reviewedPolicy,
    DomainLimits(workers = 4, maxAdmissions = 64),
)
```

Use `Mazewall.callContained(runtime, domain, Callable { ... })` for materialized
synchronous work. The [owned runtime recipe](../enforcer/README.md#owned-synchronous-workers)
defines bootstrap, admission, cancellation, physical end and bounded drain. Required
installation precedes body entry; review approval is not conferred by conversion.

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

`result.toPolicy()` and `result.toDsl()` refuse incomplete coverage (for example
`io_uring` syscalls with no destinations). There is no public `allowIncomplete`
override. Keep incomplete results as diagnostics; do not synthesize complete
coverage or use an internal test seam to authorize enforcement.

### Tier S — `USER_NOTIF` Daemon (Recommended)

The default. An out-of-process daemon intercepts every syscall on the profiled thread via the kernel's `SECCOMP_USER_NOTIF` interface. It captures both the syscall number and the resolved filesystem path (via `/proc/<pid>/fd/`), then releases the thread and lets it continue.

**Limitations:**
- `io_uring` operations bypass syscall interception (see [IO_URING_PROFILING.md](IO_URING_PROFILING.md) for solutions)
- Requires `ptrace_scope ≤ 1` or a container with `SYS_PTRACE` if cross-process path resolution is needed

### Tier A — Iterative Landlock Profiler

Runs the workload under a progressively tightening Landlock policy. When a path access is denied, the path is whitelisted and the workload retries. Converges to the minimal filesystem ruleset.

> [!CAUTION]
> Because the workload restarts on each violation, any side effects (DB writes, outbound messages) will execute multiple times. Use idempotent workloads or mock external systems.

The iterative implementation is internal test/lab code. Application consumers use
the owned `MazewallProfiler.open().use { session -> session.profile { workload() } }`
entry point described above.

### Tier P — Descendant `strace` Profiler

Wraps a subprocess under `strace -f` and parses the syscall log stream asynchronously. Useful for tracing legacy workloads or child JVM processes without modifying their code.

---

## SBoB Output

The profiler's final output is a `BillOfBehavior` — a structured behavioral contract that lists observed syscalls, filesystem paths, and network endpoints. This is the JVM-native contribution to the [SBoB (Software Bill of Behavior)](../docs/presentation/article1-threat-model.md) concept.

<!-- consumer: profiler-exports -->
```kotlin
import io.mazewall.profiler.api.ProfilingResult

fun inspectProfile(result: ProfilingResult<*>) {
    val behavior = result.behavior

    behavior.syscalls        // Set<Syscall> — recorded syscall kinds
    behavior.opens           // Set<String> — resolved observed open paths
    behavior.fsWritePaths    // Set<String> — observed write paths
    behavior.connects        // Set<NetworkEndpoint> — observed connect destinations
    behavior.ioUringOps      // Set<String> — io_uring opcodes (eBPF collector only)

    behavior.toJson()        // String — SBoB-only JSON
    behavior.toStackTracesJson() // String — stacks associated with events
    result.toProfileDocumentJson() // versioned behavior + measured coverage
}
```

`behavior.toJson()` is deliberately an SBoB-only export. It cannot authorize portal
`--complete-profile`, because it carries no coverage evidence. For that mode, persist the
versioned measured artifact instead:

Persist `result.toProfileDocumentJson()` as shown above.

`ProfileDocument.fromJson(profileDocumentJson)` restores both the SBoB and the complete
`ProfilingCoverage`. Portal workers reject a missing, legacy, plain-SBoB, or incomplete
document. The document is structured operator evidence, not a cryptographic signature.
Do not export `result.behavior.toPolicy()`/`toDsl()` without measured coverage:
their default absent evidence is incomplete. Result-level conversion retains the
collector's evidence but cannot prove unexecuted future paths, independent helpers,
or late asynchronous work. Generic permission errors are inferred diagnostics;
only correlated observed events identify the intercepted operation.

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

> Use `./scripts/adkw slice <file-or-class> --json` for a structural outline of an unfamiliar source file.

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
