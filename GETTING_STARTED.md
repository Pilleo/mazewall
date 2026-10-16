# Getting Started with mazewall

This guide shows how to evaluate kernel-enforced restrictions for selected JVM workers. Read the [threat model](docs/internals/designs/core/security-considerations.md) before applying a policy to an application.

---

## Prerequisites

| Requirement | Minimum | Recommended |
|---|---|---|
| **JDK (application runtime)** | 22 | 25 |
| **Linux kernel** | 3.17 (Seccomp basic) | Verify the features required by your policy |
| **Landlock filesystem isolation** | kernel 5.13 | 6.7+ (adds TCP `bind`/`connect` port restrictions; not general network control) |

> [!NOTE]
> These kernel versions are feature minimums, not a full-feature guarantee. mazewall detects unavailable host features at runtime. The effective behavior depends on the configured fallback policy, kernel capabilities, and outer container restrictions; a dev container uses its host's kernel.

**No native C libraries or kernel modules are required. Root is not required on a compatible Linux host, but an outer container profile or runtime policy can prevent filter installation.**

Building this repository requires **JDK 25**. Kotlin consumers need a compiler
compatible with the library's metadata; the packaged consumer checks use stable
Kotlin **2.4.20** (the producer version is in [the version catalog](gradle/libs.versions.toml)).

---

## Installation

### Option 1: Build locally (recommended for evaluation)

With JDK 25 selected through `JAVA_HOME`, clone the repository and publish the
enforcer and its platform dependency to your local Maven repository:

```bash
git clone https://github.com/Pilleo/mazewall.git
cd mazewall
./gradlew :platform:publishToMavenLocal :enforcer:publishToMavenLocal
```

This builds the library artifacts without installing containment in your application
or running the kernel integration suites. It installs `io.mazewall` version
`0.1.0-SNAPSHOT` locally; repeating it replaces that local snapshot. It does not
publish to a remote package service.

In a **separate application project**, add the local repository and dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal {
            content { includeGroup("io.mazewall") }
        }
        mavenCentral()
    }
}

// build.gradle.kts
dependencies {
    implementation("io.mazewall:enforcer:0.1.0-SNAPSHOT")
}
```

Use `--enable-native-access=ALL-UNNAMED` when running a classpath application that
installs containment. Installing the dependency alone does not apply any policy;
continue with the trusted bootstrap and owned-worker example below.

### Option 2: JitPack (on-demand builds)

JitPack builds the selected Git revision on demand. Check the
[build status](https://jitpack.io/#Pilleo/mazewall) before using this path; it is not
the locally verified installation above. The repository is `Pilleo/mazewall` and
its default branch is `master`:

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
    implementation("com.github.Pilleo.mazewall:enforcer:master-SNAPSHOT")
}
```

### Option 3: GitHub Packages

This path requires package access and an available published version. Configure
credentials through your existing Gradle properties or operator-managed environment:

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
- Use `ManagedRuntime` to own dedicated **platform-thread** workers and require installation before admission. Kernel restrictions remain on living workers; unrelated pools and late callbacks are outside this contract.
- Thread-scoped restrictions protect trusted code handling untrusted data. They do not isolate arbitrary hostile Java code, existing file descriptors, shared JVM memory, or unrestricted sibling threads.
- Validate every policy against representative normal, error, lazy-loading, and dependency-upgrade workloads. Profiler output is a candidate policy, not proof of future behavior.

## Quick Start: Your First Evaluation

### Step 1 — Choose the process baseline at trusted bootstrap

The example below is for an application that needs no later child processes or
dynamic native-agent loading. Its baseline prevents process creation even from an
uncontained sibling thread. A host that launches or replaces Portal workers must
instead choose an explicit launch-compatible baseline. Starting a worker once does
not preserve permission to replace it later. Do not silently weaken the baseline.

> [!IMPORTANT]
> Once installed, the filter applies to current and future threads in the process and cannot be removed.

### Step 2 — Prepare owned workers and call a synchronous body

<!-- consumer: getting-started-owned -->
```kotlin
import io.mazewall.enforcer.api.Mazewall
import io.mazewall.enforcer.api.ProcessPolicies
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.ManagedRuntime
import java.time.Duration
import java.util.concurrent.Callable

// The application lifecycle owner retains this runtime before preparing workers.
fun bootstrapEvaluationRuntime(): ManagedRuntime = ManagedRuntime.open(
    RuntimeProfile.HOTSPOT_JIT,
    ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
    Duration.ofSeconds(10),
    Duration.ofSeconds(10),
)

fun prepareEvaluation(runtime: ManagedRuntime): InProcessDomain = runtime.prepare(
    WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(),
    DomainLimits(workers = 4, maxAdmissions = 64),
)

fun evaluate(
    runtime: ManagedRuntime,
    domain: InProcessDomain,
    workload: Callable<String>,
): String = Mazewall.callContained(runtime, domain, workload)

// Called by the lifecycle owner at shutdown, not by evaluate().
fun drainEvaluation(runtime: ManagedRuntime): DrainReport {
    val report = runtime.drain(Duration.ofSeconds(10))
    when (report) {
        is DrainReport.Complete -> println("Retirement complete")
        is DrainReport.Incomplete -> System.err.println("Retirement incomplete: $report")
    }
    return report // the owner keeps runtime reachable until Complete
}
```

During trusted platform-thread bootstrap, the application lifecycle owner calls
`bootstrapEvaluationRuntime()` once and stores its returned runtime **before**
calling `prepareEvaluation(runtime)`. It also retains the prepared domain and passes
both owners into each `evaluate(...)` call. A preparation failure or interrupted
evaluation does not discard or close that retained runtime. Every worker must
install required restrictions before readiness. Admission includes queued and running
invocations; overload rejects without executing on the caller. Four workers and 64
admissions are illustrative, not benchmark-derived defaults. Supported bodies return
materialized values; lazy results, independent executors and completion observers
are outside the protected synchronous body.

`runtime.submit(domain, Callable { ... })` returns a handle with separate `result()`
and `executionEnd()` stages. Timing out while waiting does not terminate Java code.
Request cooperative cancellation via `requestCancellation()` and retain the owner
until physical execution/cleanup ends. `drain(timeout)` stops admission and reports
complete or incomplete retirement; bounded `close()` throws `IncompleteDrainException`
if it cannot finish. Neither close nor a finished invocation removes kernel filters.
At application shutdown, call `drainEvaluation` from outside its owned workers.
On `Incomplete`, keep the same runtime reachable and report the unfinished work;
retry drain through the lifecycle owner after work/cleanup can finish. If using
`close()` instead, an `IncompleteDrainException` likewise leaves that owner retained.
Caller interruption and exceptional result delivery are not physical-end evidence.

---



## Common Recipes

### Recipe 1 — Data parser that reads files but never touches the network

<!-- consumer: getting-started-parser -->
```kotlin
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime

fun prepareParser(runtime: ManagedRuntime) = runtime.prepare(
    WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
    .base(Policy.NO_NETWORK)
    .allowFsRead("/data/incoming")   // explicit allow; everything else is denied by Landlock
    .allowJvmClasspath()             // required if your parser lazily loads classes
    .build(),
    DomainLimits(workers = 2, maxAdmissions = 16),
)
```

### Recipe 2 — High-security compute worker (crypto, image decode, ML inference)

These workers shouldn't touch the network, spawn processes, or write to arbitrary paths.

Use this recipe only in a runtime bootstrapped with `NATIVE_IMAGE`; executable
mapping denial is not safe merely because work runs on one HotSpot worker.

<!-- consumer: getting-started-compute -->
```kotlin
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime

fun prepareCompute(runtime: ManagedRuntime) = runtime.prepare(
    WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE)
    .base(Policy.PURE_COMPUTE)       // blocks exec + network + filesystem writes + mmap(PROT_EXEC)
    .allowFsRead("/models")          // model weights
    .allowFsWrite("/tmp/inference")  // scratch space only
    .build(),
    DomainLimits(workers = 8, maxAdmissions = 32),
)
```

### Recipe 3 — Restricting a reviewed data-processing operation

<!-- consumer: getting-started-data -->
```kotlin
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime

fun prepareDataOperation(runtime: ManagedRuntime) = runtime.prepare(
    WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
    .base(Policy.PURE_COMPUTE_UNSAFE)
    .forRuntime(RuntimeProfile.HOTSPOT_JIT)
    .allowFsRead("/secure/input")
    .allowFsWrite("/secure/output")
    .allowJvmClasspath()
    // No .allowNetwork() → connect/sendmsg are blocked at kernel level
    .build(),
    DomainLimits(workers = 2, maxAdmissions = 16),
)
```

An exception class alone is not confirmed kernel-denial evidence or regulatory
certification. Preserve observed versus inferred diagnostics and the original cause.

---

## Building a Custom Policy

<!-- consumer: getting-started-custom -->
```kotlin
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder

fun customPolicy() = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
    // Start from a built-in base
    .base(Policy.NO_EXEC_HOTSPOT)

    // Filesystem access (Landlock — inode-backed file or directory rules)
    .allowFsRead("/data/in")
    .allowFsWrite("/data/out")
    .allowJvmClasspath()       // auto-whitelists java.home + classpath entries

    // Fine-grained syscall control (advanced — see enforcer/README.md)
    // .allowMmapExec()         // needed if the pool runs a JIT-compiled native agent
    // .allowNonThreadClone(true) // advanced: permits non-thread clone; review process creation separately

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

Run these commands from the library checkout, not your application project.
Kernel integration tests require a compatible Linux host and sufficient permissions
under its outer Seccomp profile. The [testing strategy](docs/internals/designs/core/testing-strategy.md)
describes the prerequisites and the host/kernel split.

```bash
# Host-safe unit tests, static analysis, and coverage
./gradlew unitCheck

# Kernel integration suites in the configured Podman/Docker environment
./scripts/run_containerized_tests.sh :kernelCheck

# A specific kernel suite in the same environment
./scripts/run_containerized_tests.sh :enforcer:integrationTest

# Full quality gate on a suitably configured host
./gradlew build
```

---

## Debugging Containment Violations

Kernel-denied operations often surface as ordinary Java I/O failures. ERRNO-mode
Seccomp does not automatically supply the blocked syscall number to the JVM.
`EPERM`/`EACCES` and `AccessDeniedException` can also come from DAC, another LSM,
or an outer container policy. Such permission failures are inferred, not confirmed
MazeWall denials. An observed supervised event can identify the intercepted operation;
keep that evidence distinct from exception-based inference and preserve causes.

### How to resolve violations:
1. **Inspect evidence and causes:** Use observed event diagnostics where available; do not infer an exact syscall or enforcing layer from a generic permission exception. Unobserved swallowed kernel errors cannot be recovered from invocation evidence.
2. **Auto-generate a policy:** Rather than guessing which syscalls or paths to whitelist, run your target workload under the `:profiler` inside a unit test:
   Follow the [coverage-preserving profiler recipe](profiler/README.md#quick-start).
   Export through `result.toDsl(...)`, `result.toPolicy(...)` and
   `result.toProfileDocumentJson()` so measured coverage is retained. Incomplete
   coverage refuses conversion; profiling is candidate discovery, not review approval.

---

## Known Limitations

- **Thread-scope vs Process-scope Isolation**: Thread-scoped restrictions do not isolate arbitrary hostile Java code. Attackers can hop to unrestricted executors, and all JVM threads share heap and address-space state. Existing socket file descriptors may remain usable through generic I/O calls. Combine with a process-wide `NO_EXEC` baseline (Tier 1) and external process/container isolation where that threat model requires it. See [designs/core/security-considerations.md](docs/internals/designs/core/security-considerations.md) for the full threat model.
- **JIT Compiler Coexistence**: Unrestricted background compiler threads do not prove every executable-mapping request stays off a contained worker. Lazy/native/runtime work and inherited restrictions matter. Select `HOTSPOT_JIT` for HotSpot policies before restrictive composition and validate post-install workloads. `NO_EXEC`/`PURE_COMPUTE` deny executable mappings and are not unconditionally safe even thread-locally on HotSpot.
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
