# Getting Started with mazewall

This guide shows how to evaluate kernel-enforced restrictions for selected JVM workers. Read the [threat model](docs/internals/designs/core/security-considerations.md) before applying a policy to an application.

---

## Prerequisites

| Requirement | Minimum | Recommended |
|---|---|---|
| **JDK** | 22 | 22+ |
| **Linux kernel** | 3.17 (Seccomp basic) | **6.2+** (full feature set) |
| **Landlock filesystem isolation** | kernel 5.13 | 6.7+ (adds TCP `bind`/`connect` port restrictions; not general network control) |

> [!NOTE]
> mazewall detects unavailable host features at runtime. The effective behavior depends on the configured fallback policy, kernel capabilities, and outer container restrictions. A kernel 6.2+ host (or the provided dev container) provides the broadest supported feature set.

**No native C libraries or kernel modules are required. Root is not required on a compatible Linux host, but an outer container profile or runtime policy can prevent filter installation.**

---

## Installation

### Option 1: JitPack (Easiest)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.Pilleo.jseccomp:enforcer:main-SNAPSHOT")
}
```

### Option 2: GitHub Packages (Verified Builds)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Pilleo/mazewall")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("GITHUB_ACTOR")).get()
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("GITHUB_TOKEN")).get()
            }
        }
    }
}

// build.gradle.kts
dependencies {
    implementation("io.mazewall:enforcer:0.1.0-SNAPSHOT")
}
```

---

## Before You Evaluate

- Linux only. Record the exact kernel, Landlock ABI, JDK, container runtime, and outer Seccomp profile used for testing.
- Install the process-wide baseline before framework or application thread pools start. It is irreversible for the life of the process.
- Use a dedicated **platform-thread** executor for contained work. Do not wrap `ForkJoinPool.commonPool()`, framework-managed pools, or an executor reused for unrelated work: kernel restrictions remain on its workers.
- Thread-scoped restrictions protect trusted code handling untrusted data. They do not isolate arbitrary hostile Java code, existing file descriptors, shared JVM memory, or unrestricted sibling threads.
- Validate every policy against representative normal, error, lazy-loading, and dependency-upgrade workloads. Profiler output is a candidate policy, not proof of future behavior.

## Quick Start: Your First Evaluation

### Step 1 — Install a process-wide exec baseline at startup

This prevents the JVM process from creating a child process after installation, including from an uncontained thread. Install it only after confirming that your application does not need later process creation or dynamic native-agent loading.

```kotlin
// Call once, early in main() / Application.run(), before application thread pools exist.
ContainedExecutors.installOnProcess(
    ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
)
```

> [!IMPORTANT]
> Once installed, the filter applies to current and future threads in the process and cannot be removed.

### Step 2 — Restrict a dedicated worker pool

```kotlin
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.Policy
import io.mazewall.ProcessPolicies
import io.mazewall.RuntimeProfile
import java.util.concurrent.Executors

val sandboxed = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
    // or Policy.NO_EXEC_HOTSPOT — same filters; use NATIVE_IMAGE / Policy.NO_EXEC for W^X
)

// Everything submitted to this pool runs under the kernel-enforced policy.
sandboxed.submit {
    processUntrustedInput(payload)
    // Configured process-creation syscalls are denied on this worker.
}
```

---



## Common Recipes

### Recipe 1 — Data parser that reads files but never touches the network

```kotlin
val policy = Policy.builder()
    .base(Policy.NO_NETWORK)
    .allowFsRead("/data/incoming")   // explicit allow; everything else is denied by Landlock
    .allowJvmClasspath()             // required if your parser lazily loads classes
    .build()

val parserPool = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(2),
    policy
)
```

### Recipe 2 — High-security compute worker (crypto, image decode, ML inference)

These workers shouldn't touch the network, spawn processes, or write to arbitrary paths.

```kotlin
val policy = Policy.builder()
    .base(Policy.PURE_COMPUTE)       // blocks exec + network + filesystem writes + mmap(PROT_EXEC)
    .allowFsRead("/models")          // model weights
    .allowFsWrite("/tmp/inference")  // scratch space only
    .build()

val workerPool = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(8),
    policy
)
```

### Recipe 3 — Proving behavioral constraints for regulated data (PCI, HIPAA)

```kotlin
val policy = Policy.builder()
    .base(Policy.PURE_COMPUTE_UNSAFE)
    .allowFsRead("/secure/input")
    .allowFsWrite("/secure/output")
    // No .allowNetwork() → connect/sendmsg are blocked at kernel level
    .build()

val auditedPool = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(2), // dedicated; do not reuse outside this scope
    policy,
)

// Any ContainmentViolationException from this pool is observable proof
// that the declared behavioral constraints were violated — enforced by the kernel,
// not by application code that an attacker could bypass.
```

---

## Building a Custom Policy

```kotlin
val policy = Policy.builder()
    // Start from a built-in base
    .base(Policy.NO_EXEC_HOTSPOT)

    // Filesystem access (Landlock — inode-backed file or directory rules)
    .allowFsRead("/data/in")
    .allowFsWrite("/data/out")
    .allowJvmClasspath()       // auto-whitelists java.home + classpath entries

    // Fine-grained syscall control (advanced — see enforcer/README.md)
    // .allowMmapExec()         // needed if the pool runs a JIT-compiled native agent
    // .allowCloneFork()        // needed for libraries that fork subprocesses

    .build()
```

> [!NOTE]
> You don't need to enumerate JVM coordination syscalls (`futex`, `sched_yield`, `rt_sigreturn`). mazewall always whitelists them — blocking them would deadlock the JVM at the next GC cycle.

---

## What the Library Handles For You

This is the list of "scary JVM + Linux edge cases" that mazewall deals with internally so you don't have to:

| Edge case | What happens without care | What mazewall does |
|---|---|---|
| **JVM GC & safepoints** | Blocking `futex` or `sched_yield` deadlocks the entire JVM at the next GC | Always whitelists JVM coordination syscalls |
| **Lazy class loading** | Landlock blocks classfile reads → `NoClassDefFoundError` inside the sandbox | `allowJvmClasspath()` auto-discovers and whitelists all classpath entries |
| **Loom virtual threads** | Seccomp filters bind to OS threads; a filter set inside a virtual thread permanently poisons the shared carrier thread | Detects virtual threads at install time and throws `IllegalStateException` immediately |
| **Landlock + Seccomp ordering** | Installing Seccomp first blocks the `landlock_*` syscalls, making it impossible to add Landlock after | Always installs Landlock first, then Seccomp |
| **io_uring bypass** | `io_uring` submits I/O operations asynchronously via a kernel ring | Verify that the selected policy explicitly denies the relevant `io_uring` syscalls before relying on this restriction |

---

## Running the Test Suite

Tests that interact with the Linux kernel require specific capabilities and a custom seccomp profile. We provide orchestration scripts to run these safely inside a Podman container.

```bash
# 1. Run host-side unit tests only (fast, no kernel interaction)
./gradlew test

# 2. Run the full integration suite (requires Podman on the host)
./scripts/run_tests.sh

# 3. Run specific integration tests (e.g., enforcer module only)
./scripts/run_tests.sh :enforcer:integrationTest
```

---

## Debugging Containment Violations

If a sandboxed thread attempts an unauthorized operation, the Linux kernel intercepts it and blocks execution. The JVM then raises an exception:

```
io.mazewall.ContainmentViolationException: Containment violation detected: blocked syscall execve (59)
```

### How to resolve violations:
1. **Identify the missing capability:** The exception message tells you exactly which system call was blocked. If Landlock blocks a file read or write, standard Java I/O will throw an `AccessDeniedException`.
2. **Auto-generate a policy:** Rather than guessing which syscalls or paths to whitelist, run your target workload under the `:profiler` inside a unit test:
   ```kotlin
   val result = Profiler.profile {
       // Run the code that failed here
   }
   // toDsl() throws if exec/connect destinations were observed but cannot be enforced.
   // Pass allowIncomplete=true if you cannot enforce all destinations (e.g., dynamic exec).
   println(result.behavior.toDsl(allowIncomplete = true)) // Prints the exact Policy builder code needed!
   ```

---

## Known Limitations

- **Thread-scope vs Process-scope Isolation**: Thread-scoped restrictions do not isolate arbitrary hostile Java code. Attackers can hop to unrestricted executors, and all JVM threads share heap and address-space state. Existing socket file descriptors may remain usable through generic I/O calls. Combine with a process-wide `NO_EXEC` baseline (Tier 1) and external process/container isolation where that threat model requires it. See [designs/core/security-considerations.md](docs/internals/designs/core/security-considerations.md) for the full threat model.
- **JIT Compiler Coexistence**: The background threads that run JVM JIT compilation are unconstrained by thread-scoped policies. Therefore, thread-local executors wrapped with `Policy.NO_EXEC` or `Policy.PURE_COMPUTE` will *not* trigger JIT compiler `mmap(PROT_EXEC)` failures. However, if you apply a process-wide lockdown, use `Policy.NO_EXEC_HOTSPOT` (or append `.allowMmapExec()`) if JIT compilation is still active. Raw `Policy.NO_EXEC` denies `PROT_EXEC` mappings and can crash HotSpot.
- **Platform threads only**: Virtual threads (Loom) are explicitly rejected at runtime. Use platform thread pools for sandboxed work.
- **Linux only**: macOS and Windows do not have Seccomp-BPF or Landlock. The library will fail to install and throw (configurable via the `IO_MAZEWALL_FALLBACK` env var).
- **Policy coverage is empirical**: The profiler can record only the workload it sees. Re-run policy validation when input classes, error paths, native dependencies, the JVM, kernel, or deployment configuration changes.

---

## Next Steps

- **Demos:** See real CVE exploits blocked live → [demos/vulnerable-web-app/README.md](demos/vulnerable-web-app/README.md)
- **Profiler:** Automatically discover the minimal policy your workload needs → [profiler README](profiler/README.md)
- **Enforcer API reference:** Full policy builder docs → [enforcer/README.md](enforcer/README.md)
- **Threat model:** What mazewall stops and what it doesn't → [designs/core/security-considerations.md](docs/internals/designs/core/security-considerations.md)
- **Reproduction guide:** Environment and expected evidence for the provided demonstrations → [docs/REPRODUCING_RESULTS.md](docs/REPRODUCING_RESULTS.md)
