# mazewall-enforcer

**Core runtime engine for thread-scoped and process-wide syscall sandboxing.**

This subproject implements unprivileged sandboxing using Linux **Seccomp-BPF** and **Landlock LSM** through the JDK **Foreign Function & Memory (FFM) API** (JDK 22+). It is designed to be a zero-dependency security layer for production JVM services.

> [!WARNING]
> **Security boundary: read this before deploying.**
> `ContainedExecutors.wrap()` (Tier 2) restricts syscalls on the *calling* thread. It stops data-plane attacks (SSRF, XXE, path traversal, fileless malware) on that thread. It does **not** isolate arbitrary Java code — an attacker who can execute Java logic (e.g., via deserialization RCE) can hop to an unrestricted thread pool using `CompletableFuture.runAsync(...)` or virtual threads.
>
> **Always install the Tier 1 process-wide baseline first:**
> ```kotlin
> ContainedExecutors.installOnProcess(Policy.NO_EXEC_HOTSPOT) // call once in main()
> ```
> See [designs/core/security-considerations.md](../docs/internals/designs/core/security-considerations.md) for the complete threat model.

---

## Technical Architecture

### Canonical policy construction

Java and Kotlin use the same fixed-scope mutable builders. Select the runtime and
list mode explicitly; every worker filesystem mutator retains the same builder,
including when its return value is ignored:

```kotlin
val worker = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
worker.base(ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
worker.base(ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT))
worker.allowFsRead("/app/input")
worker.allowFsWrite("/app/output")
val policy = worker.build()
```

`ProcessPolicyBuilder.denyList(runtime)` produces process-applicable policies and
does not expose filesystem mutators. `allowList(runtime)` on either builder starts
with default errno denial and the canonical JVM coordination/read floor; workload
permissions are then added explicitly. Runtime selection is a configuration choice,
not proof that every later customized policy is safe for that JVM.

Worker filesystem intent can also be assigned explicitly:

```kotlin
val denyAll = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
    .filesystem(FileSystemPolicy.DenyAll)
    .build()
```

`Unrestricted` installs no filesystem restriction; `DenyAll` installs one with no
grants; `AllowResources(readPaths, writePaths)` freezes the supplied sets and remains
active even when both sets are empty. Assigning `filesystem(...)` replaces earlier
configured filesystem grants. It never changes a previously built policy or an
installed kernel restriction. Already-open descriptors and mappings are not revoked.

`base(...)` selects/merges a template and can broaden configuration. Use
`restrictFurtherWith` or `PolicyDefinition.intersection` for restrictive composition.
The Java-only builder and `buildProcessWide()` scope switch have been removed.
Packaged Java/Kotlin examples and compile-time scope rejection fixtures live in
[`tests/consumers`](../tests/consumers/README.md).

For a detailed class hierarchy and structural relationship map, see the [Enforcer Module Architecture documentation](../docs/internals/designs/enforcer/enforcer-architecture.md).

- **FFM Native Bindings:** Uses the JDK Foreign Function & Memory API to map native Linux system calls directly from Kotlin, eliminating the need for JNI or dynamic C library dependencies.
- **BPF Program Compilation:** Generates deterministic BPF instruction arrays. The compiler uses an inverted linear scan to handle complex syscall policies within the 8-bit seccomp relative jump limitation.
- **Thread-Scoped Enforcement:** Applies filters to individual OS threads via `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, ...)`. Filters are inherited by child threads created after installation.
- **Filesystem & Network Isolation:** Integrates Landlock LSM to restrict filesystem paths and TCP ports. It automatically handles Landlock ABI negotiation and authorizes the JVM classpath for lazy classloading.
- **Atomic Synchronization (`TSYNC`):** Leverages the `SECCOMP_FILTER_FLAG_TSYNC` flag to synchronize filters across all existing threads in the process during initialization.

---

## Source Tree

> Use `kotlin scripts/file_structure.main.kts <file>` to inspect any file's API surface before reading its full content.

```
enforcer/src/main/kotlin/io/mazewall/
│
├── Policy.kt                  # Public API: DSL for declaring syscall allow/deny rules
├── PolicyBuilder.kt           # Fluent builder for Policy objects
├── PolicyDefinition.kt        # Sealed type for compiled vs uncompiled policies
├── PolicyPresets.kt           # Built-in presets: NO_EXEC, NO_NETWORK, PURE_COMPUTE
├── BpfFilter.kt               # BPF bytecode compiler: Policy → sock_filter[]
├── LinuxNative.kt             # All FFM downcalls to Linux kernel (prctl, seccomp, landlock)
├── NativeEngine.kt            # Trait interface enabling MockNativeEngine for tests
├── Platform.kt                # Platform detection, fallback behavior, feature matrix
├── CompiledSandbox.kt         # Value type wrapping a compiled filter + definition
├── DiagnosticsState.kt        # Diagnostic state for debugging installation failures
├── KernelFeatureMatrix.kt     # Runtime probe of available kernel features
│
├── core/
│   ├── Syscall.kt             # Enum of all supported syscalls (x86_64 + aarch64 numbers)
│   ├── Arch.kt                # Architecture detection and syscall number mapping
│   ├── SeccompAction.kt       # BPF actions: ALLOW, KILL, ERRNO, TRACE, NOTIFY
│   └── PrctlCommand.kt        # prctl command constants
│
├── enforcer/
│   ├── ContainedExecutors.kt  # ⭐ Primary public entry point (installOnProcess / installOnCurrentThread)
│   ├── ThreadStateRegistry.kt # ThreadLocal state tracking seccomp/Landlock install status
│   ├── SandboxDispatcher.kt   # Dispatches work into contained thread contexts
│   ├── FilterInstallationPlanner.kt  # Decides which filters/Landlock rules to install
│   └── ContainmentViolationDetector.kt # Detects and reports policy violations
│
├── seccomp/
│   └── PureJavaBpfEngine.kt   # Low-level Seccomp engine: builds/installs BPF programs
│
├── landlock/
│   └── Landlock.kt            # Landlock ruleset creation and path restriction logic
│
└── ffi/
    ├── memory/                # FFM memory utilities (nativeScope, struct layouts)
    └── NativeConstants.kt     # Raw kernel constants (SECCOMP_SET_MODE_FILTER, etc.)
```


## Installation

Add `mazewall-enforcer` to your project via **JitPack** or **GitHub Packages**.

### Gradle (Kotlin)

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.Pilleo.jseccomp:enforcer:main-SNAPSHOT")
}
```

---

## Core API Usage

`ManagedRuntime` owns prepared synchronous worker generations. `ContainedExecutors`
provides manual installation and adapters for caller-owned `ExecutorService` instances.
Wrapped tasks check `InstallationReceipt.requireInstalled()` before entering their
body. An `installed=false` bypass result or missing requested Landlock restriction
rejects the task, even when manual-install fallback is configured. Receipt closure
still releases supervisor registration; it does not undo applied restrictions.
Each installation attempt selects its fallback once before probing or applying
native stages. `receipt.fallback` records that choice. `receipt.progress` records
new Seccomp/Landlock/CET-lock stages applied by the attempt, and `receipt.failure` preserves
the installation error or unsupported-feature bypass reason. `requireInstalled()`
rethrows a recorded failure with its original identity and cause chain.
An installation failure or bypass terminally quarantines its thread/process
installation scope. Later contained calls reject before another installation
attempt, including calls through another wrapper or an otherwise satisfied policy
cache. Ordinary business-task failures do not quarantine successful installation.
Applied restrictions remain in force; the caller continues to own executor shutdown.

The plain Seccomp native pipeline now uses one selected backend for BPF packing,
privilege locking, raw installation, compatibility fallback, retries, and mandatory
mode verification. CET and plain Seccomp receive the installation attempt's same
captured backend. Global backend replacement during those stages cannot redirect
the in-flight calls. A reusable installation environment also retains the provider,
capability selection, fallback and self-verification gate. Supervised task registration
uses selected caller authority, while the daemon retains its captured lifecycle owner.

Landlock sessions now bind one native backend and typed capability snapshot to their
ruleset lifetime. Path opening, rule insertion, restriction, descriptor retirement,
and applied-ABI diagnostics use that selection. Permission-rejected or failed
capability probes remain rejected outcomes rather than unsupported-ABI bypass.

`lockIntelCet()` verifies and locks an **already-enabled SHSTK** on the installing
platform thread. Receipts require `CetOutcome.VERIFIED_CURRENT_THREAD` for that
request. Late FFM enablement, permission-error-as-lock-proof, and process-wide CET
claims are rejected. See the [current CET contract](../docs/internals/intel-cet-jvm-sandbox-integration.md)
for runtime prerequisites, retained proof, and native acceptance boundaries.

### Canonical policy documents

`CanonicalPolicyArtifact.worker(runtime, policy)` and `.process(runtime, policy)`
encode versioned immutable policy documents through `toJson()`. `fromJson()` returns
the fixed worker/process artifact type with its typed `policy`. Active `DenyAll`
remains active after a round trip; unrestricted state, exact resource grants,
parameterized actions and argument flags remain distinct. Unknown versions/fields,
duplicate fields/rules/resources and contradictory filesystem state are rejected.
The format uses canonical JSON token spelling with optional formatting whitespace
and absolute resource paths. These documents encode policy, not review approval.

### Reviewed operation documents

`OperationPolicyDocument` associates a canonical policy with a fixed `OperationBinding`,
reported missing coverage and specifically reasoned review exceptions. Binding includes
operation ID, adapter identity, implementation digest and configured resource generation.
`ReviewedOperationArtifact.load(document, trustedReview, expectedBinding)` requires an
external operator-controlled digest approval and an exact configured identity match.
Approval fields inside JSON are rejected. Missing coverage cannot be authorized by a
global incomplete override. The digest identifies bytes; trusted reviewer configuration
and storage access are separate responsibilities.

`runtime.prepare(reviewedArtifact, limits)` accepts an approved worker policy, verifies
runtime compatibility, retains the binding and uses the same required installer. Actual
class/method/resource resolution is verified by the owning adapter/resource binder;
matching document metadata alone is not proof that a filesystem object stayed unchanged.

### Owned synchronous workers

Create the runtime during trusted platform-thread application bootstrap. The process
baseline is explicit and permanent; its executable-mapping floor must match the
declared runtime. Prepared workers use one captured environment and require successful
installation before readiness. Worker count and logical admission are finite.

```kotlin
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import java.time.Duration
import java.util.concurrent.Callable

ManagedRuntime.open(
    RuntimeProfile.HOTSPOT_JIT,
    Policy.NO_EXEC_HOTSPOT,
    Duration.ofSeconds(10), // required preparation deadline
    Duration.ofSeconds(10), // bounded close deadline
).use { runtime ->
    val domain = runtime.prepare(
        WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(),
        DomainLimits(workers = 4, maxAdmissions = 64),
    )
    val value = Mazewall.callContained(runtime, domain, Callable { 42 })
}
```

These capacities are example configuration, not benchmark-derived defaults. Admission
includes running and queued invocations. Overload rejects; protected bodies never run
on a caller or unrelated executor. Caller future mutation does not own execution.
`requestCancellation()` is cooperative: running work retains its lease until physical
end. `drain(timeout)` stops admission and distinguishes complete retirement from live
work or cleanup failure; bounded `close()` throws `IncompleteDrainException` when it
cannot finish. Failed generations cannot be hidden by replacement capacity.

The current contract is materialized synchronous bodies. Completion observers, lazy
results and unrelated helper executors are outside that contract. Nested asynchronous
dispatch is rejected. Canonical synchronous calls nest inline only in the exact
active domain; different or unknown scopes are rejected before entry. Library-observed
nested containment/transition failure remains unsuccessful even if caught by business
code. `Mazewall.currentInvocationEvidence()` exposes read-only current evidence and
does not claim detection of unobserved/swallowed kernel errors. Thread-scoped workers
share JVM memory/descriptors and are not a complete ACE boundary. A process baseline
that forbids child launch also prevents later Portal or supervisor startup; establish
required child services before that baseline or use an explicitly compatible baseline.

### 1. Wrapping an Executor

```kotlin
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder

val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
    .base(Policy.NO_EXEC)
    .allowFsRead("/var/tmp/incoming")
    .allowJvmClasspath()
    .build()

val sandboxed = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    policy
)
```

### 2. Process-Wide Baseline

```kotlin
// Apply a NO_EXEC baseline to the entire JVM process early in main()
ContainedExecutors.installOnProcess(Policy.NO_EXEC_HOTSPOT)
```

---

## Critical Safety Invariants

To prevent JVM deadlocks and stability issues, `:enforcer` implements several internal safeguards:

- **Loom Carrier Protection:** Seccomp filters bind to OS threads. `:enforcer` detects if installation is attempted within a Virtual Thread (Loom) and throws `IllegalStateException` to prevent poisoning shared carrier threads.
- **Coordination Whitelisting:** JVM coordination syscalls (`futex`, `sched_yield`, `rt_sigreturn`, `madvise`, `gettid`, etc.) are always whitelisted. Blocking these syscalls would deadlock the JVM during GC safepoints.
- **Initialization Ordering:** Landlock rulesets are always initialized before Seccomp filters, as active Seccomp rules would otherwise block the Landlock configuration syscalls.

---

## Testing

Tests require a Linux host (6.2+) or the provided dev container environment.

```bash
# Unit tests (no kernel interaction)
./gradlew :enforcer:test

# Full check including integration tests (requires Podman)
./scripts/run_tests.sh :enforcer:integrationTest
```
