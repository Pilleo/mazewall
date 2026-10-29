# mazewall

[![CI](https://github.com/Pilleo/mazewall/actions/workflows/ci.yml/badge.svg)](https://github.com/Pilleo/mazewall/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/Pilleo/mazewall.svg)](https://jitpack.io/#Pilleo/mazewall)

**Linux kernel-enforced syscall and filesystem restrictions for process and thread self restriction from inside an app.**

> [!NOTE]
> **Pre-Alpha.** Core enforcement and profiling work end-to-end. API is unstable and known issues are actively tracked. If you're evaluating this for your stack or want to contribute, [open a discussion](https://github.com/Pilleo/mazewall/discussions).

---

## Who Should Care

If your JVM service handles **untrusted input** — XML, YAML, PDF, SQL, user-uploaded files, third-party SDKs — then every one of those code paths runs with the same OS permissions as your authentication layer, your secret store, and your outbound HTTP client.

A successful exploit in your YAML importer is a successful exploit of your entire process.

mazewall lets you wrap trusted code that processes risky input in **kernel-enforced behavioral contracts**. On a contained worker, configured syscalls and filesystem accesses are denied by the Linux kernel. This limits direct data-plane attacks; it does not isolate arbitrary hostile Java code or remove capabilities already present elsewhere in the JVM.

## Try an independent runnable example

From the repository root, use JDK 25 on Linux with the required Seccomp/Landlock
support **and Clang with a BPF backend** for the source build, then run:

```bash
./gradlew :demos:cli-demo:runWorkspaceExample --console=plain
```

The CLI module also builds profiler resources, which invoke `clang -target bpf`
even though this example does not profile anything. See the
[compiler probe and configured development-container route](demos/cli-demo/README.md#start-here-an-owned-workspace-read)
before the first build. This is a source-build tool requirement, not a native C
runtime dependency of the core API.

It reads an approved file, proves a real denied sibling open while the host can
still read that sibling, and requires actual worker cleanup. It creates only
private synthetic files and launches a fresh JVM for the permanent process baseline.
Setup/containment failures fail the command rather than falling back unprotected.

- [Core walkthrough](demos/cli-demo/README.md#start-here-an-owned-workspace-read): copy the explicit owner/domain/call pattern.
- [Koog document agent](demos/koog-document-agent/README.md): an actual offline scripted AIAgent reads documents or calls a generated process-backed Markdown outline service; no Spring or model account required.
- [Spring report HTTP API](demos/spring-report-api/README.md): normal report code, real URL/method Security, request-specific JDBC commit/rollback and readiness; no agent framework required.
- [Koog thread and process tools](demos/koog-contained-tool/README.md): run actual upstream Tools without Spring or a model account.
- [Spring report application](demos/spring-boot-sandbox/README.md#start-here-ordinary-report-generation): normal Java service code, real JDBC and separately approved finished-JAR binding, without an agent or web server.

The new applications are standalone published-artifact consumers with their own
wrappers, locks/checksums and ordinary distributions. Artifact access and exact
version selection remain explicit; a local stage is not a verified remote release.
Their guides distinguish ordinary business code from trusted bootstrap/review and
test-only fault controls. See the [bounded application measurements](benchmarks/integration-boundaries/pitch/README.md)
for scope and limitations, not a negligible-overhead claim.

The [full feature tour](demos/README.md) retains the deeper profiling, lifecycle,
supervision and negative controls. The public synchronous core entry accepts
`Callable<T>` from Java and other JVM languages; see the existing
[Java](tests/consumers/java-api/src/main/java/consumer/JavaApiConsumer.java) and
[Kotlin](tests/consumers/kotlin-api/src/main/kotlin/consumer/KotlinApiConsumer.kt)
consumer examples for API syntax, not additional native proof.

---

## The Problem

### Before mazewall: flat permissions everywhere

Every thread in your JVM today has identical OS-level permissions:

```
HTTP Thread Pool      → can exec, can connect, can write anywhere
YAML Parser Pool      → can exec, can connect, can write anywhere  ← same
PDF Generator Pool    → can exec, can connect, can write anywhere  ← same
XML Importer Pool     → can exec, can connect, can write anywhere  ← same
```

A compromise in any one of these is a compromise of all of them. Log4Shell is the canonical example: the attacker's payload ran inside the logger thread, which had `execve` permission because the rest of the JVM needed it.

### Why Docker doesn't solve this

Docker's seccomp profile is a **city wall** — it applies one policy to the entire container process. That policy is determined by what the *most permissive legitimate thread* needs. Your HTTP handler needs network access, so every thread gets network access. Your startup routine needs process creation, so every thread gets process creation.

Docker can't tailor permissions to your internal business logic. It can't know that your PDF generator should never call home, or that your YAML importer should never spawn a child process. That gap is your attack surface.

```
Docker container (one policy for all):
┌──────────────────────────────────────────────────────┐
│  HTTP Handler    │ needs: execve ✓  network ✓  fs ✓  │
│  YAML Importer   │ needs: execve ✗  network ✗  fs ~  │  ← gets full access anyway
│  PDF Generator   │ needs: execve ✗  network ✗  fs ~  │  ← gets full access anyway
│  XML Importer    │ needs: execve ✗  network ✗  fs ~  │  ← gets full access anyway
└──────────────────────────────────────────────────────┘
  If the YAML importer is compromised → full process access
```

---

## The Solution

### After mazewall: kernel-enforced per-thread contracts

mazewall owns dedicated platform workers and installs the required **kernel-enforced syscall and filesystem restrictions** before admitting synchronous work:

```
HTTP Thread Pool      → default permissions (unchanged)
YAML Parser Pool      → no exec, no network, read /data/in only    [kernel-enforced]
PDF Generator Pool    → no exec, no network, no filesystem writes   [kernel-enforced]
XML Importer Pool     → no exec, no network, read /app/schemas only [kernel-enforced]
```

If trusted parsing code attempts a configured-forbidden syscall, the kernel denies it on that worker. The result is a smaller direct syscall and filesystem-access surface for that workload. The boundary does not prevent a Java-code-execution attacker from hopping to an unrestricted executor, using an inherited file descriptor, or accessing shared JVM memory.

The filter is applied via Linux `prctl`/`seccomp` — the same mechanism Docker uses, applied from inside the JVM, **without root privileges or native C dependencies**. A thread cannot remove an installed Seccomp filter; this monotonicity does not constrain unrestricted sibling threads.

<!-- consumer: readme-owned -->
```kotlin
import io.mazewall.enforcer.api.Mazewall
import io.mazewall.enforcer.api.ProcessPolicies
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.ManagedRuntime
import java.time.Duration
import java.util.concurrent.Callable

// Trusted platform-thread bootstrap, before application pools start.
// This application needs no later child processes, including Portal workers.
fun bootstrapOwnedRuntime(): ManagedRuntime = ManagedRuntime.open(
    RuntimeProfile.HOTSPOT_JIT,
    ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
    Duration.ofSeconds(10), // preparation deadline
    Duration.ofSeconds(10), // bounded close deadline
)

fun prepareOwnedDomain(runtime: ManagedRuntime): InProcessDomain = runtime.prepare(
    WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(),
    DomainLimits(workers = 4, maxAdmissions = 64),
)

fun ownedExample(
    runtime: ManagedRuntime,
    domain: InProcessDomain,
    workload: Callable<Int>,
): Int = Mazewall.callContained(runtime, domain, workload)
```

The application lifecycle owner stores the bootstrapped runtime before preparation,
retains the domain, and passes both into subsequent calls with a materialized
synchronous `Callable`. Evaluation never closes them. Admission
counts queued and running work; overload rejects without caller-runs fallback. The
example capacities are configuration, not measured defaults. A caller timeout does
not end execution: retain the invocation handle through `executionEnd()`, request
cooperative cancellation, and use bounded `drain(timeout)`. `close()` reports incomplete
retirement rather than removing filters. Process restrictions are permanent.
Bounded shutdown belongs to that application owner: report `DrainReport.Incomplete`
or `IncompleteDrainException` and retain the same runtime for a later drain retry.
Interrupted evaluation or exceptional delivery does not establish physical end.
Hosts that launch or replace Portal workers need an explicit launch-compatible process
baseline; the no-process-creation example forbids those launches. See
[owned lifecycle guidance](enforcer/README.md#owned-synchronous-workers).

---

## You Don't Write the Policy Yourself

The biggest concern people raise: *"How do I know which syscalls my code actually needs?"*

The `:profiler` module observes your workload during a test run and **generates a policy candidate from the behavior it sees**:

<!-- consumer: readme-profile -->
```kotlin
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.profiler.api.MazewallProfiler

fun discoverCandidate(workload: () -> Unit) {
    MazewallProfiler.open().use { session ->
        val result = session.profile { workload() }
        val base = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
            .base(Policy.PURE_COMPUTE_UNSAFE)
            .forRuntime(RuntimeProfile.HOTSPOT_JIT)
            .build()
        println(result.toProfileDocumentJson()) // behavior AND measured coverage
        println(result.toDsl(basePolicyName = "profileBase", base = base))
        // Incomplete coverage throws; it is not authorization to enforce.
    }
}
```

Treat generated output as a policy candidate: validate it against representative normal and failure workloads before enforcing it. Profiling cannot observe every future execution path, dependency upgrade, or deployment environment.

This observe → generate → enforce workflow is the foundation of [SBoB (Software Bill of Behavior)](docs/presentation/article1-threat-model.md) — a behavioral contract that travels alongside your application, analogous to an SBOM for composition but capturing *what your code is allowed to do at runtime*, not just what it contains.

---

## How the Layers Fit Together

mazewall operates in two tiers. Think of it as the difference between the building's outer wall and the locks on the rooms inside:

```
┌─────────────────────────────────────────────────────────────────────┐
│  Host / Container                                                   │
│  ┌──── Docker / Podman (city wall) ──────────────────────────────┐ │
│  │  One Seccomp profile for the entire container process          │ │
│  │                                                                │ │
│  │  ┌─────────── JVM Process ────────────────────────────────┐   │ │
│  │  │                                                         │   │ │
│  │  │  [Tier 1] installOnProcess(NO_EXEC_HOTSPOT)            │   │ │
│  │  │  Nothing in this JVM ever spawns a shell               │   │ │
│  │  │                                                         │   │ │
│  │  │  ┌─ HTTP Thread Pool ──┐  ┌─ YAML Import Pool ───────┐ │   │ │
│  │  │  │  [no extra limits]  │  │  [Tier 2]                │ │   │ │
│  │  │  │                     │  │  ✗ execve   ✗ network    │ │   │ │
│  │  │  └─────────────────────┘  │  📁 /data/in only        │ │   │ │
│  │  │                           └──────────────────────────┘ │   │ │
│  │  │  ┌─ PDF Generator Pool ────────────────────────────┐   │   │ │
│  │  │  │  [Tier 2]                                        │   │   │ │
│  │  │  │  ✗ execve   ✗ network   ✗ filesystem writes      │   │   │ │
│  │  │  └─────────────────────────────────────────────────┘   │   │ │
│  │  └─────────────────────────────────────────────────────────┘   │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

| Tier | API | Scope | Purpose |
|---|---|---|---|
| **Tier 1** | `ContainedExecutors.installOnProcess(Policy.NO_EXEC_HOTSPOT)` | Entire JVM | Global backstop — no exec/memfd; HotSpot JIT may still create executable mappings |
| **Tier 2** | `ManagedRuntime.prepare` + `Mazewall.callContained` | Owned platform workers | Required installation before synchronous body entry; finite admission and owned retirement |

> [!IMPORTANT]
> **Stack Tier 1 first.** Tier 2 alone does not fully isolate malicious code that can use standard Java concurrency APIs (`CompletableFuture`, virtual threads) to escape to unrestricted sibling threads. See [designs/core/security-considerations.md](docs/internals/designs/core/security-considerations.md) for the complete threat model.
> Choose the baseline explicitly at trusted bootstrap. A Portal host must retain the
> child-launch permissions it needs; do not install the diagram's no-process-creation
> baseline and expect later worker startup or replacement to succeed.

---

## How It Works (in plain terms)

mazewall combines two Linux kernel features with different histories and deployment models:

| Kernel feature | What it does | Docker equivalent |
|---|---|---|
| **Seccomp-BPF** | Blocks specific syscalls per thread | Container runtimes commonly apply process-wide Seccomp profiles |
| **Landlock LSM** | Restricts filesystem paths per thread | Similar goal to read-only filesystems and bind mounts, but a separate Linux LSM introduced in 2021 |

The difference: Docker applies these at the *container* (process) level. mazewall applies them at the *thread* (executor) level, from inside the JVM, without root privileges and without a native C dependency.

Implementation uses the JDK **Foreign Function & Memory API** (JDK 22+) to make the `prctl`/`seccomp` syscalls directly from Kotlin.

Elasticsearch pioneered a process-wide variant of this pattern in the JVM since 2015 (originally configured via `bootstrap.system_call_filter`, now a mandatory, non-configurable startup check). mazewall brings that approach to per-thread granularity, composable with standard Java executor infrastructure.

### Experimental: Stacktrace-Enforced Scoping (L4 Call-Path Binding)
In addition to static system call blocking, `mazewall` supports **Stacktrace-Enforced Scoping** (via the `StacktraceScopingPolicy` API). When a thread executes a supervised system call (such as a network `connect` or process spawning `vfork`), the kernel suspends the thread and delegates validation to the JVM. The supervisor retrieves the JVM stack trace of the blocked thread, allowing the call only if it originates from a pre-approved call path.

> [!WARNING]
> **Highly Experimental:** Stacktrace inspection requires triggering JVM safepoints. Supervising system calls that are executed while holding internal JVM monitors (e.g. `CLONE` during thread creation) causes circular JVM safepoint deadlocks. This feature is highly experimental and subject to change or removal in future versions.

---

## Built-In Policies

| Policy | Blocks | Plain English | Typical use |
|---|---|---|---|
| `Policy.NO_EXEC_HOTSPOT` | `execve`, `execveat`, `memfd_create` (JIT `mmap(PROT_EXEC)` allowed) | No shell / memfd; HotSpot can still compile | Process-wide startup on a JIT JVM — install this first |
| `Policy.NO_EXEC` | Same syscalls **plus** `PROT_EXEC` mmap/mprotect denies | W^X-style; can fatal HotSpot if installed process-wide | AOT / no further code generation only |
| `Policy.NO_NETWORK` | `connect`, `socket`, `sendmsg`, `io_uring_*` | Denies creation and connection of new sockets on the contained thread | XML/YAML/CSV parsers, file format processors |
| `Policy.PURE_COMPUTE` | Exec + network + filesystem writes + `mmap(PROT_EXEC)`, with JVM classpath auto-whitelisted | Reads code, touches nothing else | Image processing, crypto, ML inference |
| `Policy.PURE_COMPUTE_UNSAFE` | Same as above, without the JVM classpath whitelist | Strictest possible — may crash on lazy classloading | Pre-warmed, fully initialized workers only |

Policies are composable via a builder — see [GETTING_STARTED.md](GETTING_STARTED.md#building-a-custom-policy).

---

## Where to Go Next

For a logical, executable feature tour, open the [JUnit showcase tests](demos/README.md)
and follow the [presenter guide](docs/presentation/PRESENTER_GUIDE.md). Each example
has real assertions, a short outcome trace and its own native/resource lifetime.

### Build verification

Use `./gradlew unitCheck` for host-safe tests and coverage, `./gradlew kernelCheck` for privileged kernel integration tests, and `./gradlew build` for the full merge gate. Normal verification is read-only; run `./gradlew format`, `./gradlew updateDiagrams`, or `./gradlew installGitHooks` only when those explicit maintenance actions are intended. Dependency changes require a reviewed `./gradlew dependencies --write-locks --write-verification-metadata sha256` update.

The installed version-controlled commit hook also requires the full `./gradlew build`
without excluding `check`; any failed verification blocks the commit. See
[commit verification](CONTRIBUTING.md#commit-verification) for its log and regression check.

| I want to… | Go to |
|---|---|
| Install and write my first policy | [GETTING_STARTED.md](GETTING_STARTED.md) |
| Auto-generate a policy from my workload | [profiler/README.md](profiler/README.md) |
| See it block real CVEs (Log4Shell, SSRF, XXE) | [Demo README](demos/vulnerable-web-app/README.md) |
| Understand the threat model and what it can't stop | [designs/core/security-considerations.md](docs/internals/designs/core/security-considerations.md) |
| Reproduce the documented demonstrations | [docs/REPRODUCING_RESULTS.md](docs/REPRODUCING_RESULTS.md) |
| Read the deep-dive article series | [Article series](#article-series) |
| Contribute or modify the codebase | [CONTRIBUTING.md](CONTRIBUTING.md) |

---

## Project Modules

| Module | Purpose | Status |
|---|---|---|
| `:enforcer` | Core runtime — zero dependencies beyond Kotlin stdlib | Experimental; evaluate only with an explicit threat-model and supported-platform review |
| `:profiler` | Developer tool — profiles a workload and generates a minimal policy | Experimental policy discovery; generated policies require validation |
| `:integrations:koog` | [Explicit owned thread and process-Portal Koog tools](integrations/koog/README.md) | Optional adapter; trusted synchronous thread bodies or bounded process services |
| `:demos:cli-demo` | Interactive CLI exploits showcase | 🚫 Demo only |
| `:demos:agent-sandbox-demo` | Custom supervised tool compartments and offline JUnit controls | Experimental demo |
| `:demos:koog-contained-tool` | Generated bounded process-Portal tool | Optional Koog demo |
| `demos/spring-boot-sandbox` | Finished-JAR singleton, JDBC and owned execution | Standalone published consumer |
| `:demos:vulnerable-web-app` | Spring Boot CVE demo (Log4Shell, SSRF, XXE…) | 🚫 Demo only |

---

The library is actively developed. Core sandboxing (`NO_EXEC`, `NO_NETWORK`, `PURE_COMPUTE`) and the profiler workflow have automated test coverage, but APIs and behavior can change. Before evaluating it in an application, reproduce the relevant demonstrations, use dedicated platform-thread pools, install the process-wide baseline first, and review the [threat model](docs/internals/designs/core/security-considerations.md) and [backlog](docs/internals/backlog/README.md).

---

## Article Series

Background reading on the kernel mechanics and threat model:

| Part | Title | Focus |
|---|---|---|
| **1** | [Do You Really Know What Your App Is Doing at Runtime?](docs/presentation/article1-threat-model.md) | Seccomp, Landlock, SBoB concepts |
| **2** | [Let Your Code Build Its Own Sandbox](docs/presentation/article2-profiler.md) | Dynamic profiling, USER_NOTIF daemon |
| **3** | [Thread-Scoped JVM Containment: The Mechanics](docs/presentation/article3-enforcement.md) | FFM bridge, GC safepoints, Loom VT |
| **4** | [The Attacks We Actually Stop](docs/presentation/article4-attacks.md) | Log4Shell, fileless malware, io_uring bypass |
| **5** | [Generating an SBoB for Java: What's Missing](docs/presentation/article5-graalvm.md) | Dynamic classloading, GraalVM AOT |
| **6** | [Beyond the Thread: Isolates, WebAssembly, Tooling](docs/presentation/article6-isolates.md) | GraalVM Isolates, WASI, sidecar portals |

---

## License

Apache License 2.0.
