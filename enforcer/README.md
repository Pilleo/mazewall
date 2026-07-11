# mazewall-enforcer

**Core runtime engine for thread-scoped and process-wide syscall sandboxing.**

This subproject implements unprivileged sandboxing using Linux **Seccomp-BPF** and **Landlock LSM** through the JDK **Foreign Function & Memory (FFM) API** (JDK 22+). It is designed to be a zero-dependency security layer for production JVM services.

> [!WARNING]
> **Security boundary: read this before deploying.**
> `ContainedExecutors.wrap()` (Tier 2) restricts syscalls on the *calling* thread. It stops data-plane attacks (SSRF, XXE, path traversal, fileless malware) on that thread. It does **not** isolate arbitrary Java code — an attacker who can execute Java logic (e.g., via deserialization RCE) can hop to an unrestricted thread pool using `CompletableFuture.runAsync(...)` or virtual threads.
>
> **Always install the Tier 1 process-wide baseline first:**
> ```kotlin
> ContainedExecutors.installOnProcess(Policy.NO_EXEC) // call once in main()
> ```
> See [architecture/security-considerations.md](../docs/internals/architecture/security-considerations.md) for the complete threat model.

---

## Technical Architecture

For a detailed class hierarchy and structural relationship map, see the [Enforcer Module Architecture documentation](../docs/internals/architecture/enforcer-architecture.md).

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

The primary entry point is `ContainedExecutors`, which provides wrappers for standard `ExecutorService` instances.

### 1. Wrapping an Executor

```kotlin
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.Policy

val policy = Policy.builder()
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
ContainedExecutors.installOnProcess(Policy.NO_EXEC)
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
